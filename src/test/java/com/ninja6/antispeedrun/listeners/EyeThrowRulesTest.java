package com.ninja6.antispeedrun.listeners;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.MilestoneEvaluator;
import com.ninja6.antispeedrun.progression.MilestoneRequirement;
import com.ninja6.antispeedrun.progression.PlayerProgressionSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The early Eye of Ender rule's decisions (#7, Task 3.2.1). {@link EyeThrowListener} reads a
 * {@code PlayerInteractEvent}, which needs a server, and stays untested for the reason
 * {@link ItemGateRulesTest} records.
 */
class EyeThrowRulesTest {

    /** The shipped defaults with section 7's master switch and eye rule set as given. */
    private static PluginConfig withAntiCheese(boolean enabled, boolean blockEyes) {
        PluginConfig base = PluginConfig.defaults();
        PluginConfig.AntiCheese a = base.antiCheese();
        return new PluginConfig(base.profile(), base.dimensionGates(), base.itemProgression(),
                base.trimProgression(), base.idleReminder(), base.progressCard(),
                base.journeyBook(), base.bossScaling(),
                new PluginConfig.AntiCheese(enabled, a.blockBedAnchorBossDamage(),
                        a.capSingleHitBossDamage(), a.maxSingleHitBossDamage(), blockEyes, a.earlyEyeRejectionMessage(),
                        a.blockExitPortalCrystalPlace(), a.blockGatewayPreDragon(),
                        a.outerEndRadius(), a.outerEndPollSeconds()),
                base.villagerProgression(), List.of());
    }

    @Nested
    @DisplayName("the off switches")
    class Armed {

        @Test
        @DisplayName("the shipped configuration arms the rule")
        void defaultsAreOn() {
            assertTrue(EyeThrowRules.armed(PluginConfig.defaults()));
        }

        @Test
        @DisplayName("block-early-eye-throwing: false disarms it")
        void ruleOff() {
            assertFalse(EyeThrowRules.armed(withAntiCheese(true, false)));
        }

        @Test
        @DisplayName("anti-cheese.enabled: false disarms it even with the rule itself left on")
        void sectionOff() {
            assertFalse(EyeThrowRules.armed(withAntiCheese(false, true)));
        }
    }

    @Nested
    @DisplayName("what counts as a throw")
    class Respond {

        private static final EyeThrowRules.Click AIR = EyeThrowRules.Click.RIGHT_AIR;
        private static final EyeThrowRules.Click BLOCK = EyeThrowRules.Click.RIGHT_BLOCK;

        @Test
        @DisplayName("a right click in the air is a throw, refused and explained")
        void air() {
            for (boolean frame : new boolean[] {false, true}) {
                for (boolean takes : new boolean[] {false, true}) {
                    assertEquals(EyeThrowRules.Response.DENY_AND_EXPLAIN,
                            EyeThrowRules.respond(AIR, frame, takes),
                            "the block flags mean nothing without a clicked block");
                }
            }
        }

        @Test
        @DisplayName("a right click on an ordinary block, or on a filled frame, is a throw")
        void block() {
            assertEquals(EyeThrowRules.Response.DENY_AND_EXPLAIN,
                    EyeThrowRules.respond(BLOCK, false, false));
        }

        /**
         * The review nit on #144: a chest opened with an eye in hand is not a throw, so the player is
         * told nothing, but the item use is still denied in case the block declines the click.
         */
        @Test
        @DisplayName("a block that takes the click itself is denied silently, not explained")
        void interactableBlock() {
            assertEquals(EyeThrowRules.Response.DENY_SILENTLY,
                    EyeThrowRules.respond(BLOCK, false, true));
        }

        @Test
        @DisplayName("setting an eye into an empty frame is left alone")
        void emptyFrame() {
            assertEquals(EyeThrowRules.Response.IGNORE, EyeThrowRules.respond(BLOCK, true, false));
            assertEquals(EyeThrowRules.Response.IGNORE, EyeThrowRules.respond(BLOCK, true, true));
        }

