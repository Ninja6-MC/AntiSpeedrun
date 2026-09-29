package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Joins the per-player answers of a party census into one count (#37, audit finding C-06).
 *
 * <p>The census cannot read another player's location from the {@code (0, 0)} region: on Folia that
 * is a cross-region read. Each player is instead asked on their own {@code EntityScheduler}, and
 * reports here. Exactly one report is expected per player asked — {@link #counted} when the task
 * ran, {@link #missed} when the player was retired before it could — and the last one to arrive,
 * whichever thread it is on, hands the total to the completion callback. The callback runs once.
 */
public final class CensusTally {

    private final AtomicInteger outstanding;
    private final AtomicInteger onIsland = new AtomicInteger();
    private final IntConsumer onComplete;

    private CensusTally(int expected, IntConsumer onComplete) {
        this.outstanding = new AtomicInteger(expected);
        this.onComplete = onComplete;
    }

    /**
     * Starts a census.
     *
     * @param expected   how many players will report; zero completes immediately with a count of zero
     * @param onComplete receives the number of players counted, once, on the last reporter's thread
     */
    public static CensusTally start(int expected, IntConsumer onComplete) {
        if (expected < 0) {
            throw new IllegalArgumentException("expected must not be negative: " + expected);
        }
        CensusTally tally = new CensusTally(expected, Objects.requireNonNull(onComplete, "onComplete"));
        if (expected == 0) {
            onComplete.accept(0);
        }
        return tally;
    }

    /** One player's answer. */
    public void counted(boolean countsTowardParty) {
        // The increment is ordered before the decrement, so whoever takes outstanding to zero sees it.
        if (countsTowardParty) {
            onIsland.incrementAndGet();
        }
        arrive();
    }

    /** A player who could not be asked: logged out, or removed, before their task ran. */
    public void missed() {
        arrive();
    }

    private void arrive() {
        int left = outstanding.decrementAndGet();
        if (left == 0) {
            onComplete.accept(onIsland.get());
        } else if (left < 0) {
            throw new IllegalStateException("more reports than players asked");
        }
    }
}
