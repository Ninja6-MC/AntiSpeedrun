package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Which trim structures each player has explored, as last seen while they were online (#18).
 *
 * <p>A Crafter has no player, so the template duplication lock asks on behalf of whoever placed it.
 * That owner is usually offline or in another region when the Crafter fires, and advancements can
 * only be read from a live {@code Player} on the thread that owns it. This record is the answer
 * that survives both: it is written from the player's own context whenever their exploration is
 * evaluated, and read from any thread afterwards.
 *
 * <h2>The document</h2>
 *
 * Kept in its own file, {@code explored-structures.yml}, for the reason
 * {@link ReinforcedFightStore} gives. One key per player, {@code explored-structures.<uuid>},
 * holding the list of structure milestone ids they have explored, such as
 * {@code trim:ancient_city}. A player who has explored nothing has no key. Keys that are not a UUID
 * and values that are not a list are skipped on load.
 *
 * <h2>Threading and damage</h2>
 *
 * The same contract as {@link ReinforcedFightStore}: {@link #hasExplored} is one {@code volatile}
 * read, legal from any region thread; {@link #record} publishes synchronously and hands the write to
 * the I/O executor, and only when something changed; {@link #loadNow()} is synchronous and runs in
 * {@code onEnable}. An unreadable file is left untouched until the first write, which moves it
 * aside before writing a new one.
 */
public final class ExploredStructureStore {

    /** Prefix of every key in the document. */
    public static final String KEY_PREFIX = "explored-structures.";

    private final Logger logger;
    private final StateFile file;
    private final Executor ioExecutor;

    private final Object stateLock = new Object();
    private final Object writeLock = new Object();

    /** Player to explored milestone ids. Replaced wholesale, never mutated, so reads need no lock. */
    private volatile Map<UUID, Set<String>> explored = Map.of();

    private volatile boolean writesBlocked;

    /**
     * @param ioExecutor runs the file writes; never a region thread. Tests pass {@code Runnable::run}
     */
    public ExploredStructureStore(Logger logger, StateFile file, Executor ioExecutor) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.file = Objects.requireNonNull(file, "file");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /**
     * Reads the file in, synchronously. Call once from {@code onEnable}.
     *
     * @return {@code false} if the file existed and could not be read, in which case the store starts
     *         empty and the file is preserved
     */
    public boolean loadNow() {
        try {
            this.explored = fromDocument(file.load());
            this.writesBlocked = false;
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "Could not read the explored structure record; starting with "
                    + "none recorded, so Crafters duplicating gated templates stop until their owners "
                    + "next join. The damaged file has NOT been overwritten: it will be moved aside "
                    + "under a .corrupt name before the next write. Cause: " + failure.getMessage(),
                    failure);
            this.explored = Map.of();
            this.writesBlocked = true;
            return false;
        }
    }

    /** Whether the damaged file from a failed {@link #loadNow()} is still waiting to be moved aside. */
    public boolean isAwaitingQuarantine() {
        return writesBlocked;
    }

    /** Whether {@code player} was last seen having explored {@code milestoneId}. Legal from any thread. */
    public boolean hasExplored(UUID player, String milestoneId) {
        Objects.requireNonNull(milestoneId, "milestoneId");
        Set<String> ids = explored.get(Objects.requireNonNull(player, "player"));
        return ids != null && ids.contains(milestoneId);
    }

    /**
     * Records whether {@code player} has explored {@code milestoneId}, and persists it. Both
     * directions are recorded: a revoked advancement clears the entry the next time it is evaluated.
     *
     * @return {@code true} if this changed anything; {@code false} if the record already said so, in
     *         which case nothing is written
     */
    public boolean record(UUID player, String milestoneId, boolean hasExplored) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(milestoneId, "milestoneId");
        synchronized (stateLock) {
            Map<UUID, Set<String>> current = explored;
            Set<String> before = current.getOrDefault(player, Set.of());
            if (before.contains(milestoneId) == hasExplored) {
                return false;
            }
            Set<String> after = new TreeSet<>(before);
            if (hasExplored) {
                after.add(milestoneId);
            } else {
                after.remove(milestoneId);
            }
            Map<UUID, Set<String>> next = new HashMap<>(current);
            if (after.isEmpty()) {
                next.remove(player);
            } else {
                next.put(player, Set.copyOf(after));
            }
            this.explored = Map.copyOf(next);
        }
        persist();
        return true;
    }

    /** The document {@code explored} is written as, sorted so the file diffs cleanly. */
    static Map<String, Object> toDocument(Map<UUID, Set<String>> explored) {
        Map<String, Object> document = new LinkedHashMap<>();
        explored.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> document.put(KEY_PREFIX + entry.getKey(),
                        List.copyOf(new TreeSet<>(entry.getValue()))));
        return document;
    }

    /** Reads a document back, skipping keys that are not a UUID and values that are not a list. */
    static Map<UUID, Set<String>> fromDocument(Map<String, Object> document) {
        Map<UUID, Set<String>> explored = new HashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(KEY_PREFIX) || !(entry.getValue() instanceof Collection<?> values)) {
                continue;
            }
            UUID player;
            try {
                player = UUID.fromString(key.substring(KEY_PREFIX.length()));
            } catch (IllegalArgumentException notUuid) {
                continue;
            }
            Set<String> ids = new TreeSet<>();
            for (Object value : values) {
                if (value instanceof String id && !id.isBlank()) {
                    ids.add(id);
                }
            }
            if (!ids.isEmpty()) {
                explored.put(player, Set.copyOf(ids));
            }
        }
        return Map.copyOf(explored);
    }

    /** Queues a write of the live record. Each write persists whatever is live when it runs. */
    void persist() {
        ioExecutor.execute(() -> {
            synchronized (writeLock) {
                if (!clearForWriting()) {
                    return;
                }
                try {
                    file.save(toDocument(explored));
                } catch (IOException | RuntimeException failure) {
                    logger.log(Level.SEVERE, "Could not persist the explored structure record. "
                            + "Crafters owned by players who explored a structure since the last "
                            + "successful write will refuse gated templates after a restart until "
                            + "their owners join. Cause: " + failure.getMessage(), failure);
                }
            }
        });
    }

    private boolean clearForWriting() {
        if (!writesBlocked) {
            return true;
        }
        try {
            String moved = file.quarantine().orElse(null);
            writesBlocked = false;
            if (moved != null) {
                logger.warning("The unreadable explored structure record has been moved aside as \""
                        + moved + "\" so a new one could be written.");
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "The explored structure record is unreadable and could not be "
                    + "moved aside, so it has been left as it is and the new entry was NOT persisted. "
                    + "Move or delete the file by hand to restore persistence. Cause: "
                    + failure.getMessage(), failure);
            return false;
        }
    }
}
