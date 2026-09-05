package com.ninja6.antispeedrun.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.ninja6.antispeedrun.config.ConfigLoadException;
import com.ninja6.antispeedrun.config.ConfigSection;
import com.ninja6.antispeedrun.config.MapConfigSection;
import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * What a capture is asked to look up, and what the villager trade gate contributes to it — #68,
 * item 4.
 *
 * <p>These run against real parsed YAML rather than a hand-built record, because the empty-key case
 * is reachable only through {@code ConfigReader.string}, which returns {@code ""} for a key an
 * operator has cleared.
 */
class MilestoneTest {

    private static PluginConfig config(String document) throws ConfigLoadException {
        Object root = new Yaml().load(new StringReader(document));
        ConfigSection section = root instanceof Map<?, ?> mapping
                ? MapConfigSection.of(mapping)
                : MapConfigSection.EMPTY;
        return PluginConfig.from(section);
    }

    /** A document with the two dimension gates set to one advancement each and nothing else. */
    private static String withVillager(String villagerBlock) {
        return """
                dimension-gates:
                  nether:
                    enabled: true
                    require-advancements:
                      - "minecraft:story/iron_tools"
                  the_end:
                    enabled: true
                    require-advancements:
                      - "minecraft:nether/obtain_blaze_rod"
                """ + villagerBlock;
    }

    @Nested
    @DisplayName("the villager trade advancement")
    class VillagerTradeAdvancement {

        @Test
        @DisplayName("is not queried at all while gate-mending-trade is off")
        void absentWhileTheGateIsOff() throws ConfigLoadException {
            PluginConfig config = config(withVillager("""
                    villager-progression:
                      gate-mending-trade: false
                      required-advancement: "minecraft:story/cure_zombie_villager"
                    """));

            assertTrue(Milestone.villagerTradeAdvancement(config).isEmpty());
            assertFalse(Milestone.allRequiredAdvancements(config)
                            .contains("minecraft:story/cure_zombie_villager"),
                    "a server with the trade gate off must not pay for the lookup, nor be warned "
                            + "about a key belonging to a feature it has switched off");
        }

        @Test
        @DisplayName("is queried, once, while the gate is on")
        void presentWhileTheGateIsOn() throws ConfigLoadException {
            PluginConfig config = config(withVillager("""
                    villager-progression:
                      gate-mending-trade: true
                      required-advancement: "minecraft:story/cure_zombie_villager"
                    """));

            assertEquals(java.util.Optional.of("minecraft:story/cure_zombie_villager"),
                    Milestone.villagerTradeAdvancement(config));
            assertTrue(Milestone.allRequiredAdvancements(config)
                    .contains("minecraft:story/cure_zombie_villager"));
        }

        @Test
        @DisplayName("an empty key means no advancement is required, not a malformed one")
        void anEmptyKeyIsNotAnUnresolvableKey() throws ConfigLoadException {
            PluginConfig config = config(withVillager("""
                    villager-progression:
                      gate-mending-trade: true
                      required-advancement: ""
                    """));

            assertTrue(Milestone.villagerTradeAdvancement(config).isEmpty(),
                    "an operator who clears the key is saying 'no advancement'; "
                            + "NamespacedKey.fromString on an empty string returns null, which "
                            + "would otherwise be reported as a typo");
            assertFalse(Milestone.allRequiredAdvancements(config).contains(""));
        }

        @Test
        @DisplayName("a blank key is treated the same as an empty one")
        void aBlankKeyIsAlsoNoRequirement() throws ConfigLoadException {
            PluginConfig config = config(withVillager("""
                    villager-progression:
                      gate-mending-trade: true
                      required-advancement: "   "
                    """));

            assertTrue(Milestone.villagerTradeAdvancement(config).isEmpty());
        }
    }

    @Test
    @DisplayName("the dimension gates' own keys are unaffected by the villager gate")
    void dimensionKeysAreAlwaysQueried() throws ConfigLoadException {
        PluginConfig config = config(withVillager("""
                villager-progression:
                  gate-mending-trade: false
                  required-advancement: "minecraft:story/cure_zombie_villager"
                """));

        Set<String> keys = Milestone.allRequiredAdvancements(config);
        assertTrue(keys.contains("minecraft:story/iron_tools"));
        assertTrue(keys.contains("minecraft:nether/obtain_blaze_rod"));
    }

}
