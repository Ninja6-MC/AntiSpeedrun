package com.ninja6.antispeedrun.listeners;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The boss-bar fill of every living secondary dragon, per End world (#21).
 *
 * <p>Each secondary writes its own entry on its own region, and removes it when it dies, is removed
 * or unloads. Viewers read a copy of their world's entries on their own region. No bar is kept here:
 * see {@link SecondaryDragonRules} for why every viewer has bars of their own.
 */
public final class SecondaryBarBoard {

    private final Map<UUID, Map<UUID, Float>> worlds = new ConcurrentHashMap<>();

    /** Records {@code dragon}'s current fill in {@code world}. */
    public void publish(UUID world, UUID dragon, float progress) {
        Objects.requireNonNull(dragon, "dragon");
        worlds.computeIfAbsent(Objects.requireNonNull(world, "world"), id -> new ConcurrentHashMap<>())
                .put(dragon, progress);
    }

    /**
     * Removes {@code dragon}'s bar; every viewer drops it on their next refresh.
     *
     * @return {@code true} if it had one
     */
    public boolean withdraw(UUID world, UUID dragon) {
        Map<UUID, Float> dragons = worlds.get(Objects.requireNonNull(world, "world"));
        return dragons != null && dragons.remove(Objects.requireNonNull(dragon, "dragon")) != null;
    }

    /** Removes every bar in {@code world}. For a world that unloads. */
    public void forgetWorld(UUID world) {
        worlds.remove(Objects.requireNonNull(world, "world"));
    }

    /** The bars in {@code world} at this moment, as an immutable copy. */
    public Map<UUID, Float> snapshot(UUID world) {
        Map<UUID, Float> dragons = worlds.get(Objects.requireNonNull(world, "world"));
        return dragons == null ? Map.of() : Map.copyOf(dragons);
    }
}
