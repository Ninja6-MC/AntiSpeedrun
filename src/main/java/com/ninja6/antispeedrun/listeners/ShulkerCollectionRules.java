package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.storage.PersonalCredit;

/** The optional shell credit adds to ordinary tiers without changing their prerequisites. */
public final class ShulkerCollectionRules {
    private ShulkerCollectionRules() { }

    public static boolean earns(EntityType entity, boolean player, boolean npc, boolean shellDropped) {
        return entity == EntityType.SHULKER && shellDropped
                && PersonalCreditRules.creditable(player, npc);
    }

    public static boolean material(Material material, boolean enabled, boolean boxes) {
        return enabled && !material.isLegacy() && (material == Material.SHULKER_SHELL
                || boxes && (material == Material.SHULKER_BOX || material.name().endsWith("_SHULKER_BOX")));
    }

    public static EligibilityResult evaluate(EligibilityResult tier, boolean required, boolean credited) {
        if (!required || credited) {
            return tier;
        }
        var missing = new ArrayList<>(tier.missingAdvancements());
        String key = PersonalCredit.KILL_SHULKER.requirementKey();
        if (!missing.contains(key)) {
            missing.add(key);
        }
        return new EligibilityResult(false, missing, tier.unresolvableAdvancements(),
                tier.missingPlaytimeHours(), tier.missingAccountAgeDays(), tier.accountAgeUnknown());
    }
}
