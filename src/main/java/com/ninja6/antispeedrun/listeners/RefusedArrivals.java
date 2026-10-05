package com.ninja6.antispeedrun.listeners;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Arrivals the dimension gate's backstop is returning, handed from
 * {@link ProgressionGateListener#onPlayerAddedToWorld} to {@link BossCombatListener#onAddedToWorld}
 * for the same {@code EntityAddToWorldEvent} (#209).
 *
 * <p>On Folia no portal event fires, so a player who has not earned the End walks through its
 * portal, is added to the End, and only then is sent back by the backstop. Without this note the
 * reinforcement window would treat that arrival as the first entry and spend the fight's one window
 * on a player who is about to leave.
 *
 * <p>The gate marks at {@code HIGHEST} and the boss listener consumes at {@code MONITOR}, on the
 * thread firing the event, so a mark lives for one dispatch. The boss listener consumes on every
 * player arrival, so a mark it does not match cannot outlive the next one.
 */
public final class RefusedArrivals {

    private final Map<UUID, UUID> refused = new ConcurrentHashMap<>();

    /** Records that {@code player}'s arrival in {@code world} is being undone. */
    public void mark(UUID player, UUID world) {
        refused.put(Objects.requireNonNull(player, "player"), Objects.requireNonNull(world, "world"));
    }

    /**
     * Takes the mark for this arrival, if there is one.
     *
     * @return true when the gate is returning {@code player} from {@code world}
     */
    public boolean consume(UUID player, UUID world) {
        Objects.requireNonNull(world, "world");
        UUID marked = refused.remove(Objects.requireNonNull(player, "player"));
        return world.equals(marked);
    }
}
