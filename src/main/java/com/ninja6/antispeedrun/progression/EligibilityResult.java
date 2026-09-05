package com.ninja6.antispeedrun.progression;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of evaluating one {@link MilestoneRequirement} against one
 * {@link PlayerProgressionSnapshot}: whether the player clears it, and if not, precisely what is
 * outstanding.
 *
 * <p>Immutable. Listeners cancel on {@code !}{@link #eligible()}; {@code /progress} (#3) renders
 * the outstanding fields; the rejection-message formatter substitutes them into
 * {@code {REQUIREMENT}}.
 *
 * @param eligible                 whether every requirement is satisfied
 * @param missingAdvancements      required advancements not yet earned, in configured order.
 *                                 Excludes {@link #unresolvableAdvancements()}, which are not
 *                                 something the player can go and earn
 * @param unresolvableAdvancements required advancements that resolve to nothing on this server.
 *                                 Treated as satisfied — see {@link #fallbackHint()}
 * @param missingPlaytimeHours     hours still to play; {@code 0.0} when satisfied
 * @param missingAccountAgeDays    days of server tenure still to elapse; {@code 0} when satisfied
 * @param accountAgeUnknown        whether the age check was waived because the server recorded no
 *                                 first-join time for the player (finding R-15)
 */
public record EligibilityResult(
        boolean eligible,
        List<String> missingAdvancements,
        List<String> unresolvableAdvancements,
        double missingPlaytimeHours,
        int missingAccountAgeDays,
        boolean accountAgeUnknown) {

    private static final EligibilityResult ELIGIBLE =
            new EligibilityResult(true, List.of(), List.of(), 0.0D, 0, false);

    public EligibilityResult {
        missingAdvancements = List.copyOf(Objects.requireNonNull(missingAdvancements, "missingAdvancements"));
        unresolvableAdvancements =
                List.copyOf(Objects.requireNonNull(unresolvableAdvancements, "unresolvableAdvancements"));
    }

    /**
     * The unconditional pass, for a milestone that requires nothing or a player who bypasses.
     *
     * <p>Named {@code pass} rather than {@code eligible} because a record's accessor owns that
     * name.
     */
    public static EligibilityResult pass() {
        return ELIGIBLE;
    }

    /**
     * A human-readable explanation of why the evaluation could not be trusted, if it could not.
     *
     * <p>Present only when a required advancement resolved to nothing — either the key is
     * malformed, or the server runs with vanilla advancements switched off or a datapack that
     * removes them. In that state the requirement is unsatisfiable by play, so the evaluator waives
     * it rather than sealing the gate forever; this hint is what the operator and the player get
     * told instead of a silent pass.
     *
     * @return the hint, or empty when every requirement resolved normally
     */
    public Optional<String> fallbackHint() {
        if (unresolvableAdvancements.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("This server does not define "
                + String.join(", ", unresolvableAdvancements)
                + " -- advancements may be disabled, so that requirement was skipped. "
                + "Ask an operator to review require-advancements in config.yml.");
    }

    /** Whether anything at all is outstanding that the player could still go and do. */
    public boolean hasActionableRequirements() {
        return !missingAdvancements.isEmpty() || missingPlaytimeHours > 0.0D || missingAccountAgeDays > 0;
    }

    /**
     * Whether this milestone fails only because a duration has not elapsed yet.
     *
     * <p>The arming condition for {@link UnlockWatch}, and the reason it exists: an advancement
     * announces itself through {@code PlayerAdvancementDoneEvent}, but the passage of time raises
     * no event, so a milestone in this state will open in silence unless something is watching it.
     * A milestone still missing an advancement is deliberately excluded — that event will fire and
     * the question will be re-asked then, so a task armed now would do nothing until it does.
     *
     * <p>{@link #unresolvableAdvancements()} does not count against this. {@link MilestoneEvaluator}
     * has already waived those, and an unknown account age is likewise already treated as
     * satisfied, so what remains here really is only the clock.
     */
    public boolean outstandingOnTimeAlone() {
        return !eligible
                && missingAdvancements.isEmpty()
                && (missingPlaytimeHours > 0.0D || missingAccountAgeDays > 0);
    }
}