        @Test
        @DisplayName("a left click never uses the eye")
        void other() {
            assertEquals(EyeThrowRules.Response.IGNORE,
                    EyeThrowRules.respond(EyeThrowRules.Click.OTHER, false, false));
        }

        @Test
        @DisplayName("null click is a programming error")
        void nullClick() {
            assertThrows(NullPointerException.class, () -> EyeThrowRules.respond(null, false, false));
        }
    }

    @Nested
    @DisplayName("waivers")
    class Waived {

        @Test
        @DisplayName("any one of permission, grant or a server-wide End unlock waives the rule")
        void anyWaives() {
            assertFalse(EyeThrowRules.waived(false, false, false));
            assertTrue(EyeThrowRules.waived(true, false, false));
            assertTrue(EyeThrowRules.waived(false, true, false));
            assertTrue(EyeThrowRules.waived(false, false, true));
        }
    }

    @Nested
    @DisplayName("the requirement")
    class Requirement {

        @Test
        @DisplayName("is finding a Nether Fortress, and nothing else")
        void fortressOnly() {
            MilestoneRequirement req = EyeThrowRules.requirement(PluginConfig.defaults());
            assertEquals(List.of("minecraft:nether/find_fortress"), req.advancements());
            assertEquals(0.0D, req.playtimeHours());
            assertEquals(0, req.accountAgeDays());
        }

        @Test
        @DisplayName("refuses a player without the fortress and admits one who has found it")
        void evaluates() {
            MilestoneRequirement req = EyeThrowRules.requirement(PluginConfig.defaults());
            Set<String> queried = Milestone.allRequiredAdvancements(PluginConfig.defaults());

            EligibilityResult fresh = MilestoneEvaluator.evaluate(req,
                    new PlayerProgressionSnapshot(queried, Set.of("minecraft:nether/obtain_blaze_rod"),
                            Set.of(), 50.0D, 30L, true, 0L));
            assertFalse(fresh.eligible(), "a blaze rod is not the fortress");
            assertEquals(List.of("minecraft:nether/find_fortress"), fresh.missingAdvancements());

            EligibilityResult found = MilestoneEvaluator.evaluate(req,
                    new PlayerProgressionSnapshot(queried, Set.of("minecraft:nether/find_fortress"),
                            Set.of(), 0.0D, 0L, true, 0L));
            assertTrue(found.eligible());
        }

        @Test
        @DisplayName("is empty, and so a pass, when the rule is off")
        void emptyWhenOff() {
            assertTrue(EyeThrowRules.requirement(withAntiCheese(true, false)).isEmpty());
        }

        /**
         * The capture queries exactly {@code allRequiredAdvancements}; a key outside it is waived as
         * uncovered on every evaluation, which would let every eye fly.
         */
        @Test
        @DisplayName("is captured with every other requirement while the rule is on, and only then")
        void captured() {
            assertTrue(Milestone.allRequiredAdvancements(PluginConfig.defaults())
                    .contains(Milestone.EARLY_EYE_ADVANCEMENT));

            PluginConfig off = withAntiCheese(true, false);
            Set<String> offKeys = Milestone.allRequiredAdvancements(off);
            // The shipped End gate and end-tier also name find_fortress, so assert on a config
            // that names it nowhere else before claiming the rule alone added it.
            assertEquals(offKeys.contains(Milestone.EARLY_EYE_ADVANCEMENT),
                    otherwiseNamed(off), "the switched-off rule adds nothing of its own");
        }

        private boolean otherwiseNamed(PluginConfig config) {
            return config.dimensionGates().nether().requireAdvancements()
                    .contains(Milestone.EARLY_EYE_ADVANCEMENT)
                    || config.dimensionGates().theEnd().requireAdvancements()
                    .contains(Milestone.EARLY_EYE_ADVANCEMENT)
                    || config.itemProgression().gatedItems().stream()
                    .anyMatch(t -> t.requireAdvancements().contains(Milestone.EARLY_EYE_ADVANCEMENT));
        }
    }
}
