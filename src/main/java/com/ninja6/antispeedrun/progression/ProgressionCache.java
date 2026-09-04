package com.ninja6.antispeedrun.progression;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Caches one {@link PlayerProgressionSnapshot} per player, so the hot paths stop re-querying the
 * server.
 *
 * <p>Finding R-18: {@code ProgressionManager} is consulted on every pickup attempt and every
 * container click. Uncached, each of those resolves a {@code NamespacedKey}, calls
 * {@code Bukkit.getAdvancement}, walks an {@code AdvancementProgress} per required advancement, and
 * reads a statistic — tens of times a second, per player, for an answer that changes a handful of
 * times a session.
 *
 * <h2>Invalidation</h2>
 *
 * <ul>
 *   <li><strong>Advancements: exactly.</strong> {@code PlayerAdvancementDoneEvent} calls
 *       {@link #invalidate(UUID)}. That is the only way a player's advancement set changes during a
 *       session, so an entry is rebuilt at most once per advancement earned — which is the
 *       "read at most once per player per advancement change" criterion, met by construction rather
 *       than by timing.</li>
 *   <li><strong>Playtime and account age: coarsely.</strong> These advance continuously and have no
 *       event, so an entry simply expires. The interval is a deliberate trade: it bounds how late a
 *       playtime-driven unlock can be noticed, and it is the reason a stale entry cannot outlive a
 *       reload or a clock correction indefinitely. {@link #DEFAULT_TIME_TO_LIVE} is one minute —
 *       requirements are configured in <em>hours</em>, so a minute is already an order of magnitude
 *       finer than the finest requirement anyone can express, while still collapsing roughly
 *       1,200 hot-path evaluations per player per minute into one capture.</li>
 * </ul>
 *
 * <h2>Why expiry is lazy and there is no sweeper task</h2>
 *
 * A repeating task could only <em>evict</em>: rebuilding an entry means reading
 * {@code Player#getStatistic} and {@code Player#getAdvancementProgress}, which on Folia may only
 * happen on the region thread that owns the player. A global sweeper doing that would be the exact
 * cross-region read that finding C-07 is about. Eviction alone buys nothing a
 * time-to-live check on read does not already give, and it costs a scheduler the plugin would then
 * have to shut down correctly. Unbounded growth is prevented by {@link #forget(UUID)} on quit, not
 * by a sweeper.
 *
 * <h2>Threading</h2>
 *
 * Thread-safe. Entries live in a {@link PlayerStateMap}, so a quitting player's entry is dropped
 * along with every other per-player map. Capture happens <em>outside</em> the map, and the result is
 * installed with {@code putIfAbsent}: no server read ever runs while a map bin is held. Two threads
 * capturing for the same player would waste one capture and agree on the winner, and in practice
 * they never race at all, because a given player's snapshot is only ever captured on that player's
 * own region thread.
 */
public final class ProgressionCache {

    /** One minute. See the class javadoc for the reasoning. */
    public static final Duration DEFAULT_TIME_TO_LIVE = Duration.ofMinutes(1L);

    private final PlayerStateMap<PlayerProgressionSnapshot> entries;
    private final long timeToLiveMillis;
    private final Supplier<Long> clock;

    /**
     * @param registry     registry the entry map is registered with, so quit cleanup is automatic
     * @param timeToLive   how long a captured snapshot stays usable; must be positive
     * @param clock        source of wall-clock milliseconds; {@code System::currentTimeMillis} in
     *                     production, a controllable value in tests
     */
    public ProgressionCache(PlayerStateRegistry registry, Duration timeToLive, Supplier<Long> clock) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(timeToLive, "timeToLive");
        if (timeToLive.isNegative() || timeToLive.isZero()) {
            throw new IllegalArgumentException("timeToLive must be positive, was " + timeToLive);
        }
        this.entries = registry.register("progression-snapshots");
        this.timeToLiveMillis = timeToLive.toMillis();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A cache on {@link #DEFAULT_TIME_TO_LIVE} and the system clock. */
    public ProgressionCache(PlayerStateRegistry registry) {
        this(registry, DEFAULT_TIME_TO_LIVE, System::currentTimeMillis);
    }

    /**
     * The player's snapshot, captured through {@code capture} only if none is held or the held one
     * has expired.
     *
     * <p>{@code capture} runs on the calling thread and must therefore be called from a context
     * that owns the player — see the package threading contract.
     *
     * @param player  the player
     * @param capture captures a fresh snapshot; must not return {@code null} and must not re-enter
     *                this cache
     */
    public PlayerProgressionSnapshot get(UUID player, Supplier<PlayerProgressionSnapshot> capture) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(capture, "capture");

        PlayerProgressionSnapshot held = entries.get(player).orElse(null);
        if (held != null && !isExpired(held)) {
            return held;
        }

        // Capture OUTSIDE any map lock. Routing this through computeIfAbsent would run
        // Bukkit.getAdvancement, getAdvancementProgress, getStatistic and a possible log write while
        // a ConcurrentHashMap bin is held, serialising two region threads whose players merely hash
        // together -- an odd thing to do in the class that owns the concurrency contract. The only
        // cost of capturing first is a discarded duplicate in a race that cannot arise in practice:
        // a given player's snapshot is only ever captured on that player's own region thread.
        PlayerProgressionSnapshot fresh =
                Objects.requireNonNull(capture.get(), "capture returned no snapshot");

        PlayerProgressionSnapshot installed = entries.putIfAbsent(player, fresh).orElse(null);
        if (installed == null) {
            return fresh;
        }
        if (isExpired(installed)) {
            // Either the expired entry read above, or another one that expired meanwhile.
            entries.put(player, fresh);
            return fresh;
        }
        // Another thread captured concurrently and its entry is live; prefer it, so callers on both
        // threads observe the same instance and the duplicate is simply dropped.
        return installed;
    }

    /** The held snapshot if one is present and unexpired; never captures. For {@code /progress}. */
    public Optional<PlayerProgressionSnapshot> peek(UUID player) {
        return entries.get(Objects.requireNonNull(player, "player")).filter(held -> !isExpired(held));
    }

    /**
     * Drops the player's entry so the next {@link #get} re-captures.
     *
     * <p>Called from {@code PlayerAdvancementDoneEvent} and from {@code /asr reload}, since a new
     * configuration may require advancements the previous snapshot never looked up.
     */
    public void invalidate(UUID player) {
        entries.remove(Objects.requireNonNull(player, "player"));
    }

    /** Drops every entry. For {@code /asr reload} and {@code onDisable}. */
    public void invalidateAll() {
        entries.clear();
    }

    /**
     * Drops the player's entry on quit.
     *
     * <p>Redundant with {@link PlayerStateRegistry#forget(UUID)}, which already covers this map;
     * kept as an explicit name so a caller holding only the cache can still do the right thing.
     */
    public void forget(UUID player) {
        invalidate(player);
    }

    /** How many players currently have a cached snapshot. Leak assertions and diagnostics. */
    public int size() {
        return entries.size();
    }

    private boolean isExpired(PlayerProgressionSnapshot held) {
        long age = clock.get() - held.capturedAtMillis();
        // A negative age means the wall clock moved backwards; treat that as expired rather than
        // as an entry that is valid until the clock catches up.
        return age < 0L || age >= timeToLiveMillis;
    }
}
