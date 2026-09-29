package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Which End worlds have already had their reinforcement window resolved (#56, carried over from
 * #37), so a restart during the first dragon fight does not open a second window and spawn a second
 * set of secondary dragons on top of the ones already saved in the chunks.
 *
 * <p>Kept in its own file, {@code dragon-fights.yml}, rather than in {@code state.yml}: that file is
 * written wholesale from {@link UnlockState}, and sharing it would let each store erase the other's
 * keys.
 *
 * <h2>The document</h2>
 *
 * One key per world, {@code reinforced-fights.<world-uid>}, holding the epoch millisecond at which
 * the window resolved. The world's UID rather than its name: a reset End is a new world with a new
 * {@code uid.dat}, so an entry for the old one simply never matches again. Entries whose key is not a
 * UUID, or whose value is not a number, are skipped on load, as a hand edit is more likely than
 * damage.
 *
 * <h2>Threading and damage</h2>
 *
 * The same contract as {@link DimensionUnlockStore}: {@link #isReinforced} is one {@code volatile}
 * read, legal from any region thread; {@link #markReinforced} publishes synchronously and hands the
 * write to the I/O executor; {@link #loadNow()} is synchronous and runs in {@code onEnable}. An
 * unreadable file is left untouched until the first write, which moves it aside under a
 * {@code .corrupt-<timestamp>} name before writing a new one.
 */
public final class ReinforcedFightStore {

    /** Prefix of every key in the document. */
    public static final String KEY_PREFIX = "reinforced-fights.";

    private final Logger logger;
    private final StateFile file;
    private final Executor ioExecutor;

    private final Object stateLock = new Object();
    private final Object writeLock = new Object();

    /** World UID to resolve time. Replaced wholesale, never mutated, so reads need no lock. */
    private volatile Map<UUID, Long> reinforced = Map.of();

    private volatile boolean writesBlocked;

    /**
     * @param ioExecutor runs the file writes; never a region thread. Tests pass {@code Runnable::run}
     */
    public ReinforcedFightStore(Logger logger, StateFile file, Executor ioExecutor) {
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
            this.reinforced = fromDocument(file.load());
            this.writesBlocked = false;
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "Could not read the dragon fight record; starting with no "
                    + "reinforced fights recorded. Secondary dragons already spawned are still found "
                    + "when their chunks load. The damaged file has NOT been overwritten: it will be "
                    + "moved aside under a .corrupt name before the next write. Cause: "
                    + failure.getMessage(), failure);
            this.reinforced = Map.of();
            this.writesBlocked = true;
            return false;
        }
    }

    /** Whether the damaged file from a failed {@link #loadNow()} is still waiting to be moved aside. */
    public boolean isAwaitingQuarantine() {
        return writesBlocked;
    }

    /** Whether {@code world}'s reinforcement window has resolved. Legal from any thread. */
    public boolean isReinforced(UUID world) {
        return reinforced.containsKey(Objects.requireNonNull(world, "world"));
    }

    /**
     * Records that {@code world}'s window has resolved, and persists it.
     *
     * @return {@code true} if this changed anything; {@code false} if it was already recorded, in
     *         which case nothing is written
     */
    public boolean markReinforced(UUID world, long atMillis) {
        Objects.requireNonNull(world, "world");
        synchronized (stateLock) {
            Map<UUID, Long> current = reinforced;
            if (current.containsKey(world)) {
                return false;
            }
            Map<UUID, Long> next = new HashMap<>(current);
            next.put(world, atMillis);
            this.reinforced = Map.copyOf(next);
        }
        persist();
        return true;
    }

    /** The document {@code reinforced} is written as, in UID order so the file diffs cleanly. */
    static Map<String, Object> toDocument(Map<UUID, Long> reinforced) {
        Map<String, Object> document = new LinkedHashMap<>();
        reinforced.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> document.put(KEY_PREFIX + entry.getKey(), entry.getValue()));
        return document;
    }

    /** Reads a document back, skipping keys that are not a UUID and values that are not a number. */
    static Map<UUID, Long> fromDocument(Map<String, Object> document) {
        Map<UUID, Long> reinforced = new HashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(KEY_PREFIX) || !(entry.getValue() instanceof Number at)) {
                continue;
            }
            try {
                reinforced.put(UUID.fromString(key.substring(KEY_PREFIX.length())), at.longValue());
            } catch (IllegalArgumentException notUuid) {
                // A hand-edited key. Skipped rather than failing the whole record.
            }
        }
        return Map.copyOf(reinforced);
    }

    /** Queues a write of the live record. Each write persists whatever is live when it runs. */
    void persist() {
        ioExecutor.execute(() -> {
            synchronized (writeLock) {
                if (!clearForWriting()) {
                    return;
                }
                try {
                    file.save(toDocument(reinforced));
                } catch (IOException | RuntimeException failure) {
                    logger.log(Level.SEVERE, "Could not persist the dragon fight record. A restart "
                            + "during this fight relies on its secondary dragons' chunks loading to "
                            + "stop a second window. Cause: " + failure.getMessage(), failure);
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
                logger.warning("The unreadable dragon fight record has been moved aside as \""
                        + moved + "\" so a new one could be written.");
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "The dragon fight record is unreadable and could not be moved "
                    + "aside, so it has been left as it is and the new entry was NOT persisted. Move "
                    + "or delete the file by hand to restore persistence. Cause: "
                    + failure.getMessage(), failure);
            return false;
        }
    }
}
