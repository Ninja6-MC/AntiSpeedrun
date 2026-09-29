package com.ninja6.antispeedrun.listeners;

import java.util.concurrent.atomic.AtomicReference;

/**
 * One End world's reinforcement window (#37): whether it has opened, whether it has resolved, and
 * the cached answer to {@code DragonBattle#hasBeenPreviouslyKilled()}.
 *
 * <h2>Folia</h2>
 *
 * The battle belongs to the region owning chunk {@code (0, 0)}, so that is the only thread allowed to
 * ask it anything. Every player entering the End asks whether a window should open, from the
 * player's own region, so the answer is kept here in a {@code volatile} that only the {@code (0, 0)}
 * task writes (audit finding C-06). The phase is an {@link AtomicReference} for the same reason:
 * several players arriving in the same tick, each on a different region thread, must open the window
 * exactly once.
 */
public final class ReinforcementWindow {

    /** Where a window is in its life. */
    public enum Phase {

        /** No window is running. The next player to enter opens one. */
        IDLE,

        /** The countdown is running, or its census is in flight. */
        COUNTING,

        /** The census has been taken and the secondaries dispatched. Never reopens by entry. */
        RESOLVED
    }

    private final AtomicReference<Phase> phase;

    /** A window that has not opened. */
    public ReinforcementWindow() {
        this(false);
    }

    /**
     * @param resolved whether this fight's window already resolved in an earlier server run (#56), in
     *                 which case it never opens again
     */
    public ReinforcementWindow(boolean resolved) {
        this.phase = new AtomicReference<>(resolved ? Phase.RESOLVED : Phase.IDLE);
    }

    /**
     * {@code DragonBattle#hasBeenPreviouslyKilled()}, as last read on the {@code (0, 0)} region.
     * False until the first read: an unknown world is treated as one whose first fight is still to
     * come, and the {@code (0, 0)} task that the open schedules checks the battle itself before
     * counting anything.
     */
    private volatile boolean previouslyKilled;

    /** The current phase. */
    public Phase phase() {
        return phase.get();
    }

    /** The cached {@code hasBeenPreviouslyKilled()}. Legal from any thread. */
    public boolean previouslyKilled() {
        return previouslyKilled;
    }

    /** Refreshes the cache. Called only from the {@code (0, 0)} region, after reading the battle. */
    public void refreshPreviouslyKilled(boolean killed) {
        this.previouslyKilled = killed;
    }

    /**
     * Opens the window if none has run for this fight.
     *
     * @return true for exactly one caller: the one that must schedule the countdown
     */
    public boolean tryOpen() {
        if (previouslyKilled) {
            return false;
        }
        return phase.compareAndSet(Phase.IDLE, Phase.COUNTING);
    }

    /**
     * Closes a running window without resolving it, so the next entry may open it again. Used when
     * the {@code (0, 0)} task finds no fight to reinforce.
     */
    public void abandon() {
        phase.compareAndSet(Phase.COUNTING, Phase.IDLE);
    }

    /**
     * Marks the census as taken.
     *
     * @return true for exactly one caller: the one that must spawn the secondaries
     */
    public boolean resolve() {
        return phase.compareAndSet(Phase.COUNTING, Phase.RESOLVED);
    }

    /**
     * Marks the window resolved from any phase, without spawning. Used when a tagged secondary is
     * found in a loaded chunk (#56): the fight was reinforced in an earlier run, so a window counting
     * now must not resolve and spawn again.
     *
     * @return {@code true} if this changed the phase
     */
    public boolean markResolved() {
        return phase.getAndSet(Phase.RESOLVED) != Phase.RESOLVED;
    }
}
