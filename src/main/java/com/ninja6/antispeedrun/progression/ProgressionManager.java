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
     * one belongs with the rest of the messages work rather than here. {@code {MILESTONE}} is the
     * only placeholder.
     */
    public static final String UNLOCK_ANNOUNCEMENT =
            "<green>🔓 <bold>{MILESTONE}</bold> is now open!<reset> <gray>Type <gold>/progress<gray> "
                    + "to see what is next.";

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

    /** Guards the R-15 "no first-join recorded" warning so it is logged once per server run. */
    private final AtomicBoolean warnedMissingFirstPlayed = new AtomicBoolean();

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
        PlayerProgressionSnapshot held = cache.get(id, () -> capture(player, config));
        if (held.covers(mustCover)) {
            return held;
        }
        // A reload introduced a key this capture predates. Treating an unqueried key as unearned
        // would seal a gate against a player who had already cleared it, so re-capture instead.
        cache.invalidate(id);
        return cache.get(id, () -> capture(player, config));
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
        if (!ageKnown && warnedMissingFirstPlayed.compareAndSet(false, true)) {
            logger.log(Level.WARNING, "getFirstPlayed() returned no usable first-join time for {0}. "
                    + "require-account-age-days will be treated as satisfied for affected players; "
                    + "this usually means playerdata was wiped or migrated. See issue #33, finding R-15.",
                    player.getName());
        }

        return new PlayerProgressionSnapshot(queried, earned, unresolvable, playtimeHours,
                ageKnown ? ageDays : 0L, ageKnown, now);
    }

    // -------------------------------------------------------------------------------------------
    // Unlock announcements
    // -------------------------------------------------------------------------------------------

    /**
     * Records which milestones the player already satisfies, <em>without</em> announcing them.
     *
     * <p>Called on join. Without it the first advancement of a session would announce every gate
     * the player cleared weeks ago.
     */
    public void primeUnlocks(Player player, PluginConfig config) {
        announced.put(player.getUniqueId(), eligibleIds(player, config));
    }

    /**
     * Re-evaluates every dimension milestone and announces the ones that have just become
     * available.
     *
     * <p>Called from {@code PlayerAdvancementDoneEvent}, after the cache has been invalidated, so
     * the announcement lands in the same tick the advancement is earned.
     *
     * @return the milestones announced, in configured order; empty when nothing changed
     */
    public List<Milestone> announceNewUnlocks(Player player, PluginConfig config) {
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
        player.sendMessage(MiniMessage.miniMessage()
                .deserialize(UNLOCK_ANNOUNCEMENT.replace("{MILESTONE}", milestone.displayName())));
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
     * Drops every cached snapshot and every recorded announcement.
     *
     * <p>{@code /asr reload} (#40) must call this: a new configuration can require advancements the
     * live snapshots never queried, and can enable a gate whose unlock has never been announced.
     * Snapshots would recover on their own through {@link PlayerProgressionSnapshot#covers}, but
     * the announcement bookkeeping would not.
     */
    public void onConfigurationReloaded() {
        cache.invalidateAll();
        announced.clear();
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
