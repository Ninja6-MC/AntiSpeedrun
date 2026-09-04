package com.ninja6.antispeedrun.progression;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Per-player state keyed by {@link UUID}, safe to reach from any Folia region thread.
 *
 * <p>A thin, deliberately small wrapper over a {@link ConcurrentHashMap}. It exists for two
 * reasons that a bare map cannot serve:
 *
 * <ol>
 *   <li>It is registered with a {@link PlayerStateRegistry}, so quit cleanup happens once for
 *       every feature rather than being re-implemented — and forgotten — per listener. Finding
 *       R-08 is precisely a map that nobody remembered to clear.</li>
 *   <li>It carries a {@link #name()}, so a leak is diagnosable: a registry dump names the map that
 *       grew rather than reporting one anonymous total.</li>
 * </ol>
 *
 * <p>Obtain one from {@link PlayerStateRegistry#register(String)}; there is no public constructor,
 * because an unregistered map is exactly the leak this type exists to prevent.
 *
 * @param <T> the state held per player. Should be immutable or itself thread-safe: this class
 *            makes the <em>map</em> safe, not whatever is stored in it
 */
public final class PlayerStateMap<T> {

    private final String name;
    private final ConcurrentHashMap<UUID, T> values = new ConcurrentHashMap<>();

    PlayerStateMap(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /** The diagnostic name this map was registered under. */
    public String name() {
        return name;
    }

    /** The player's state, if any is held. */
    public Optional<T> get(UUID player) {
        return Optional.ofNullable(values.get(Objects.requireNonNull(player, "player")));
    }

    /** The player's state, or {@code fallback} when none is held. */
    public T getOrDefault(UUID player, T fallback) {
        return values.getOrDefault(Objects.requireNonNull(player, "player"), fallback);
    }

    /** Stores {@code value}, replacing anything held. */
    public void put(UUID player, T value) {
        values.put(Objects.requireNonNull(player, "player"), Objects.requireNonNull(value, "value"));
    }

    /**
     * Computes and stores the player's state if absent, atomically.
     *
     * <p>{@code factory} runs while the map's bin is held, so it must not touch this map or any
     * other {@link PlayerStateMap}.
     */
    public T computeIfAbsent(UUID player, Function<UUID, ? extends T> factory) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(factory, "factory");
        return values.computeIfAbsent(player, factory);
    }

    /** Removes and returns the player's state, if any. */
    public Optional<T> remove(UUID player) {
        return Optional.ofNullable(values.remove(Objects.requireNonNull(player, "player")));
    }

    /** Whether any state is held for the player. */
    public boolean contains(UUID player) {
        return values.containsKey(Objects.requireNonNull(player, "player"));
    }

    /** How many players currently have state here. Diagnostics and leak assertions. */
    public int size() {
        return values.size();
    }

    /** Drops every entry. Used on disable, and by {@link PlayerStateRegistry#forgetAll()}. */
    public void clear() {
        values.clear();
    }
}
