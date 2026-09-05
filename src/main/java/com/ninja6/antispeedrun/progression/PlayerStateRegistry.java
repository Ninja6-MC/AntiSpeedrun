package com.ninja6.antispeedrun.progression;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single place per-player state is allowed to live, and the single place it is cleaned up.
 *
 * <p>The plugin accumulates several unrelated per-player maps — the action-bar feedback throttle
 * (#12), idle coordinates and reminder cooldowns (#4), temporary {@code /asr bypass} flags (#40),
 * the progression cache and the set of gates a player has already been congratulated on. Findings
 * C-07 and R-08 are the two ways that goes wrong: a plain {@code HashMap} reached from several
 * Folia region threads is a corruption path, and a map that nobody clears on quit grows for the
 * lifetime of the server.
 *
 * <p>Registering a map here fixes both at once. Every map is a {@link ConcurrentHashMap} by
 * construction, and one {@link #forget(UUID)} on {@code PlayerQuitEvent} clears every registered
 * map, including maps added by features written later that never touched the quit listener.
 *
 * <h2>Threading</h2>
 *
 * Thread-safe. {@link #register(String)} is expected during {@code onEnable} but is safe at any
 * time: the registration list is copy-on-write, so a concurrent {@link #forget(UUID)} sees a
 * consistent snapshot of the maps. A map registered <em>during</em> a {@code forget} may miss that
 * one call, which is harmless — a map that has just been created holds nothing to clear.
 */
public final class PlayerStateRegistry {

    private final CopyOnWriteArrayList<PlayerStateMap<?>> maps = new CopyOnWriteArrayList<>();

    /**
     * Names already taken, so {@link #register(String)} can refuse a duplicate rather than let two
     * features share one row in {@link #sizes()}.
     */
    private final Set<String> names = ConcurrentHashMap.newKeySet();

    /**
     * Creates a per-player map that is cleaned up on quit along with every other registered map.
     *
     * <p>The name must be unique across the whole plugin, and this method enforces that rather than
     * merely documenting it. Two features registering under one name is not a harmless collision:
     * {@link #sizes()} would report a single row carrying their combined entry count, so the one
     * diagnostic this type exists to provide — <em>which</em> map grew — would name a map that is
     * really two, and a leak in either would be indistinguishable from ordinary traffic in the
     * other. Failing at registration puts that in front of whoever added the second map, during
     * {@code onEnable}, instead of in front of whoever is chasing the leak months later.
     *
     * @param name diagnostic name, unique across every registered map; appears in {@link #sizes()}
     * @param <T>  the state held per player
     * @throws IllegalArgumentException if a map is already registered under {@code name}
     */
    public <T> PlayerStateMap<T> register(String name) {
        Objects.requireNonNull(name, "name");
        if (!names.add(name)) {
            throw new IllegalArgumentException("A per-player map is already registered as \"" + name
                    + "\". Names must be unique: PlayerStateRegistry.sizes() reports one row per "
                    + "name, so a shared name hides which feature's map is growing. Pick a name "
                    + "that identifies this feature.");
        }
        PlayerStateMap<T> map = new PlayerStateMap<>(name);
        maps.add(map);
        return map;
    }

    /**
     * Drops everything held for one player, across every registered map.
     *
     * <p>Call this from {@code PlayerQuitEvent} and from {@code PlayerKickEvent} equivalents. It is
     * idempotent and safe for a player who has no state.
     */
    public void forget(UUID player) {
        Objects.requireNonNull(player, "player");
        for (PlayerStateMap<?> map : maps) {
            map.remove(player);
        }
    }

    /** Drops everything for every player. For {@code onDisable}. */
    public void forgetAll() {
        for (PlayerStateMap<?> map : maps) {
            map.clear();
        }
    }

    /**
     * Registered map names against their current entry counts, for leak diagnostics.
     *
     * <p>One row per map, and {@link #register(String)} guarantees one map per name, so a row is
     * always attributable to exactly one feature.
     */
    public Map<String, Integer> sizes() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PlayerStateMap<?> map : maps) {
            counts.put(map.name(), map.size());
        }
        return Map.copyOf(counts);
    }

    /** Every registered map, in registration order. */
    public List<PlayerStateMap<?>> registered() {
        return List.copyOf(maps);
    }
}
