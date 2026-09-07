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
