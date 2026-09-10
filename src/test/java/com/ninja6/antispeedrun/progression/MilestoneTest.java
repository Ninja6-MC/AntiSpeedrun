package com.ninja6.antispeedrun.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.ninja6.antispeedrun.config.UnenforceableGateException;

/**
 * What a capture is asked to look up, and what the villager trade gate contributes to it — #68,
 * item 4.
 *
 * <p>These run against real parsed YAML rather than a hand-built record, because what the model is
 * handed is decided by {@code ConfigReader} and the two have to be tested together. One case is the
 * exception and says so: since #92 an armed gate beside a blank key is refused at the read site, so
 * the blank branch of {@link Milestone#villagerTradeAdvancement} is no longer reachable from
 * {@code config.yml} at all and is exercised from a record built in code.
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
        @DisplayName("a cleared key parses as no requirement rather than as a malformed one")
        void aClearedKeyIsNotAnUnresolvableKey() throws ConfigLoadException {
            // What this proves is a READ-SITE property, and the display name says so: the reader
            // hands back "" rather than rejecting the document. NamespacedKey.fromString("")
            // returns null, so without the blank exemption a cleared key would be reported as a
            // typo. Read under a gate that is switched off, which since #92 is the only shape of
            // this combination config.yml can still express -- see the test below.
            //
            // It deliberately does NOT assert villagerTradeAdvancement here. With the gate off
            // that call returns empty from the gate-off branch before the key is looked at, so
            // such an assertion would pass for any key at all and prove nothing about the blank.
            // The blank branch itself is exercised directly below.
            PluginConfig empty = config(withVillager("""
                    villager-progression:
                      gate-mending-trade: false
                      required-advancement: ""
                    """));
            PluginConfig blank = config(withVillager("""
                    villager-progression:
                      gate-mending-trade: false
                      required-advancement: "   "
                    """));

            assertEquals("", empty.villagerProgression().requiredAdvancement());
            assertEquals("", blank.villagerProgression().requiredAdvancement(),
                    "a blank key is treated the same as an empty one");
            assertFalse(Milestone.allRequiredAdvancements(empty).contains(""),
                    "whatever the reason, \"\" must never be handed to the advancement lookup");
        }

        @Test
        @DisplayName("a blank key beside an armed gate still requires nothing, not \"\"")
        void aBlankKeyBesideAnArmedGateRequiresNothing() {
            // The blank branch of Milestone#villagerTradeAdvancement, exercised on its own.
            //
            // Since #92 no config.yml can reach it: gate on with a blank key is refused at the
            // read site, and gate off returns empty from the branch above it. It is reachable only
            // from a PluginConfig built in code -- VillagerProgression is a public record with a
            // public constructor -- so the branch is kept as the defence for that, and this test
            // is what stops it being deleted as dead and then quietly reintroducing "" into
            // BukkitAdvancementLookup.
            PluginConfig base = PluginConfig.defaults();
            PluginConfig config = new PluginConfig(base.profile(), base.dimensionGates(),
                    base.itemProgression(), base.trimProgression(), base.idleReminder(),
                    base.progressCard(), base.journeyBook(), base.bossScaling(), base.antiCheese(),
                    new PluginConfig.VillagerProgression(true, ""), List.of());

            assertTrue(Milestone.villagerTradeAdvancement(config).isEmpty(),
                    "an armed gate with no key requires nothing; it must not require \"\"");
            assertFalse(Milestone.allRequiredAdvancements(config).contains(""));
        }

        @Test
        @DisplayName("a cleared key beside a gate that is on no longer parses at all")
        void aClearedKeyBesideAnArmedGateIsRefused() {
            // #92's second waiver path. This used to parse into a mending trade gated on nothing,
            // with no warning about either half, so the requirement never reached this model to be
            // waived. It is refused at the read site instead, which is why the case above is
            // written with the gate off.
            //
            // UnenforceableGateException, not the ConfigLoadException supertype: only the subtype
            // makes AntiSpeedrunPlugin refuse to start. On the supertype, onEnable falls back to
            // PluginConfig.defaults(), which declare no item tiers -- so a fix that threw the
            // supertype would trade this waiver for item gating being off server-wide.
            assertThrows(UnenforceableGateException.class, () -> config(withVillager("""
                    villager-progression:
                      gate-mending-trade: true
                      required-advancement: ""
                    """)));
            assertThrows(UnenforceableGateException.class, () -> config(withVillager("""
                    villager-progression:
                      gate-mending-trade: true
                      required-advancement: "   "
                    """)));
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
