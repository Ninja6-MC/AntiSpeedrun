package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The server-wide record of which dimension gates an operator has opened, and the thing that makes
 * {@code /asr unlock} survive a restart (finding C-08).
 *
 * <h2>Threading</h2>
 *
 * Reads are a single {@code volatile} read and are legal from any thread, exactly like
 * {@code plugin.configuration()}. A mutation updates that reference synchronously — so the very
 * next gate check on any region already sees the unlock — and hands the file write to the
 * {@link Executor} supplied at construction, which in production is Folia's {@code AsyncScheduler}.
 * Nothing here blocks a region thread.
 *
 * <p>{@link #loadNow()} is the one exception and is synchronous by design: it runs during
 * {@code onEnable}, before any region is serving, for the same reason {@code config.yml} is read
 * there. A store that populated itself asynchronously would answer "locked" for the first few
 * milliseconds of the server's life, which is a gate flapping open and shut at exactly the moment
 * players reconnect.
 *
 * <h2>Write ordering</h2>
 *
 * Each queued write persists whatever the live state is when it runs, not a document captured when
 * it was queued. Two unlocks in quick succession therefore converge on the same file regardless of
 * the order the executor runs them in, and no write can resurrect a state that has since been
 * superseded. {@link #writeLock} serialises the writes themselves so two tasks cannot interleave
 * inside {@link StateFile#save}.
 */
public final class DimensionUnlockStore {

    private final Logger logger;
    private final StateFile file;
    private final Executor ioExecutor;
    private final Object writeLock = new Object();

    /** Volatile for the reason given in the class javadoc: written here, read from every region. */
    private volatile UnlockState state = UnlockState.empty();

    /**
     * @param logger     plugin logger
     * @param file       where the state lives
     * @param ioExecutor runs the file writes. Must not execute on a region thread; production
     *                   passes a dispatch onto Folia's {@code AsyncScheduler}. Tests pass
     *                   {@code Runnable::run}
     */
    public DimensionUnlockStore(Logger logger, StateFile file, Executor ioExecutor) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.file = Objects.requireNonNull(file, "file");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /**
     * Reads the persisted state in, synchronously. Call once from {@code onEnable}.
     *
     * <p>An unreadable file is logged and treated as empty rather than thrown: the alternative is
     * refusing to start a server because a state file that only ever holds two timestamps was
     * damaged. The next successful write replaces it.
     *
     * @return {@code true} if the file was read (or was simply absent), {@code false} if it existed
     *         and could not be read
     */
    public boolean loadNow() {
        try {
            this.state = UnlockState.fromDocument(file.load());
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "Could not read AntiSpeedrun state; starting with no dimension "
                    + "unlocks recorded. Any unlock granted with /asr unlock before this restart is "
                    + "no longer in effect. Cause: " + failure.getMessage(), failure);
            this.state = UnlockState.empty();
            return false;
        }
    }

    /** The live state. One volatile read; hold the returned value for the rest of your handler. */
    public UnlockState state() {
        return state;
    }

    /**
     * Whether an operator has opened {@code dimension} server-wide.
     *
     * <p>This is an <em>override</em>, not a replacement for the gate: the dimension listeners
     * (#34, #35) consult it alongside {@code ProgressionManager}, so an unlocked dimension admits
     * everyone regardless of progression while a locked one changes nothing.
     */
    public boolean isUnlocked(DimensionUnlock dimension) {
        return state.isUnlocked(dimension);
    }

    /**
     * Opens {@code dimension} for everyone and persists it.
     *
     * @param atMillis when the unlock happened, for the record kept in the file
     * @return {@code true} if this changed anything; {@code false} if it was already open, in which
     *         case nothing is written
     */
    public boolean unlock(DimensionUnlock dimension, long atMillis) {
        return swap(state.unlocked(Objects.requireNonNull(dimension, "dimension"), atMillis));
    }

    /**
     * Closes {@code dimension} again, returning it to normal progression gating, and persists it.
     *
     * @return {@code true} if this changed anything; {@code false} if it was not open
     */
    public boolean lock(DimensionUnlock dimension) {
        return swap(state.locked(Objects.requireNonNull(dimension, "dimension")));
    }

    private boolean swap(UnlockState candidate) {
        if (candidate == state) {
            return false;
        }
        this.state = candidate;
        persist();
        return true;
    }

    /** Queues a write of the current state. Package-private so the store's tests can force one. */
    void persist() {
        ioExecutor.execute(() -> {
            synchronized (writeLock) {
                UnlockState current = state;
                try {
                    file.save(current.toDocument());
                } catch (IOException | RuntimeException failure) {
                    logger.log(Level.SEVERE, "Could not persist AntiSpeedrun state. The unlock is in "
                            + "effect for this server run but will NOT survive a restart. Cause: "
                            + failure.getMessage(), failure);
                }
            }
        });
    }
}
