package com.ninja6.antispeedrun.progression;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Everything about one player that a milestone can be evaluated against, captured at one instant.
 *
 * <p>Immutable and self-contained: once captured it may be read from any thread, which is what
 * makes it cacheable at all. Capturing it is the only part of progression evaluation that must
 * run on the player's own region thread.
 *
 * <h2>Account age, and why it is really tenure — finding R-15</h2>
 *
 * {@code accountAgeDays} is derived from {@code Player#getFirstPlayed()}, which records the first
 * time the player joined <strong>this server</strong>. It has nothing to do with when the Mojang
 * account was created, so it does not do what an operator reading
 * {@code require-account-age-days: 7} would assume:
 *
 * <ul>
 *   <li>On a freshly created world every player is zero days old, so a non-zero requirement seals
 *       the dimension for the entire server for that many days, regardless of skill.</li>
 *   <li>It resets on a playerdata wipe or a world migration, re-sealing dimensions for players who
 *       had already cleared them.</li>
 *   <li>It is not an anti-alt measure. A returning speedrunner's alt account that joined a month
 *       ago satisfies it; a legitimate new player never can, until time passes.</li>
 * </ul>
 *
 * The shipped {@code config.yml} therefore sets {@code require-account-age-days: 0} on both gates,
 * making them advancement-driven. A non-zero value is well defined and is honoured exactly — but
 * what it means is <em>"has been a member of this server for N days"</em>, a tenure requirement.
 * Operators who want it should set it deliberately and only on an established world.
 *
 * <h2>The zero return</h2>
 *
 * {@code getFirstPlayed()} returns {@code 0} when the server holds no playerdata for the player.
 * That is <em>not</em> "joined at the Unix epoch" and it is <em>not</em> "zero days old"; naively
 * subtracting it yields an age of roughly 20,000 days, which would silently satisfy every
 * conceivable requirement. It is captured here as {@link #accountAgeKnown()} {@code == false} and
 * {@link MilestoneEvaluator} treats an unknown age as satisfying the requirement, for the same
 * reason an unresolvable advancement is treated as satisfied: this snapshot is only ever captured
 * for an <em>online</em> player, for whom a zero means the server failed to record the join rather
 * than that the player is new. There is no action a player can take to fix it, so failing closed
 * would lock them out permanently. The manager logs it instead.
 *
 * @param queriedAdvancements     every requirement key this capture looked up. A key outside this
 *                                set was never asked about, and the snapshot says nothing at all
 *                                about it — which is different from the player not having earned
 *                                it. {@link ProgressionManager} re-captures rather than guess
 * @param earnedAdvancements      queried keys the player has completed
 * @param unresolvableAdvancements queried keys that could not be resolved to an advancement at
 *                                all — a malformed key, or an advancement no datapack defines
 *                                because vanilla advancements are switched off. Distinct from
 *                                "not yet earned": no play can ever satisfy these
 * @param playtimeHours           {@code Statistic.PLAY_ONE_MINUTE} converted to hours
 * @param accountAgeDays          whole days since first join to this server; {@code 0} when
 *                                {@code accountAgeKnown} is {@code false}
 * @param accountAgeKnown         whether the server actually recorded a first-join time
 * @param capturedAtMillis        wall-clock capture time, for {@code /progress} and diagnostics
 */
public record PlayerProgressionSnapshot(
        Set<String> queriedAdvancements,
        Set<String> earnedAdvancements,
        Set<String> unresolvableAdvancements,
        double playtimeHours,
        long accountAgeDays,
        boolean accountAgeKnown,
        long capturedAtMillis) {

    /** Ticks per hour: 20 ticks a second, 3,600 seconds an hour. */
    public static final double TICKS_PER_HOUR = 20.0D * 3600.0D;

    /** Milliseconds per day. */
    public static final long MILLIS_PER_DAY = 1000L * 3600L * 24L;

    public PlayerProgressionSnapshot {
        queriedAdvancements = Set.copyOf(Objects.requireNonNull(queriedAdvancements, "queriedAdvancements"));
        earnedAdvancements = Set.copyOf(Objects.requireNonNull(earnedAdvancements, "earnedAdvancements"));
        unresolvableAdvancements =
                Set.copyOf(Objects.requireNonNull(unresolvableAdvancements, "unresolvableAdvancements"));
    }

    /**
     * Converts the {@code PLAY_ONE_MINUTE} statistic to hours.
     *
     * <p>The statistic is misnamed: it counts <em>ticks</em>, not minutes, and has done since it
     * was introduced. Dividing by anything else silently produces a requirement 60 or 1,200 times
     * off, which no test that stubs the statistic would catch.
     *
     * @param playOneMinuteTicks the raw statistic value
     */
    public static double playtimeHours(int playOneMinuteTicks) {
        if (playOneMinuteTicks <= 0) {
            return 0.0D;
        }
        return playOneMinuteTicks / TICKS_PER_HOUR;
    }

    /**
     * Whole days between a {@code getFirstPlayed()} value and now, or {@code -1} when the value is
     * unusable.
     *
     * @param firstPlayedMillis {@code Player#getFirstPlayed()}; {@code 0} means "not recorded"
     * @param nowMillis         {@code System.currentTimeMillis()}
     * @return days elapsed, or {@code -1} if {@code firstPlayedMillis} is zero, negative, or in
     *         the future — the last of which happens when a server's clock is corrected backwards
     */
    public static long accountAgeDays(long firstPlayedMillis, long nowMillis) {
        if (firstPlayedMillis <= 0L || firstPlayedMillis > nowMillis) {
            return -1L;
        }
        return (nowMillis - firstPlayedMillis) / MILLIS_PER_DAY;
    }

    /**
     * Whether every one of {@code keys} was looked up by this capture.
     *
     * <p>False after {@code /asr reload} introduces an advancement key the live snapshots predate.
     * {@link ProgressionManager} re-captures instead of treating an unqueried key as unearned,
     * which would seal a gate against a player who had already cleared it.
     */
    public boolean covers(Iterable<String> keys) {
        for (String key : keys) {
            if (!queriedAdvancements.contains(key)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A copy of this snapshot in which any of {@code keys} it does not already cover is recorded as
     * {@linkplain #unresolvableAdvancements() unresolvable}.
     *
     * <p>The escape hatch for a requirement that no capture against the live configuration can ever
     * answer — a {@link MilestoneRequirement} built from a configuration snapshot other than the one
     * the caller passed in, which the evaluation API cannot structurally prevent because the two
     * arrive as independent parameters. Without this, an unqueried key is absent from both
     * {@code earnedAdvancements} and {@code unresolvableAdvancements}, and
     * {@link MilestoneEvaluator} reads it as simply unearned — locking the player out of a gate they
     * may well have cleared. That is fail-<em>closed</em>, and it is the one case in this package
     * that would not follow the fail-open policy every other unevaluable requirement follows.
     *
     * <p>Returns {@code this} when nothing needs waiving, so the common path allocates nothing. The
     * copy is deliberately not cached: it is an answer for one call, not a fact about the player.
     */
    public PlayerProgressionSnapshot withWaived(Iterable<String> keys) {
        Set<String> uncovered = new LinkedHashSet<>();
        for (String key : keys) {
            if (!queriedAdvancements.contains(key)) {
                uncovered.add(key);
            }
        }
        if (uncovered.isEmpty()) {
            return this;
        }
        Set<String> widenedQueried = new LinkedHashSet<>(queriedAdvancements);
        widenedQueried.addAll(uncovered);
        Set<String> widenedUnresolvable = new LinkedHashSet<>(unresolvableAdvancements);
        widenedUnresolvable.addAll(uncovered);
        return new PlayerProgressionSnapshot(widenedQueried, earnedAdvancements, widenedUnresolvable,
                playtimeHours, accountAgeDays, accountAgeKnown, capturedAtMillis);
    }

    /** The subset of {@code keys} this capture never looked up. */
    public Set<String> uncovered(Iterable<String> keys) {
        Set<String> uncovered = new LinkedHashSet<>();
        for (String key : keys) {
            if (!queriedAdvancements.contains(key)) {
                uncovered.add(key);
            }
        }
        return Set.copyOf(uncovered);
    }

    /** Whether {@code key} is a requirement this snapshot could not resolve to any advancement. */
    public boolean isUnresolvable(String key) {
        return unresolvableAdvancements.contains(key);
    }

    /** Whether the player has completed {@code key}. Unresolvable keys are never earned. */
    public boolean hasEarned(String key) {
        return earnedAdvancements.contains(key);
    }
}
