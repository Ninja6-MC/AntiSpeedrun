package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.Optional;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PluginConfig.DimensionGate;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.MilestoneRequirement;
import com.ninja6.antispeedrun.storage.DimensionUnlock;

/**
 * Every rule the dimension gate applies, with no Bukkit type anywhere in the signature.
 *
 * <p>The shape {@link com.ninja6.antispeedrun.progression.MilestoneEvaluator} set: the decision is
 * a pure function, {@link ProgressionGateListener} is the wiring that reads the event, calls in
 * here and acts on the answer. That split is what makes the gate testable without booting Paper —
 * and the gate is the one thing in this plugin that stops a player, so it is the last place a test
 * should need a live server.
 *
 * <h2>No thresholds are written down here — audit finding R-02</h2>
 *
 * The acceptance criteria on #34 quote "playtime &lt; 2h" and "&lt; 20h, age &lt; 7d". Specification
 * §4 and the shipped {@code config.yml} set {@code require-playtime-hours} and
 * {@code require-account-age-days} to {@code 0} on both gates, commented "Default 0
 * (Advancement-driven)". Nothing in this class knows a number: it reads
 * {@code dimension-gates.<dimension>.require-*} through {@link MilestoneRequirement#of}, so a gate
 * behaves as the operator configured it and a test written against the shipped configuration —
 * every threshold at zero, advancements alone — is testing what actually ships.
 */
public final class DimensionGateRules {

    private DimensionGateRules() {
    }

    /**
     * Which configured gate, if any, a transit from {@code from} to {@code to} has to clear.
     *
     * <p>Three things make this return empty, and each one matters more than it looks:
     *
     * <ul>
     *   <li><strong>{@code from == to}.</strong> An intra-dimensional teleport is not a dimension
     *       change: {@code /spawn}, a random-teleport plugin, a chorus fruit, an End gateway hop
     *       within the End. #6 names this explicitly, and getting it wrong would have this plugin
     *       cancelling teleports for every other plugin on the server.</li>
     *   <li><strong>{@code to} is not gatable.</strong> Leaving the Nether for the Overworld, or
     *       entering a datapack dimension, is never gated — only arrival in the Nether or the End
     *       is.</li>
     *   <li><strong>The gate is switched off.</strong> {@code enabled: false} means the operator
     *       does not want that dimension gated at all.</li>
     * </ul>
     *
     * <p>Note that this compares <em>kinds</em>, not worlds. A server with two Overworld worlds
     * teleporting between them is {@code OVERWORLD -> OVERWORLD} and is left alone, which is the
     * behaviour a multiverse setup needs; a second Nether world is still a Nether arrival and is
     * still gated, which is the behaviour the gate needs.
     *
     * @param from   where the transit starts
     * @param to     where it ends
     * @param config the snapshot the caller is already holding
     * @return the dimension whose gate applies, or empty when nothing here is gated
     */
    public static Optional<DimensionUnlock> gatedDestination(EnvironmentKind from, EnvironmentKind to,
                                                             PluginConfig config) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(config, "config");

