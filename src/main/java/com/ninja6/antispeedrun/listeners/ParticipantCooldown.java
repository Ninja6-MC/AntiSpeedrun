package com.ninja6.antispeedrun.listeners;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.storage.RespawnCooldownStore;

/**
 * The respawn-on-entry cooldown (#272): a player who took part in a won dragon fight cannot start a
 * respawn by entering until {@code boss-scaling.respawn-on-entry.participant-cooldown-hours} have
 * passed since that victory. The hours are read live, so a reload applies at once to every
 * recorded victory. One {@code volatile} read of the store and one of the configuration: any thread.
 */
public final class ParticipantCooldown implements RespawnCooldown {

    private final RespawnCooldownStore store;
    private final Supplier<PluginConfig> configuration;

    public ParticipantCooldown(RespawnCooldownStore store, Supplier<PluginConfig> configuration) {
        this.store = Objects.requireNonNull(store, "store");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    @Override
    public Optional<Duration> remaining(UUID player, long nowMillis) {
        return RespawnCooldownRules.remaining(store.lastVictory(player),
                configuration.get().bossScaling().respawnOnEntry().participantCooldownHours(), nowMillis);
    }
}
