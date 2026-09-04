package com.ninja6.antispeedrun.progression;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
     * Creates a per-player map that is cleaned up on quit along with every other registered map.
     *
     * @param name diagnostic name, unique per feature; appears in {@link #sizes()}
     * @param <T>  the state held per player
     */
    public <T> PlayerStateMap<T> register(String name) {
        Objects.requireNonNull(name, "name");
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

    /** Registered map names against their current entry counts, for leak diagnostics. */
    public Map<String, Integer> sizes() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PlayerStateMap<?> map : maps) {
            counts.merge(map.name(), map.size(), Integer::sum);
        }
        return Map.copyOf(counts);
    }

    /** Every registered map, in registration order. */
    public List<PlayerStateMap<?>> registered() {
        return List.copyOf(maps);
    }
}