        if (from == to || !to.isGatable()) {
            return Optional.empty();
        }
        DimensionUnlock dimension = to == EnvironmentKind.NETHER
                ? DimensionUnlock.NETHER
                : DimensionUnlock.THE_END;
        return gate(dimension, config).enabled() ? Optional.of(dimension) : Optional.empty();
    }

    /** The configured gate for {@code dimension}. */
    public static DimensionGate gate(DimensionUnlock dimension, PluginConfig config) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(config, "config");
        return dimension == DimensionUnlock.NETHER
                ? config.dimensionGates().nether()
                : config.dimensionGates().theEnd();
    }

    /** What that gate demands, in the shape {@code ProgressionManager} evaluates. */
    public static MilestoneRequirement requirement(DimensionUnlock dimension, PluginConfig config) {
        return MilestoneRequirement.of(gate(dimension, config));
    }

    /**
     * Whether the gate is waived for this player before their progression is even looked at.
     *
     * <p>Three independent waivers, deliberately all equal in force:
     *
     * <ul>
     *   <li>{@code antispeedrun.bypass.gates}, the standing permission. Declared in
     *       {@code plugin.yml} as a child of {@code antispeedrun.bypass} and pointedly <em>not</em>
     *       of {@code antispeedrun.admin}, so operators are gated like everyone else unless
     *       somebody grants it.</li>
     *   <li>An unexpired {@code BypassStore} grant from {@code /asr bypass}, which is a different
     *       thing from the permission and has to be checked separately.</li>
     *   <li>An operator having opened the dimension server-wide with {@code /asr unlock}, which
     *       {@code DimensionUnlockStore} documents as an override rather than a replacement.</li>
     * </ul>
     *
     * @param hasBypassPermission {@code player.hasPermission(BYPASS_PERMISSION)}
     * @param hasBypassGrant      {@code plugin.bypasses().hasBypass(player, now)}
     * @param serverUnlocked      {@code plugin.dimensionUnlocks().isUnlocked(dimension)}
     */
    public static boolean waived(boolean hasBypassPermission, boolean hasBypassGrant,
                                 boolean serverUnlocked) {
        return hasBypassPermission || hasBypassGrant || serverUnlocked;
    }

    /**
     * How long a gate decision taken on the way in stays good for the arrival it belongs to.
     *
     * <p>The arrival follows the decision by a tick or two — the portal event, then the world
     * change. Ten seconds is far longer than that gap and far shorter than a session, so a note
     * that is never consumed (the transit was cancelled after all, or the server dropped it) has
     * expired long before the player's next portal. Nothing about the gate depends on the exact
     * number: it is a staleness bound on a hand-off, not a threshold on a requirement, so R-02 does
     * not apply to it.
     */
    public static final long DECISION_WINDOW_MILLIS = 10_000L;

    /** What {@link #arrival} says to do about a player who has just landed in another dimension. */
    public enum Arrival {

        /** Nothing to do: the arrival was either ungated, decided already, waived, or earned. */
        ALLOWED,

        /** The player is standing in a dimension nothing ever cleared them for. Send them back. */
        REJECTED
    }

    /**
     * The backstop's verdict on a player who is <em>already</em> in a gated dimension — #100.
     *
     * <p>Every other method here decides a transit before it happens. This one runs after the fact,
     * because on Folia one route does not announce itself at all: a vehicle carrying a passenger
     * through a portal fires neither {@code EntityPortalEvent} nor {@code PlayerPortalEvent}
     * (PaperMC/Folia#453), so there is no transit to cancel and the only evidence the gate ever gets
     * is the player turning up on the other side.
     *
     * <p>The whole difficulty is telling that arrival apart from the several legitimate ways an
     * ineligible player reaches the Nether, and the answer is that each of those leaves a trace:
     *
     * <p>Gatedness is not one of the arguments: the caller has already asked
     * {@link #gatedDestination(EnvironmentKind, EnvironmentKind, PluginConfig)} which gate applies,
     * because it needs the answer to look the other three up. Reaching this method at all means one
     * does.
     *
     * <ul>
     *   <li><strong>{@code eligible}</strong> — the player earned it. This is the ordinary case and
     *       it is checked fresh rather than remembered, so a player let through the gate a moment
     *       ago is never bounced by the backstop.</li>
     *   <li><strong>{@code waived}</strong> — the permission, a {@code /asr bypass} grant or an
     *       {@code /asr unlock}. Also re-read rather than remembered, for the same reason.</li>
     *   <li><strong>{@code decided}</strong> — a handler upstream already looked at this arrival and
     *       let it stand even though the player is neither eligible nor waived. There are exactly
     *       three of those, all deliberate: a teleport whose cause this plugin does not regulate
     *       (an operator's {@code /tp}, a warp plugin — see {@code GATED_CAUSES}), a portal event
     *       with no resolved destination (#92's fail-open), and a rider the vehicle path has already
     *       ejected and is repositioning. Without this the backstop would overturn all three.</li>
     * </ul>
     *
     * <p>What is left over — ineligible, unwaived, and nobody decided anything — is the transit no
     * event reported. That is the bypass, and it fails closed.
     *
     * @param decided  whether an upstream handler already dealt with this arrival
     * @param waived   as {@link #waived(boolean, boolean, boolean)}
     * @param eligible whether the player meets the gate's requirement right now
     */
    public static Arrival arrival(boolean decided, boolean waived, boolean eligible) {
        return decided || waived || eligible ? Arrival.ALLOWED : Arrival.REJECTED;
    }

    /**
     * Whether a decision recorded at {@code recordedAt} still covers an arrival seen at {@code now}.
     *
     * <p>A negative age is treated as stale rather than fresh. The two timestamps can come from
     * different Folia region threads, and {@code System.currentTimeMillis()} is not monotonic, so
     * "recorded in the future" is a clock artefact and the safe reading of it is that there is no
     * usable decision — which costs an ineligible player a bounce, not a bypass.
     */
    public static boolean decisionHolds(long recordedAt, long now) {
        long age = now - recordedAt;
        return age >= 0L && age < DECISION_WINDOW_MILLIS;
    }

    /**
     * The line a blocked player is shown: the operator's {@code rejection-message} verbatim, with
     * the fail-open hint appended when the evaluation could not be trusted.
     *
     * <p>The message is MiniMessage and is the operator's own text, so it is deserialised as
     * markup. The hint is not — it interpolates advancement keys read from {@code config.yml}, and
     * {@link com.ninja6.antispeedrun.commands.AntiSpeedrunCommand} already established that
     * anything arriving from outside gets its tags neutralised before it reaches a deserialiser.
     * That escaping is the caller's job, because it needs the MiniMessage instance; this method
     * only decides <em>what</em> to say.
     *
     * @return the configured rejection message, and the hint if {@link EligibilityResult} carries
     *         one. The hint is separate rather than concatenated so the caller can escape one half
     *         and not the other
     */
    public static Optional<String> fallbackHint(EligibilityResult result) {
        return Objects.requireNonNull(result, "result").fallbackHint();
    }
}
