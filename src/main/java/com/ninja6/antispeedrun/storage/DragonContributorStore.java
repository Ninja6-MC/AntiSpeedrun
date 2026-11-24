package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The players who have contributed to each End world's dragon fight that has not been won yet
 * (#278). When the fight is won they earn {@link PersonalCredit#DRAGON_FIGHT} and the world's entry
 * is cleared; the credit itself lives in {@link PersonalCreditStore}, so earned qualification and
 * this pending list never share state.
 *
 * <p>A fight can have several dragons, each owned by a different region on Folia, so a contributor
 * cannot be kept on one dragon's persistent data container as a Wither's are: it is kept here, per
 * world, and survives restarts and unloads until the fight is won or abandoned.
 *
 * <h2>The document</h2>
 *
 * Kept in its own file, {@code dragon-contributors.yml}, for the reason {@link ReinforcedFightStore}
 * gives. One key per End world, {@code fight-contributors.<world-uid>}, holding the list of
 * contributor UUIDs in the order they first contributed. A world with nobody pending has no key, so
 * a replaced End's entry disappears with its fight and never matches the new world's UID. Keys that
 * are not a UUID, values that are not a list, and entries that are not a UUID are skipped on load.
 *
 * <h2>Threading and damage</h2>
 *
 * The contract of {@link ReinforcedFightStore}: {@link #contributors} is one {@code volatile} read,
 * legal from any thread; {@link #add} and {@link #take} change the live record under a private lock,
 * publish it at once and hand the write to the I/O executor, and only when something changed, so a
 * player hitting a dragon a hundred times writes once. {@link #loadNow()} is synchronous and runs in
 * {@code onEnable}. An unreadable file is left untouched until the first write, which moves it aside
 * before writing a new one.
 */
public final class DragonContributorStore {

    /** Prefix of every key in the document. */
    public static final String KEY_PREFIX = "fight-contributors.";

    private final Logger logger;
    private final StateFile file;
    private final Executor ioExecutor;

    private final Object stateLock = new Object();
    private final Object writeLock = new Object();

    /** World UID to contributors. Replaced wholesale, never mutated, so reads need no lock. */
    private volatile Map<UUID, Set<UUID>> pending = Map.of();

    private volatile boolean writesBlocked;

    /**
     * @param ioExecutor runs the file writes; never a region thread. Tests pass {@code Runnable::run}
     */
    public DragonContributorStore(Logger logger, StateFile file, Executor ioExecutor) {
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
            this.pending = fromDocument(file.load());
            this.writesBlocked = false;
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "Could not read the dragon fight contributor record; starting "
                    + "with no contributors pending. Players who damaged a dragon before this restart "
                    + "will not be credited when that fight is won unless they damage it again. The "
                    + "damaged file has NOT been overwritten: it will be moved aside under a .corrupt "
                    + "name before the next write. Cause: " + failure.getMessage(), failure);
            this.pending = Map.of();
            this.writesBlocked = true;
            return false;
        }
    }

    /** Whether the damaged file from a failed {@link #loadNow()} is still waiting to be moved aside. */
    public boolean isAwaitingQuarantine() {
        return writesBlocked;
    }

    /** The contributors pending in {@code world}'s fight, in first-contribution order. Any thread. */
    public Set<UUID> contributors(UUID world) {
        return pending.getOrDefault(Objects.requireNonNull(world, "world"), Set.of());
    }

    /**
     * Records {@code player} as a contributor to {@code world}'s current fight.
     *
     * @return {@code true} if this changed anything; {@code false} if they were already recorded, in
     *         which case nothing is written
     */
    public boolean add(UUID world, UUID player) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(player, "player");
        synchronized (stateLock) {
            Map<UUID, Set<UUID>> current = pending;
            Set<UUID> players = current.getOrDefault(world, Set.of());
            if (players.contains(player)) {
                return false;
            }
            Set<UUID> grown = new LinkedHashSet<>(players);
            grown.add(player);
            Map<UUID, Set<UUID>> next = new HashMap<>(current);
            next.put(world, Collections.unmodifiableSet(grown));
            this.pending = Map.copyOf(next);
        }
        persist();
        return true;
    }

    /**
     * Removes and returns {@code world}'s contributors: on victory, to credit them, and when a fight
     * is abandoned, to discard them.
     *
     * @return the contributors, in first-contribution order; empty, with nothing written, if none
     */
    public Set<UUID> take(UUID world) {
        Objects.requireNonNull(world, "world");
        Set<UUID> taken;
        synchronized (stateLock) {
            Map<UUID, Set<UUID>> current = pending;
            taken = current.get(world);
            if (taken == null) {
                return Set.of();
            }
            Map<UUID, Set<UUID>> next = new HashMap<>(current);
            next.remove(world);
            this.pending = Map.copyOf(next);
        }
        persist();
        return taken;
    }

    /** The document {@code pending} is written as, in UID order so the file diffs cleanly. */
    static Map<String, Object> toDocument(Map<UUID, Set<UUID>> pending) {
        Map<String, Object> document = new LinkedHashMap<>();
        pending.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    List<String> players = new ArrayList<>();
                    entry.getValue().forEach(player -> players.add(player.toString()));
                    document.put(KEY_PREFIX + entry.getKey(), List.copyOf(players));
                });
        return document;
    }

    /** Reads a document back, skipping anything that is not a UUID key holding a list of UUIDs. */
    static Map<UUID, Set<UUID>> fromDocument(Map<String, Object> document) {
        Map<UUID, Set<UUID>> pending = new HashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(KEY_PREFIX) || !(entry.getValue() instanceof Collection<?> values)) {
                continue;
            }
            UUID world;
            try {
                world = UUID.fromString(key.substring(KEY_PREFIX.length()));
            } catch (IllegalArgumentException notUuid) {
                continue;
            }
            Set<UUID> players = new LinkedHashSet<>();
            for (Object value : values) {
                try {
                    players.add(UUID.fromString(String.valueOf(value)));
                } catch (IllegalArgumentException notUuid) {
                    // A hand-edited entry. Skipped rather than failing the whole record.
                }
            }
            if (!players.isEmpty()) {
                pending.put(world, Collections.unmodifiableSet(players));
            }
        }
        return Map.copyOf(pending);
    }

    /** Queues a write of the live record. Each write persists whatever is live when it runs. */
    void persist() {
        ioExecutor.execute(() -> {
            synchronized (writeLock) {
                if (!clearForWriting()) {
                    return;
                }
                try {
                    file.save(toDocument(pending));
                } catch (IOException | RuntimeException failure) {
                    logger.log(Level.SEVERE, "Could not persist the dragon fight contributor record. "
                            + "A restart before this fight is won loses its contributors. Cause: "
                            + failure.getMessage(), failure);
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
                logger.warning("The unreadable dragon fight contributor record has been moved aside as \""
                        + moved + "\" so a new one could be written.");
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "The dragon fight contributor record is unreadable and could not "
                    + "be moved aside, so it has been left as it is and the change was NOT persisted. "
                    + "Move or delete the file by hand to restore persistence. Cause: "
                    + failure.getMessage(), failure);
            return false;
        }
    }
}
