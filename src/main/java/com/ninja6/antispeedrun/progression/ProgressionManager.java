package com.ninja6.antispeedrun.progression;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import com.ninja6.antispeedrun.config.PluginConfig;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/**
 * Answers the one question every gate in this plugin asks: <em>has this player earned it yet?</em>
 *
 * <p>Dimension gates (#34, #35), item pickup gating (#11), trim gating, villager trade gating and
 * the {@code /progress} card all route through here, so this class is on the hottest paths in the
 * plugin. It is deliberately thin: the comparison itself is {@link MilestoneEvaluator}, the caching
 * is {@link ProgressionCache}, and the server reads are {@link AdvancementLookup} — leaving this
 * class responsible for capture, for wiring those three together, and for announcing unlocks.
 *
 * <h2>Threading</h2>
 *
 * Every method taking a {@link Player} must be called from a context that owns that player: an
 * event handler for them, or a task on their {@code EntityScheduler}. Capturing a snapshot reads
 * {@code Player#getStatistic} and {@code Player#getAdvancementProgress}, which are region-owned.
 * Methods taking only a {@link UUID} are safe from anywhere.
 *
 * <h2>Insertion after quit — finding R-08</h2>
 *
 * Quit cleanup is {@link PlayerStateRegistry#forget(UUID)} on {@code PlayerQuitEvent}, and it runs
 * once. Any per-player entry written <em>after</em> it therefore has nothing left to remove it and
 * survives to {@code onDisable}. Every method here that writes to a per-player map — {@link
 * #evaluate}, {@link #primeUnlocks} and {@link #announceNewUnlocks} — consequently checks
 * {@code Player#isOnline()} first and declines to store rather than trusting its caller. The check
 * costs a field read on paths that already do a map lookup, and it is what makes {@link UnlockWatch}
 * safe: a scheduled task is the one kind of caller that can outlive the player it holds, and the
 * idle-reminder work in #4 will add more of them.
 *
 * <p>The {@link PluginConfig} snapshot is a parameter, never a field. Callers read
 * {@code plugin.configuration()} once at the top of their handler and pass that same local in, per
 * the contract in {@code com.ninja6.antispeedrun.config}; this class must not re-read it and
 * straddle a reload.
 */
public final class ProgressionManager {

    /**
     * Announcement sent the moment a gate's prerequisites complete.
     *
     * <p>Hard-coded rather than configured: {@code config.yml} has no key for it today, and adding
     * one belongs with the rest of the messages work rather than here.
     *
     * <p>{@link #MILESTONE_PLACEHOLDER} is the only placeholder, and it is resolved <em>by</em>
     * MiniMessage rather than substituted into the template before parsing. The difference is not
     * cosmetic: a string replace would let the milestone's display name be parsed as markup, and
     * while both dimension names are compile-time constants today, {@code announceNewUnlocks} is
     * meant to grow to item tiers, whose display names come from {@code config.yml}. A tag resolver
     * makes an operator's text literal text, permanently, rather than leaving that guarantee
     * resting on where the string happens to come from.
     */
    public static final String UNLOCK_ANNOUNCEMENT =
            "<green>🔓 <bold><milestone></bold> is now open!<reset> <gray>Type <gold>/progress<gray> "
                    + "to see what is next.";

    /** The tag {@link #UNLOCK_ANNOUNCEMENT} carries for the milestone's display name. */
    public static final String MILESTONE_PLACEHOLDER = "milestone";

    /**
     * How many distinct players must hit the missing-first-join condition before the log says so
     * again, in terms that distinguish a server-wide wipe from one odd playerdata file.
     */
    static final int MISSING_FIRST_PLAYED_ESCALATION = 10;

    private final Logger logger;
    private final AdvancementLookup advancements;
    private final PlayerStateRegistry state;
    private final ProgressionCache cache;
    private final Supplier<Long> clock;

    /**
     * Milestone ids each player has already been told about, so an unlock is announced once and not
     * on every subsequent advancement. Primed silently on join; #57 will persist it, at which point
     * a player who unlocks the Nether and rejoins will still not be re-congratulated.
     */
    private final PlayerStateMap<Set<String>> announced;

