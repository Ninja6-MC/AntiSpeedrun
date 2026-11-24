package com.ninja6.antispeedrun.listeners;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether a player may start a dragon respawn by entering the End (#271). The per-player cooldown
 * of #272 implements it; until then {@link #NONE} lets everyone trigger.
 *
 * <p>A cooldown only decides who may start a respawn. It never removes a player from a fight
 * someone else started, and its expiry starts nothing by itself: only a later entry does.
 * Implementations are read on the entering player's region and must be safe on any thread.
 */
@FunctionalInterface
public interface RespawnCooldown {

    /** No cooldown: every eligible player may trigger a respawn. */
    RespawnCooldown NONE = (player, nowMillis) -> Optional.empty();

    /**
     * How long {@code player} must still wait before an entry of theirs can start a respawn.
     *
     * @return empty when they may trigger one now
     */
    Optional<Duration> remaining(UUID player, long nowMillis);
}
