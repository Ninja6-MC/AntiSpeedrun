package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;
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
 * <h2>A damaged file survives contact with a write</h2>
 *
 * Starting empty on an unreadable {@code state.yml} is deliberate — refusing to boot a server
 * because a file holding two timestamps was truncated would be worse. <em>Overwriting</em> it
 * afterwards is not. The damaged document is the only surviving record of which dimensions an
 * operator had opened, so replacing it with an empty one on the next {@code /asr unlock} destroys
 * evidence they may still need, silently, at the moment they are least likely to be looking.
 *
 * <p>So a failed load latches {@link #writesBlocked}, and the first write that follows must move
 * the damaged document aside through {@link StateFile#quarantine()} before it may write anything.
 * Quarantine, rather than a flat refusal to persist: refusing would keep the old file intact but
 * would make every unlock granted for the rest of the server's run silently non-durable, which is
 * the same class of quiet data loss one step removed. Moving it aside preserves both — the
 * operator keeps the damaged bytes under a {@code .corrupt-<timestamp>} name, and new unlocks
 * persist normally. If the move itself fails the write is abandoned and the latch stays set, so a
 * later unlock retries rather than proceeding over an unquarantined file.
 *
 * <p>This mirrors what {@code ConfigSnapshotHolder} does for {@code config.yml}: a document that
 * fails to parse never causes the state derived from it to be reset.
 *
 * <h2>Write ordering</h2>
 *
 * Each queued write persists whatever the live state is when it runs, not a document captured when
 * it was queued. Two unlocks in quick succession therefore converge on the same file regardless of
 * the order the executor runs them in, and no write can resurrect a state that has since been
 * superseded. {@link #writeLock} serialises the writes themselves so two tasks cannot interleave
 * inside {@link StateFile#save}.
 *
 * <p>The mutation is serialised separately, by {@link #stateLock}: deriving the new state from the
 * live one and publishing it is one indivisible step, so an {@code /asr unlock} and an
 * {@code /asr unlock ... lock} issued in the same instant cannot lose one of the two updates.
 */
public final class DimensionUnlockStore {

    private final Logger logger;
    private final StateFile file;
    private final Executor ioExecutor;

    /**
     * Serialises the read-derive-write in {@link #swap}. Held across an {@code EnumMap} copy and
     * nothing else — never across file I/O, so a region thread may take it. Distinct from
     * {@link #writeLock}, which serialises the writes themselves on the I/O executor.
     */
    private final Object stateLock = new Object();

    private final Object writeLock = new Object();

    /** Volatile for the reason given in the class javadoc: written here, read from every region. */
    private volatile UnlockState state = UnlockState.empty();

    /**
     * Set when a load failed, cleared once the damaged document has been moved aside. While it is
     * set, no write may touch the file — see the class javadoc.
     */
    private volatile boolean writesBlocked;

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
     * damaged. It is <em>not</em> then overwritten — the first write that follows moves it aside
     * first. See the class javadoc.
     *
     * @return {@code true} if the file was read (or was simply absent), {@code false} if it existed
     *         and could not be read
     */
    public boolean loadNow() {
        try {
            this.state = UnlockState.fromDocument(file.load());
            this.writesBlocked = false;
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "Could not read AntiSpeedrun state; starting with no dimension "
                    + "unlocks recorded. Any unlock granted with /asr unlock before this restart is "
                    + "no longer in effect. The damaged file has NOT been overwritten: it will be "
                    + "moved aside under a .corrupt name if and when a new unlock needs to be "
                    + "written. Cause: " + failure.getMessage(), failure);
            this.state = UnlockState.empty();
            this.writesBlocked = true;
            return false;
        }
    }

    /**
     * Whether a damaged document is still waiting to be moved aside.
     *
     * <p>True between a failed {@link #loadNow()} and the first successful quarantine. For
     * diagnostics and for the store's own tests; nothing in the plugin branches on it.
     */
    public boolean isAwaitingQuarantine() {
        return writesBlocked;
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
        Objects.requireNonNull(dimension, "dimension");
        return swap(current -> current.unlocked(dimension, atMillis));
    }

    /**
     * Closes {@code dimension} again, returning it to normal progression gating, and persists it.
     *
     * @return {@code true} if this changed anything; {@code false} if it was not open
     */
    public boolean lock(DimensionUnlock dimension) {
        Objects.requireNonNull(dimension, "dimension");
        return swap(current -> current.locked(dimension));
    }

    /**
     * Derives a new state from the live one and publishes it, as one indivisible step.
     *
     * <p>{@link #stateLock} is what makes {@code /asr unlock nether} and {@code /asr lock end}
     * running at the same instant safe. Both used to read {@link #state}, derive from that read and
     * write the result back; two of them interleaving lost whichever update was derived from the
     * older snapshot, and the command still reported success (#75). The write lock further down
     * guards the file, not this derivation, so it could not help.
     *
     * <p>Nothing but an {@code EnumMap} copy happens inside the lock — no file I/O, so this stays
     * legal to call from a region thread — and {@link #persist()} is deliberately outside it, since
     * it only queues a task onto the I/O executor.
     */
    private boolean swap(UnaryOperator<UnlockState> derivation) {
        synchronized (stateLock) {
            UnlockState current = state;
            UnlockState candidate = derivation.apply(current);
            if (candidate == current) {
                return false;
            }
            this.state = candidate;
        }
        persist();
        return true;
    }

    /** Queues a write of the current state. Package-private so the store's tests can force one. */
    void persist() {
        ioExecutor.execute(() -> {
            synchronized (writeLock) {
                if (!clearForWriting()) {
                    return;
                }
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

    /**
     * Moves a damaged document aside, if one is outstanding, so the caller may write.
     *
     * <p>Runs on the I/O executor under {@link #writeLock}, immediately before the write it is
     * guarding. Returns {@code false} to abandon that write and leave the damaged file untouched.
     *
     * @return whether it is safe to write
     */
    private boolean clearForWriting() {
        if (!writesBlocked) {
            return true;
        }
        try {
            String moved = file.quarantine().orElse(null);
            writesBlocked = false;
            if (moved != null) {
                logger.warning("The unreadable AntiSpeedrun state file has been moved aside as \""
                        + moved + "\" so a new one could be written. Nothing in it was lost; inspect "
                        + "it if you need to know which dimensions had been unlocked before it was "
                        + "damaged.");
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            // Abandon the write rather than overwrite an unreadable file that is still the only
            // record of what had been unlocked. The latch stays set, so a later unlock retries.
            logger.log(Level.SEVERE, "The AntiSpeedrun state file is unreadable and could not be "
                    + "moved aside, so it has been left exactly as it is and the new unlock was NOT "
                    + "persisted. The unlock is in effect for this server run only. Move or delete "
                    + "the file by hand to restore persistence. Cause: " + failure.getMessage(),
                    failure);
            return false;
        }
    }
}
