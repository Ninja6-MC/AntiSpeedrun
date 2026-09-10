package com.ninja6.antispeedrun.listeners;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
     *   <li><strong>{@code decided}</strong> — a handler upstream already let this player into this
     *       dimension even though they are neither eligible nor waived. There are exactly two of
     *       those, both deliberate: a teleport whose cause this plugin does not regulate (an
     *       operator's {@code /tp}, a warp plugin — see {@code GATED_CAUSES}), and a rider the
     *       vehicle path has already ejected and is repositioning. Without this the backstop would
     *       overturn both. A note is only ever good for the arrival it was written against — see
     *       {@link Decision}, which is what {@code decided} has to be derived through.</li>
     * </ul>
     *
     * <p>What is left over — ineligible, unwaived, and nobody decided anything — is the transit no
     * event reported. That is the bypass, and it fails closed.
     *
     * <p><strong>A cross-world respawn lands here too, and is meant to.</strong> CraftBukkit fires
     * {@code PlayerChangedWorldEvent} from {@code PlayerList#respawn} when the respawn world differs
     * from the death world, and that path fires no {@code PlayerTeleportEvent}, so it leaves no
     * note. A player who dies in the Overworld and respawns at a Nether anchor therefore arrives
     * {@code decided == false} and, if ineligible and unwaived, is returned. That is the intended
     * answer rather than an oversight: a respawn anchor set while the player was waived — under an
     * {@code /asr bypass} grant that has since lapsed, or before an {@code /asr lock} — is a
     * standing re-entry into a dimension the gate currently closes to them, and the gate is not a
     * one-time toll. They keep the anchor; only the arrival is undone.
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
     * One handler's record that it let a player into a gated dimension they neither earned nor were
     * waived for, so that {@link #arrival} does not overturn it a tick later.
     *
     * <h2>Why the destination is part of the note</h2>
     *
     * An earlier revision recorded only a gate and a timestamp, and that is not a record of a
     * decision — it is a bearer token. A note written by one transit was indistinguishable from a
     * note written by any other, so it could be spent on an arrival nobody had decided anything
     * about, which on Folia is precisely the unreported vehicle transit this whole backstop exists
     * to catch. Naming the world the decision was taken <em>about</em> is what ties a note to its
     * own transit: an arrival somewhere else is not the arrival that was cleared, and is judged on
     * its own merits.
     *
     * <p>The world rather than the gate, because the gate is a kind and several worlds can share
     * one — a multiverse server with two Nether worlds has one {@code NETHER} gate across both, and
     * a teleport cleared into one of them says nothing about the other.
     *
     * @param destinationWorld the world the deciding handler saw the player going to
     * @param recordedAt       {@code System.currentTimeMillis()} at the moment of the decision
     */
    public record Decision(UUID destinationWorld, long recordedAt) {

        public Decision {
            Objects.requireNonNull(destinationWorld, "destinationWorld");
        }
    }

    /**
     * Whether {@code note} covers an arrival in {@code arrivedIn} seen at {@code now}.
     *
     * <p>Both halves have to hold: the note must be for this world, and it must still be inside
     * {@link #DECISION_WINDOW_MILLIS}. A missing note is not a cover.
     */
    public static boolean decisionCovers(Decision note, UUID arrivedIn, long now) {
        Objects.requireNonNull(arrivedIn, "arrivedIn");
        return note != null
                && note.destinationWorld().equals(arrivedIn)
                && decisionHolds(note.recordedAt(), now);
    }

    /**
     * Writes a note into one player's ledger, replacing anything held for that gate.
     *
     * <p>The ledger is {@code Map<DimensionUnlock, Decision>} rather than a type of its own so that
     * the listener can hand over the concurrent map it already holds per player, and so that every
     * rule about a note stays here where a test can reach it without a server.
     */
    public static void note(Map<DimensionUnlock, Decision> notes, DimensionUnlock gate,
                            UUID destinationWorld, long now) {
        Objects.requireNonNull(notes, "notes");
        Objects.requireNonNull(gate, "gate");
        notes.put(gate, new Decision(destinationWorld, now));
    }

    /**
     * Takes this gate's note out of the ledger and says whether it covers an arrival in
     * {@code arrivedIn}.
     *
     * <p>Removed rather than merely read, and removed even when it does <em>not</em> cover. A note
     * covers one arrival: leaving a matching one in place would have a single admin {@code /tp}
     * clear every unreported transit for the rest of its window, and leaving a mismatched one in
     * place would let a player who has just been bounced retry until they land somewhere it fits.
     * The cost is a note that a genuinely pending transit would have used, which is a bounce for an
     * ineligible player rather than a way past the gate.
     *
     * @return whether the player's arrival was already decided
     */
    public static boolean consume(Map<DimensionUnlock, Decision> notes, DimensionUnlock gate,
                                  UUID arrivedIn, long now) {
        Objects.requireNonNull(gate, "gate");
        if (notes == null || notes.isEmpty()) {
            // The ordinary case: nothing was ever noted for this player. Answered without touching
            // the map, so an arrival costs nothing when there is nothing to spend.
            return false;
        }
        return decisionCovers(notes.remove(gate), arrivedIn, now);
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
