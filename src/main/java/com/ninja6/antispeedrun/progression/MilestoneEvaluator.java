package com.ninja6.antispeedrun.progression;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compares a {@link MilestoneRequirement} against a {@link PlayerProgressionSnapshot}.
 *
 * <p>Pure, static and free of Bukkit types, so every rule below is exercisable off-server — which
 * matters, because {@code paper-api} is {@code compileOnly} and never reaches the test classpath.
 * Everything that needs a live server lives in {@link ProgressionManager}.
 *
 * <h2>Failure policy</h2>
 *
 * Two requirement kinds can be <em>unevaluable</em> rather than merely unmet, and both fail open:
 *
 * <ul>
 *   <li>An advancement key that resolves to nothing. No amount of play can earn an advancement the
 *       server does not define, so failing closed would seal the gate permanently for everyone,
 *       with the only symptom being that the dimension never opens.</li>
 *   <li>An account age the server never recorded — {@code getFirstPlayed()} returning {@code 0} for
 *       an online player. Likewise unfixable by the player.</li>
 * </ul>
 *
 * Both are reported on the {@link EligibilityResult} so the caller can surface a hint rather than
 * letting a misconfiguration pass silently. A requirement that is simply <em>unmet</em> — an
 * advancement not yet earned, hours not yet played — blocks normally.
 */
public final class MilestoneEvaluator {

    private MilestoneEvaluator() {
    }

    /**
     * Evaluates {@code requirement} against {@code snapshot}.
     *
     * @param requirement the milestone's configured requirements
     * @param snapshot    the player's captured facts
     * @return the outcome; never {@code null}
     */
    public static EligibilityResult evaluate(MilestoneRequirement requirement,
                                             PlayerProgressionSnapshot snapshot) {
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(snapshot, "snapshot");

        if (requirement.isEmpty()) {
            return EligibilityResult.pass();
        }

        List<String> missing = new ArrayList<>();
        List<String> unresolvable = new ArrayList<>();
        for (String key : requirement.advancements()) {
            if (snapshot.isUnresolvable(key)) {
                unresolvable.add(key);
            } else if (!snapshot.hasEarned(key)) {
                missing.add(key);
            }
        }

        double missingHours = 0.0D;
        if (requirement.playtimeHours() > 0.0D && snapshot.playtimeHours() < requirement.playtimeHours()) {
            missingHours = requirement.playtimeHours() - snapshot.playtimeHours();
        }

        int missingDays = 0;
        boolean ageUnknown = false;
        if (requirement.accountAgeDays() > 0) {
            if (!snapshot.accountAgeKnown()) {
                // Finding R-15: an online player with no recorded first join. Waive rather than
                // block; the player has no way to acquire a first-join record on demand.
                ageUnknown = true;
            } else if (snapshot.accountAgeDays() < requirement.accountAgeDays()) {
                missingDays = (int) Math.min(
                        Integer.MAX_VALUE, requirement.accountAgeDays() - snapshot.accountAgeDays());
            }
        }

        boolean eligible = missing.isEmpty() && missingHours <= 0.0D && missingDays <= 0;
        return new EligibilityResult(eligible, missing, unresolvable, missingHours, missingDays, ageUnknown);
    }
}
