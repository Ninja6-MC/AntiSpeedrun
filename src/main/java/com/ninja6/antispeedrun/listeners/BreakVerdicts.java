package com.ninja6.antispeedrun.listeners;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Carries one answer about a block break from an early handler to the {@code MONITOR} handler of
 * the same event.
 *
 * <p>The mining credit (#213) needs two things that are only both known at different priorities:
 * whether the placed-block registry held the block, which {@code PlacedBlockListener} clears at
 * {@code MONITOR}, and whether the break was finally allowed, which is only settled at
 * {@code MONITOR}. Reading the registry at {@code MONITOR} would depend on the order of two
 * same-priority listeners, which Bukkit does not define. So the registry is read at
 * {@code HIGHEST}, strictly before any {@code MONITOR} handler, and the answer waits here.
 *
 * <p>Per thread, because Folia breaks blocks on many region threads at once and an event is
 * dispatched entirely on one of them. Keyed by the event's identity, so a break another plugin
 * fires from inside a handler (a vein miner, say) keeps its own answer. {@link #take} removes the
 * entry whatever it returns; the {@code MONITOR} handler always calls it, cancelled or not, so
 * nothing is left behind.
 */
final class BreakVerdicts {

    private final ThreadLocal<Map<Object, Boolean>> pending =
            ThreadLocal.withInitial(IdentityHashMap::new);

    /** Holds {@code placed} for {@code event} until {@link #take}. */
    void put(Object event, boolean placed) {
        pending.get().put(Objects.requireNonNull(event, "event"), placed);
    }

    /** The answer held for {@code event}, removed; {@code null} if none was held. */
    Boolean take(Object event) {
        return pending.get().remove(Objects.requireNonNull(event, "event"));
    }

    /** How many answers this thread is holding. For tests. */
    int heldOnThisThread() {
        return pending.get().size();
    }
}
