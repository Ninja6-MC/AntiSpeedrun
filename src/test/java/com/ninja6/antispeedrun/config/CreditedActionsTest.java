package com.ninja6.antispeedrun.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How a missing credited advancement is named to a player (#216). */
class CreditedActionsTest {

    private static final PluginConfig.ItemProgression ITEMS = PluginConfig.defaults().itemProgression();

    private static PluginConfig.ItemProgression items(boolean credit, boolean loot) {
        PluginConfig.ItemProgression i = ITEMS;
        return new PluginConfig.ItemProgression(i.enabled(), i.dropRecallEnabled(), i.gateDispensers(),
                i.gateNestedBundles(), i.feedbackCooldownSeconds(), i.rejectionMessage(), i.gatedItems(),
                credit, loot);
    }

    @Test
    @DisplayName("every credited advancement, and only those, has an action")
    void coversExactlyTheCreditedKeys() {
        for (String key : PossessionAdvancements.POSSESSION_TRIGGERED) {
            assertEquals(PossessionAdvancements.CREDITED.contains(key),
                    CreditedActions.action(key, ITEMS).isPresent(), key);
        }
        assertEquals(Optional.empty(), CreditedActions.action("minecraft:nether/find_fortress", ITEMS));
    }

    @Test
    @DisplayName("with credits off nothing is renamed")
    void creditsOff() {
        for (String key : PossessionAdvancements.CREDITED) {
            assertEquals(Optional.empty(), CreditedActions.action(key, items(false, true)), key);
        }
        assertFalse(CreditedActions.lootCounts(items(false, true)));
    }

    @Test
    @DisplayName("the loot alternative appears only while loot counts, and only for iron and diamonds")
    void lootAlternative() {
        for (String key : PossessionAdvancements.CREDITED) {
            boolean lootable = key.equals("minecraft:story/smelt_iron")
                    || key.equals("minecraft:story/mine_diamond");
            assertEquals(lootable, CreditedActions.action(key, items(true, true)).orElseThrow()
                    .contains("chest you open first"), key);
            assertFalse(CreditedActions.action(key, items(true, false)).orElseThrow().contains("chest"), key);
        }
        assertTrue(CreditedActions.lootCounts(items(true, true)));
        assertFalse(CreditedActions.lootCounts(items(true, false)));
    }

    @Test
    @DisplayName("a split keeps credited actions and other keys apart, each in order")
    void split() {
        CreditedActions.Split split = CreditedActions.split(List.of("minecraft:nether/obtain_blaze_rod",
                "minecraft:nether/find_fortress", "minecraft:story/mine_stone"), ITEMS);
        assertEquals(List.of("kill a blaze yourself", "mine natural stone with a pickaxe"), split.actions());
        assertEquals(List.of("minecraft:nether/find_fortress"), split.advancements());
        assertEquals(Optional.of("Kill a blaze yourself"),
                CreditedActions.sentence("minecraft:nether/obtain_blaze_rod", ITEMS));
    }
}
