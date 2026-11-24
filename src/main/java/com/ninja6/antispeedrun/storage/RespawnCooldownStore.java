package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * When each player last took part in a won dragon fight (#272), the timestamp the respawn-on-entry
 * cooldown is measured from. It is separate state from the lifetime {@code dragon-fight} credit in
 * {@link PersonalCreditStore}: a cooldown expiring or being cleared never touches that credit, and
 * revoking the credit never touches this.
 *
 * <h2>The document</h2>
 *
 * Kept in its own file, {@code respawn-cooldowns.yml}, for the reason {@link ReinforcedFightStore}
 * gives. One key per player, {@code last-victory.<uuid>}, holding the epoch millisecond of the most
 * recent won fight they took part in. Per player, not per world, so it carries across End worlds
 * and daily End replacement. Keys that are not a UUID, and values that are not a number, are
 * skipped on load.
 *
 * <h2>Threading and damage</h2>
 *
 * The contract of {@link ReinforcedFightStore}: {@link #lastVictory} is one {@code volatile} read,
 * legal from any thread; {@link #stamp} and {@link #clear} change the live record under a private
 * lock, publish it at once and hand the write to the I/O executor, and only when something changed.
 * {@link #loadNow()} is synchronous and runs in {@code onEnable}. An unreadable file is left
 * untouched until the first write, which moves it aside before writing a new one.
 */
public final class RespawnCooldownStore {

    /** Prefix of every key in the document. */
    public static final String KEY_PREFIX = "last-victory.";

    private final Logger logger;
    private final StateFile file;
    private final Executor ioExecutor;

    private final Object stateLock = new Object();
    private final Object writeLock = new Object();

    /** Player to last victory. Replaced wholesale, never mutated, so reads need no lock. */
    private volatile Map<UUID, Long> victories = Map.of();

    private volatile boolean writesBlocked;

    /**
     * @param ioExecutor runs the file writes; never a region thread. Tests pass {@code Runnable::run}
     */
    public RespawnCooldownStore(Logger logger, StateFile file, Executor ioExecutor) {
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
            this.victories = fromDocument(file.load());
            this.writesBlocked = false;
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "Could not read the respawn cooldown record; starting with no "
                    + "player on cooldown. The damaged file has NOT been overwritten: it will be moved "
                    + "aside under a .corrupt name before the next write. Cause: "
                    + failure.getMessage(), failure);
            this.victories = Map.of();
            this.writesBlocked = true;
            return false;
        }
    }

    /** Whether the damaged file from a failed {@link #loadNow()} is still waiting to be moved aside. */
    public boolean isAwaitingQuarantine() {
        return writesBlocked;
    }

    /** When {@code player} last took part in a won fight, in epoch milliseconds. Any thread. */
    public OptionalLong lastVictory(UUID player) {
        Long at = victories.get(Objects.requireNonNull(player, "player"));
        return at == null ? OptionalLong.empty() : OptionalLong.of(at);
    }

    /**
     * Records that {@code player} took part in a fight won at {@code atMillis}. A later victory
     * replaces an earlier one, so a second fight restarts the cooldown from its own victory even
     * while the first is still running; an earlier time never replaces a later one.
     *
     * @return {@code true} if this changed anything
     */
    public boolean stamp(UUID player, long atMillis) {
        Objects.requireNonNull(player, "player");
        synchronized (stateLock) {
            Map<UUID, Long> current = victories;
            Long previous = current.get(player);
            if (previous != null && previous >= atMillis) {
                return false;
            }
            Map<UUID, Long> next = new HashMap<>(current);
            next.put(player, atMillis);
            this.victories = Map.copyOf(next);
        }
        persist();
        return true;
    }

    /**
     * Removes {@code player}'s cooldown: a staff action. The {@code dragon-fight} credit is kept.
     *
     * @return {@code true} if they had one recorded
     */
    public boolean clear(UUID player) {
        Objects.requireNonNull(player, "player");
        synchronized (stateLock) {
            Map<UUID, Long> current = victories;
            if (!current.containsKey(player)) {
                return false;
            }
            Map<UUID, Long> next = new HashMap<>(current);
            next.remove(player);
            this.victories = Map.copyOf(next);
        }
        persist();
        return true;
    }

    /** The document {@code victories} is written as, in UUID order so the file diffs cleanly. */
    static Map<String, Object> toDocument(Map<UUID, Long> victories) {
        Map<String, Object> document = new LinkedHashMap<>();
        victories.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> document.put(KEY_PREFIX + entry.getKey(), entry.getValue()));
        return document;
    }

    /** Reads a document back, skipping keys that are not a UUID and values that are not a number. */
    static Map<UUID, Long> fromDocument(Map<String, Object> document) {
        Map<UUID, Long> victories = new HashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(KEY_PREFIX) || !(entry.getValue() instanceof Number at)) {
                continue;
            }
            try {
                victories.put(UUID.fromString(key.substring(KEY_PREFIX.length())), at.longValue());
            } catch (IllegalArgumentException notUuid) {
                // A hand-edited key. Skipped rather than failing the whole record.
            }
        }
        return Map.copyOf(victories);
    }

    /** Queues a write of the live record. Each write persists whatever is live when it runs. */
    void persist() {
        ioExecutor.execute(() -> {
            synchronized (writeLock) {
                if (!clearForWriting()) {
                    return;
                }
                try {
                    file.save(toDocument(victories));
                } catch (IOException | RuntimeException failure) {
                    logger.log(Level.SEVERE, "Could not persist the respawn cooldown record. A restart "
                            + "loses cooldowns changed since the last write. Cause: "
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
                logger.warning("The unreadable respawn cooldown record has been moved aside as \""
                        + moved + "\" so a new one could be written.");
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "The respawn cooldown record is unreadable and could not be "
                    + "moved aside, so it has been left as it is and the change was NOT persisted. "
                    + "Move or delete the file by hand to restore persistence. Cause: "
                    + failure.getMessage(), failure);
            return false;
        }
    }
}