    /**
     * Players already counted against the R-15 "no first-join recorded" warning.
     *
     * <p>A single {@code AtomicBoolean} here logged once per server run and named whichever player
     * happened to be captured first, which is the least useful thing to know: the realistic cause
     * is a playerdata wipe or a world migration, which affects everyone, and an operator reading
     * one name cannot tell that from one corrupt file. Counting distinct players separates the two.
     *
     * <p>Bounded at {@link #MISSING_FIRST_PLAYED_ESCALATION} entries: once the escalation line has
     * been logged nothing further is reported, so the set is cleared and never repopulated. It is
     * therefore not the unbounded per-player collection finding R-08 is about.
     */
    private final Set<UUID> missingFirstPlayed = ConcurrentHashMap.newKeySet();

    /** Whether the escalated "this looks server-wide" line has already been logged. */
    private final AtomicBoolean escalatedMissingFirstPlayed = new AtomicBoolean();

    /**
     * Requirement keys already reported as uncapturable. Bounded by the number of distinct keys any
     * caller passes, so it needs no eviction.
     */
    private final Set<String> uncoveredReported = ConcurrentHashMap.newKeySet();

    /**
     * @param logger      plugin logger
     * @param advancements how configured advancement keys are resolved
     * @param state       registry every per-player map in this class is registered with, so quit
     *                    cleanup covers them without this class owning a quit hook
     * @param timeToLive  how long a captured snapshot stays usable; see {@link ProgressionCache}
     * @param clock       wall-clock milliseconds; {@code System::currentTimeMillis} in production
     */
    public ProgressionManager(Logger logger, AdvancementLookup advancements, PlayerStateRegistry state,
                              Duration timeToLive, Supplier<Long> clock) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.advancements = Objects.requireNonNull(advancements, "advancements");
        this.state = Objects.requireNonNull(state, "state");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cache = new ProgressionCache(state, timeToLive, clock);
        this.announced = state.register("progression-announced-milestones");
    }

    /** A manager on the default cache time-to-live and the system clock. */
    public ProgressionManager(Logger logger, AdvancementLookup advancements, PlayerStateRegistry state) {
        this(logger, advancements, state, ProgressionCache.DEFAULT_TIME_TO_LIVE, System::currentTimeMillis);
    }

    // -------------------------------------------------------------------------------------------
    // Evaluation
    // -------------------------------------------------------------------------------------------

    /**
     * Whether {@code player} satisfies {@code requirement}.
     *
     * <p>The convenience form of {@link #evaluate}; use that one when the caller needs to explain
     * what is outstanding.
     */
    public boolean isEligible(Player player, PluginConfig config, MilestoneRequirement requirement) {
        return evaluate(player, config, requirement).eligible();
    }

    /**
     * Evaluates {@code requirement} against {@code player}, capturing a snapshot only if the cached
     * one is missing, expired, or predates a requirement key it never looked up.
     *
     * @param player      the player; must be owned by the calling thread's region
     * @param config      the configuration snapshot the caller is already holding
     * @param requirement what to evaluate. {@link MilestoneRequirement#of} builds one from a
     *                    {@code DimensionGate} or an {@code ItemTier}
     */
    public EligibilityResult evaluate(Player player, PluginConfig config, MilestoneRequirement requirement) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(requirement, "requirement");

        if (requirement.isEmpty()) {
            return EligibilityResult.pass();
        }
        return MilestoneEvaluator.evaluate(requirement, snapshot(player, config, requirement.advancements()));
    }

    /** Evaluates a whole milestone. */
    public EligibilityResult evaluate(Player player, PluginConfig config, Milestone milestone) {
        return evaluate(player, config, Objects.requireNonNull(milestone, "milestone").requirement());
    }

    /**
     * The player's cached progression facts, captured if necessary.
     *
     * <p>For {@code /progress} (#3) and {@code /asr inspect}, which want the whole picture rather
     * than one verdict.
     */
    public PlayerProgressionSnapshot snapshot(Player player, PluginConfig config) {
        return snapshot(player, config, List.of());
    }

    private PlayerProgressionSnapshot snapshot(Player player, PluginConfig config,
                                               Iterable<String> mustCover) {
        UUID id = player.getUniqueId();
        if (!player.isOnline()) {
            // PlayerQuitEvent has already run, so PlayerStateRegistry.forget has already cleared
            // this player's row and nothing will clear it again: an entry installed now lives until
            // onDisable. Answer the caller from a throwaway capture instead of caching it. See the
            // insertion-point rule in the class javadoc.
            return waive(player, capture(player, config), mustCover);
        }
        PlayerProgressionSnapshot held = cache.get(id, () -> capture(player, config));

        Set<String> uncovered = held.uncovered(mustCover);
        if (uncovered.isEmpty()) {
            return held;
        }

        // Ask whether a re-capture could even help before paying for one. capture() queries exactly
        // allRequiredAdvancements(config), so a key outside that set will be missing from the second
        // snapshot too -- and re-capturing regardless would mean two Bukkit captures on every call
        // for the rest of the session, permanently, with nothing in the log to say why.
        if (Milestone.allRequiredAdvancements(config).containsAll(uncovered)) {
            // A reload introduced a key this capture predates. One re-capture answers it.
            cache.invalidate(id);
            held = cache.get(id, () -> capture(player, config));
            uncovered = held.uncovered(mustCover);
            if (uncovered.isEmpty()) {
                return held;
            }
        }

        // Nothing this configuration can capture will ever answer these keys -- the requirement was
        // built from a different configuration snapshot than the one passed in. Waive them, exactly
        // as an advancement the server does not define is waived, so the player is not locked out of
        // a gate by a mismatch they cannot see and cannot act on.
        reportUncovered(player, uncovered);
        return held.withWaived(uncovered);
    }

    /** The waive-and-report tail of {@link #snapshot}, shared with the uncached offline path. */
    private PlayerProgressionSnapshot waive(Player player, PlayerProgressionSnapshot held,
                                            Iterable<String> mustCover) {
        Set<String> uncovered = held.uncovered(mustCover);
        if (uncovered.isEmpty()) {
            return held;
        }
        reportUncovered(player, uncovered);
        return held.withWaived(uncovered);
    }

    /**
     * Logs an uncovered requirement key once per key, not once per call: this condition persists
     * for as long as the mismatched pairing does, and a per-call line would bury the log at pickup
     * rates.
     */
    private void reportUncovered(Player player, Set<String> uncovered) {
        for (String key : uncovered) {
            if (uncoveredReported.add(key)) {
                logger.log(Level.WARNING, "Requirement \"{0}\" was evaluated for {1} against a "
                        + "configuration snapshot that does not declare it, so no capture can answer "
                        + "it. Treating it as unresolvable rather than unmet. This means a "
                        + "MilestoneRequirement and a PluginConfig from two different snapshots were "
                        + "passed to ProgressionManager; read plugin.configuration() once per event "
                        + "and derive the requirement from that same local.",
                        new Object[] {key, player.getName()});
            }
        }
    }

    /**
     * Reads the player's progression facts from the server. The only region-thread-bound work in
     * this class.
     */
    private PlayerProgressionSnapshot capture(Player player, PluginConfig config) {
        Set<String> queried = Milestone.allRequiredAdvancements(config);
        Set<String> earned = new LinkedHashSet<>();
        Set<String> unresolvable = new LinkedHashSet<>();
        for (String key : queried) {
            switch (advancements.state(player, key)) {
                case EARNED -> earned.add(key);
                case UNRESOLVABLE -> unresolvable.add(key);
                case NOT_EARNED -> { /* absent from both sets */ }
            }
        }

        double playtimeHours =
                PlayerProgressionSnapshot.playtimeHours(player.getStatistic(Statistic.PLAY_ONE_MINUTE));

        long now = clock.get();
        long ageDays = PlayerProgressionSnapshot.accountAgeDays(player.getFirstPlayed(), now);
        boolean ageKnown = ageDays >= 0L;
        if (!ageKnown) {
            reportMissingFirstPlayed(player);
        }

        return new PlayerProgressionSnapshot(queried, earned, unresolvable, playtimeHours,
                ageKnown ? ageDays : 0L, ageKnown, now);
    }

    /**
     * Logs the R-15 condition at most twice per server run: once naming the first affected player,
     * and once more if it turns out not to be about one player at all.
     *
     * <p>Deduplicated per player rather than per capture, so the cache's one-minute time-to-live
     * cannot escalate a single affected player into a false server-wide report simply by
     * re-capturing them ten times over ten minutes.
     */
    private void reportMissingFirstPlayed(Player player) {
        if (escalatedMissingFirstPlayed.get() || !missingFirstPlayed.add(player.getUniqueId())) {
            return;
        }

        int distinct = missingFirstPlayed.size();
        if (distinct == 1) {
            logger.log(Level.WARNING, "getFirstPlayed() returned no usable first-join time for {0}. "
                    + "require-account-age-days will be treated as satisfied for affected players; "
                    + "this usually means playerdata was wiped or migrated. See issue #33, finding R-15.",
                    player.getName());
            return;
        }
        if (distinct >= MISSING_FIRST_PLAYED_ESCALATION
                && escalatedMissingFirstPlayed.compareAndSet(false, true)) {
            logger.log(Level.WARNING, "getFirstPlayed() has now returned no usable first-join time "
                    + "for {0} different players, so this is not one bad playerdata file: the "
                    + "server''s playerdata was most likely wiped or migrated wholesale, and "
                    + "require-account-age-days is being treated as satisfied server-wide. Set it "
                    + "to 0 deliberately, or restore playerdata. Finding R-15; this is the last "
                    + "time it will be reported this run.", distinct);
            // Nothing more will be logged, so the per-player set has no further job. Dropping it
            // keeps this from becoming the unbounded per-player collection R-08 warns about.
            missingFirstPlayed.clear();
        }
    }

    // -------------------------------------------------------------------------------------------
    // Unlock announcements
    // -------------------------------------------------------------------------------------------

    /**
     * Records which milestones the player already satisfies, <em>without</em> announcing them.
     *
     * <p>Called on join, on {@code /asr reload}, and for players already online when the plugin
     * enables. Without it the next advancement earned would announce every gate the player cleared
     * weeks ago.
     */
    public void primeUnlocks(Player player, PluginConfig config) {
        if (!player.isOnline()) {
            return;
        }
        announced.put(player.getUniqueId(), eligibleIds(player, config));
    }

    /**
     * Re-evaluates every dimension milestone and announces the ones that have just become
     * available.
     *
     * <p>Called from {@code PlayerAdvancementDoneEvent}, after the cache has been invalidated, so
     * the announcement lands in the same tick the advancement is earned — and from
     * {@link UnlockWatch}, which covers the gate whose last outstanding requirement is
     * {@code require-playtime-hours} or {@code require-account-age-days} and so has no event to
     * fire on. Between the two, every requirement kind announces. There is still no global sweeper:
     * the watch is a per-player {@code EntityScheduler} task, armed only while that player has such
     * a requirement outstanding, for the reason given in {@link ProgressionCache}.
     *
     * @return the milestones announced, in configured order; empty when nothing changed
     */
    public List<Milestone> announceNewUnlocks(Player player, PluginConfig config) {
        if (!player.isOnline()) {
            // Nobody to tell, and the bookkeeping row below would be installed after quit cleanup
            // has run -- see the insertion-point rule in the class javadoc.
            return List.of();
        }
        UUID id = player.getUniqueId();
        Set<String> previous = announced.getOrDefault(id, Set.of());

        List<Milestone> newlyUnlocked = new ArrayList<>();
        Set<String> nowEligible = ConcurrentHashMap.newKeySet();
        for (Milestone milestone : Milestone.dimensionGates(config)) {
            if (!evaluate(player, config, milestone).eligible()) {
                continue;
            }
            nowEligible.add(milestone.id());
            if (!previous.contains(milestone.id())) {
                newlyUnlocked.add(milestone);
            }
        }
        announced.put(id, nowEligible);

        for (Milestone milestone : newlyUnlocked) {
            announce(player, milestone);
        }
        return List.copyOf(newlyUnlocked);
    }

    private Set<String> eligibleIds(Player player, PluginConfig config) {
        Set<String> ids = ConcurrentHashMap.newKeySet();
        for (Milestone milestone : Milestone.dimensionGates(config)) {
            if (evaluate(player, config, milestone).eligible()) {
                ids.add(milestone.id());
            }
        }
        return ids;
    }

    private void announce(Player player, Milestone milestone) {
        player.sendMessage(MiniMessage.miniMessage().deserialize(UNLOCK_ANNOUNCEMENT,
                Placeholder.unparsed(MILESTONE_PLACEHOLDER, milestone.displayName())));
    }

    // -------------------------------------------------------------------------------------------
    // Time-based unlocks
    // -------------------------------------------------------------------------------------------

    /**
     * Whether this player has a gate that will open on the passage of time alone.
     *
     * <p>The arming condition for {@link UnlockWatch}, and deliberately narrow: a gate that is also
     * waiting on an advancement does not qualify, because {@code PlayerAdvancementDoneEvent} will
     * fire when that advancement lands and re-ask this question then. Only a gate down to its last
     * outstanding requirement, and that requirement a duration, has nothing left to fire on.
     *
     * @param player the player; must be owned by the calling thread's region, since this evaluates
     * @param config the configuration snapshot the caller is already holding
     */
    public boolean awaitingTimeBasedUnlock(Player player, PluginConfig config) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(config, "config");
        if (!player.isOnline()) {
            return false;
        }

        Set<String> alreadyTold = announced.getOrDefault(player.getUniqueId(), Set.of());
        for (Milestone milestone : Milestone.dimensionGates(config)) {
            if (alreadyTold.contains(milestone.id())) {
                continue;
            }
            if (evaluate(player, config, milestone).outstandingOnTimeAlone()) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------------------------
    // Cache and state lifecycle
    // -------------------------------------------------------------------------------------------

    /**
     * Drops the player's cached snapshot. Call whenever their advancement set may have changed
     * outside {@code PlayerAdvancementDoneEvent} — {@code /advancement grant}, for instance, does
     * fire the event, but {@code /asr unlock} (#40) will not.
     */
    public void invalidate(UUID player) {
        cache.invalidate(player);
    }

    /**
     * Drops everything held for a player. Call from {@code PlayerQuitEvent}.
     *
     * <p>Equivalent to {@link PlayerStateRegistry#forget(UUID)} and named here so a caller holding
     * only the manager can satisfy finding R-08 without reaching for the registry.
     */
    public void forget(UUID player) {
        state.forget(player);
    }

    /**
     * Drops every cached snapshot after a configuration swap.
     *
     * <p>{@code /asr reload} (#40) must call this: a new configuration can require advancements the
     * live snapshots never queried, and the captures are keyed to the configuration that produced
     * them.
     *
     * <p>It deliberately does <strong>not</strong> clear {@link #announced}. Clearing it would leave
     * every online player with an empty "already told" set, so the next advancement any of them
     * earned would re-announce every gate they cleared weeks ago — the precise behaviour
     * {@link #primeUnlocks} exists to prevent, inflicted on the whole server by a routine reload.
     * Re-priming is the correct repair and it reads player state, so it must run on each player's
     * own region thread; {@code AntiSpeedrunPlugin} arms it through their {@code EntityScheduler}
     * after calling this.
     */
    public void onConfigurationReloaded() {
        cache.invalidateAll();
    }

    /** The per-player state registry, so later features register their maps with the same one. */
    public PlayerStateRegistry state() {
        return state;
    }

    /** The snapshot cache, for diagnostics and for {@code /asr inspect}. */
    public ProgressionCache cache() {
        return cache;
    }

    /** The player's cached snapshot if one is live, without capturing. Diagnostics only. */
    public Optional<PlayerProgressionSnapshot> cached(UUID player) {
        return cache.peek(player);
    }
}
