package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Who contributes to a dragon fight and when it is won (#278). */
class DragonFightRulesTest {

    @Test
    @DisplayName("positive, finite damage from a real player in an End contributes")
    void contributes() {
        assertTrue(DragonFightRules.contributes(true, 0.5D, true, false));
    }

    @Test
    @DisplayName("zero, negative or non-finite damage, an NPC, a non-player or another dimension does not")
    void doesNotContribute() {
        for (double damage : new double[] {0.0D, -1.0D, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(DragonFightRules.contributes(true, damage, true, false), String.valueOf(damage));
        }
        assertFalse(DragonFightRules.contributes(true, 5.0D, true, true));
        assertFalse(DragonFightRules.contributes(true, 5.0D, false, false));
        assertFalse(DragonFightRules.contributes(false, 5.0D, true, false));
    }

    @Test
    @DisplayName("only the primary's death in an End wins the fight; a secondary's never does")
    void wins() {
        assertTrue(DragonFightRules.wins(true, false));
        assertFalse(DragonFightRules.wins(true, true));
        assertFalse(DragonFightRules.wins(false, false));
    }

    @Test
    @DisplayName("a primary removed by another plugin abandons the fight")
    void abandons() {
        assertTrue(DragonFightRules.abandons(true, false, "PLUGIN"));
        assertTrue(DragonFightRules.abandons(true, false, "DISCARD"));
        assertTrue(DragonFightRules.abandons(true, false, null));
    }

    @Test
    @DisplayName("death, an unload or a player leaving keeps the fight, and so does any secondary removal")
    void keeps() {
        assertFalse(DragonFightRules.abandons(true, false, "DEATH"));
        assertFalse(DragonFightRules.abandons(true, false, "UNLOAD"));
        assertFalse(DragonFightRules.abandons(true, false, "player_quit"));
        assertFalse(DragonFightRules.abandons(true, true, "PLUGIN"));
        assertFalse(DragonFightRules.abandons(false, false, "PLUGIN"));
    }
}
