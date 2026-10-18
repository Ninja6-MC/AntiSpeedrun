package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.List;
import com.ninja6.antispeedrun.progression.EligibilityResult;

/** Adds the collection requirement while preserving every ordinary item-tier prerequisite. */
public final class ElytraCollectionRules {
    private ElytraCollectionRules() { }

    public static EligibilityResult evaluate(EligibilityResult tier, boolean required,
                                             boolean credited, boolean collecting) {
        return evaluate(tier, required, credited, collecting, false);
    }

    public static EligibilityResult evaluate(EligibilityResult tier, boolean required,
                                             boolean credited, boolean collecting, boolean explicitApplies) {
        if (!required && !(collecting && explicitApplies)) {
            return tier;
        }
        List<String> missing = new ArrayList<>(tier.missingAdvancements());
        if (collecting && explicitApplies) {
            missing.remove("antispeedrun:credit/elytra");
        }
        if (collecting && required) {
            missing.remove("minecraft:end/elytra");
        } else if (required && !collecting && !credited && !missing.contains("minecraft:end/elytra")) {
            missing.add("minecraft:end/elytra");
        }
        return new EligibilityResult(missing.isEmpty() && tier.missingPlaytimeHours() <= 0
                && tier.missingAccountAgeDays() <= 0, missing, tier.unresolvableAdvancements(),
                tier.missingPlaytimeHours(), tier.missingAccountAgeDays(), tier.accountAgeUnknown());
    }
}
