package com.ninja6.antispeedrun.listeners;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.storage.PersonalCredit;
import static org.junit.jupiter.api.Assertions.*;

class ShulkerCollectionRulesTest {
    @Test void onlyPersonalShulkerKillsWithShellDropsEarn() {
        assertTrue(ShulkerCollectionRules.earns(EntityType.SHULKER, true, false, true));
        assertFalse(ShulkerCollectionRules.earns(EntityType.SHULKER, true, false, false));
        assertFalse(ShulkerCollectionRules.earns(EntityType.SHULKER, false, false, true));
        assertFalse(ShulkerCollectionRules.earns(EntityType.SHULKER, true, true, true));
        assertFalse(ShulkerCollectionRules.earns(EntityType.SHULKER_BULLET, true, false, true));
    }
    @Test void everyBoxColorIsOptionalAndShellsFollowTheMaster() {
        int boxes = 0;
        for (Material material : Material.values()) {
            boolean box = !material.isLegacy() && (material == Material.SHULKER_BOX
                    || material.name().endsWith("_SHULKER_BOX"));
            if (box) boxes++;
            assertEquals(material == Material.SHULKER_SHELL,
                    ShulkerCollectionRules.material(material, true, false), material.name());
            assertEquals(material == Material.SHULKER_SHELL || box,
                    ShulkerCollectionRules.material(material, true, true), material.name());
            assertFalse(ShulkerCollectionRules.material(material, false, true));
        }
        assertEquals(17, boxes);
    }
    @Test void collectionCreditNeverReplacesAnExistingTierOrTimeRequirement() {
        var original = new EligibilityResult(false, List.of("minecraft:end/kill_dragon"),
                List.of("missing:key"), 2, 3, true);
        var missing = ShulkerCollectionRules.evaluate(original, true, false);
        assertEquals(List.of("minecraft:end/kill_dragon", PersonalCredit.KILL_SHULKER.requirementKey()),
                missing.missingAdvancements());
        assertEquals(original.unresolvableAdvancements(), missing.unresolvableAdvancements());
        assertEquals(2, missing.missingPlaytimeHours());
        assertEquals(3, missing.missingAccountAgeDays());
        assertTrue(missing.accountAgeUnknown());
        assertFalse(missing.eligible());
        assertSame(original, ShulkerCollectionRules.evaluate(original, true, true));
        assertSame(original, ShulkerCollectionRules.evaluate(original, false, false));
    }
    @Test void duplicateExplicitCreditRequirementsAreNotAdded() {
        String key = PersonalCredit.KILL_SHULKER.requirementKey();
        var tier = new EligibilityResult(false, List.of(key), List.of(), 0, 0, false);
        assertEquals(List.of(key), ShulkerCollectionRules.evaluate(tier, true, false).missingAdvancements());
    }
}
