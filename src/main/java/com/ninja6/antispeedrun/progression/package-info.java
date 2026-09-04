/**
 * Progression evaluation: does a player meet a milestone's requirements yet?
 *
 * <h2>What lives here</h2>
 *
 * <ul>
 *   <li>{@link com.ninja6.antispeedrun.progression.MilestoneRequirement} — the three requirement
 *       kinds ({@code require-advancements}, {@code require-playtime-hours},
 *       {@code require-account-age-days}) lifted out of whichever configuration record declared
 *       them, so a dimension gate and an item tier are evaluated by the same code.</li>
 *   <li>{@link com.ninja6.antispeedrun.progression.PlayerProgressionSnapshot} — the player-side
 *       facts, captured once and then immutable.</li>
 *   <li>{@link com.ninja6.antispeedrun.progression.MilestoneEvaluator} — the pure comparison of
 *       the two. No Bukkit types, so it is exercisable off-server.</li>
 *   <li>{@link com.ninja6.antispeedrun.progression.ProgressionManager} — the server-facing
 *       service that captures snapshots, caches them, and announces unlocks.</li>
 *   <li>{@link com.ninja6.antispeedrun.progression.PlayerStateRegistry} — the one place per-player
 *       state is allowed to live, so that quit cleanup is not re-implemented per feature.</li>
 * </ul>
 *
 * <h2>Threading contract</h2>
 *
 * The rules in {@code com.ninja6.antispeedrun.config} bind here too. Three additional rules are
 * specific to this package:
 *
 * <ol>
 *   <li><strong>A snapshot is captured on the thread that owns the player.</strong>
 *       {@code Player#getStatistic} and {@code Player#getAdvancementProgress} read data owned by
 *       the player's Folia region; reading them from anywhere else is a data race. Every entry
 *       point that captures a snapshot therefore takes a {@code Player} and must be called from
 *       an event handler for that player, or from a task scheduled on that player's
 *       {@code EntityScheduler}.</li>
 *   <li><strong>Everything else here is thread-safe.</strong> The cache and every per-player map
 *       is a {@code ConcurrentHashMap}; a captured snapshot and an
 *       {@link com.ninja6.antispeedrun.progression.EligibilityResult} are immutable records and
 *       may be handed to any thread.</li>
 *   <li><strong>Nothing in this package schedules a repeating task.</strong> Cache staleness is
 *       resolved lazily on read against a time-to-live, because the only work a background
 *       sweeper could legally do is eviction — it cannot re-read a player's statistics from off
 *       that player's region thread. See
 *       {@link com.ninja6.antispeedrun.progression.ProgressionCache} for the full argument.</li>
 * </ol>
 *
 * <h2>Cache invalidation</h2>
 *
 * Two triggers, matching the two kinds of requirement that can change during a session:
 *
 * <ul>
 *   <li><em>Advancements</em> change at a known instant, so {@code PlayerAdvancementDoneEvent}
 *       invalidates the entry precisely. The event fires on the player's own region thread, which
 *       is also where the replacement snapshot is captured and where any unlock announcement is
 *       sent.</li>
 *   <li><em>Playtime and account age</em> change continuously, so they are covered by a coarse
 *       time-to-live instead.</li>
 * </ul>
 */
package com.ninja6.antispeedrun.progression;
