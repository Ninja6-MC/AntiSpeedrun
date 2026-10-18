package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ninja6.antispeedrun.progression.EligibilityResult;

class ElytraCollectionRulesTest {
    @Test void giftingRequiresCreditEvenWhenVanillaAndTierPass() {
        assertFalse(ElytraCollectionRules.evaluate(EligibilityResult.pass(), true, false, false).eligible());
        assertTrue(ElytraCollectionRules.evaluate(EligibilityResult.pass(), true, true, false).eligible());
        assertTrue(ElytraCollectionRules.evaluate(EligibilityResult.pass(), false, false, false).eligible());
    }
    @Test void earningActionExemptsOnlyItsOwnMissingCredit() {
        EligibilityResult tier = new EligibilityResult(false,
                List.of("minecraft:end/elytra", "minecraft:end/kill_dragon"), List.of(), 2, 3, false);
        EligibilityResult result = ElytraCollectionRules.evaluate(tier, true, false, true);
        assertEquals(List.of("minecraft:end/kill_dragon"), result.missingAdvancements());
        assertEquals(2, result.missingPlaytimeHours());
        assertEquals(3, result.missingAccountAgeDays());
        assertFalse(result.eligible());
    }
    @Test void originalFrameCanEarnWithoutSpeculativeStoredCredit() {
        EligibilityResult tier = new EligibilityResult(false, List.of("minecraft:end/elytra"),
                List.of(), 0, 0, false);
        assertTrue(ElytraCollectionRules.evaluate(tier, true, false, true).eligible());
        assertSame(tier, ElytraCollectionRules.evaluate(tier, false, false, true));
    }
    @Test void explicitElytraCreditCanBeEarnedWhileOtherExplicitCreditsRemainRequired() {
        EligibilityResult tier = new EligibilityResult(false,
                List.of("antispeedrun:credit/elytra", "antispeedrun:credit/kill-shulker"), List.of(), 0, 0, false);
        EligibilityResult result = ElytraCollectionRules.evaluate(tier, false, false, true, true);
        assertEquals(List.of("antispeedrun:credit/kill-shulker"), result.missingAdvancements());
        assertFalse(result.eligible());
    }

}
