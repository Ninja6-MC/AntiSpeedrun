package com.ninja6.antispeedrun.listeners;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.MilestoneRequirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mending trade gate's decisions, none of which need a server (#8, Task 3.2.2).
 *
 * <p>{@link ItemProgressionListener} stays untested for the reason {@link ItemGateRulesTest}
 * records: everything it does is read an event, call in here and apply the answer.
 */
class MendingTradeRulesTest {

    /** A configuration identical to the shipped defaults but for section 8. */
    private static PluginConfig withVillager(boolean gated, String advancement) {
        PluginConfig base = PluginConfig.defaults();
        return new PluginConfig(base.profile(), base.dimensionGates(), base.itemProgression(),
                base.trimProgression(), base.idleReminder(), base.progressCard(),
                base.journeyBook(), base.bossScaling(), base.antiCheese(),
                new PluginConfig.VillagerProgression(gated, advancement), List.of());
    }

    @Nested
    @DisplayName("the off switch")
    class Armed {

        /**
         * The criterion that keeps the trade path free on a server nobody configured:
         * {@code gate-mending-trade} defaults to {@code false}, and the listener reads nothing off
         * the result stack until this says otherwise.
         */
        @Test
        @DisplayName("the shipped configuration leaves the gate off")
        void defaultsAreOff() {
            assertFalse(MendingTradeRules.armed(PluginConfig.defaults()));
        }

        @Test
        @DisplayName("an operator who switches it on arms it")
        void switchedOn() {
            assertTrue(MendingTradeRules.armed(withVillager(true, "minecraft:story/cure_zombie_villager")));
        }

        @Test
        @DisplayName("null configuration is a programming error, not a silent pass")
        void nullConfig() {
            assertThrows(NullPointerException.class, () -> MendingTradeRules.armed(null));
        }
    }

    @Nested
    @DisplayName("recognising the enchantment")
    class CarriesMending {

        /**
         * The acceptance criterion, from the side that makes it different from every other gate in
         * the plugin: an {@code ENCHANTED_BOOK} of Mending and one of Bane of Arthropods are the
         * same {@code Material}, so the material-keyed item gate cannot tell them apart and this
         * must.
         */
        @Test
        @DisplayName("a Mending result is recognised; another enchantment on the same material is not")
        void tellsTwoEnchantedBooksApart() {
            assertTrue(MendingTradeRules.carriesMending(Set.of(MendingTradeRules.MENDING_KEY)));
            assertFalse(MendingTradeRules.carriesMending(Set.of("minecraft:bane_of_arthropods")));
        }

        /**
         * Mending alongside other enchantments still counts. A librarian sells single-enchantment
         * books, but the gate is asked about the union of a stack's normal and stored enchantments
         * and a plugin-supplied merchant can offer a tool carrying several.
         */
        @Test
        @DisplayName("Mending among others still counts")
        void mendingAmongOthers() {
            assertTrue(MendingTradeRules.carriesMending(
                    Set.of("minecraft:unbreaking", MendingTradeRules.MENDING_KEY, "minecraft:efficiency")));
        }

        @Test
        @DisplayName("an unenchanted result carries nothing")
        void noEnchantments() {
            assertFalse(MendingTradeRules.carriesMending(Set.of()));
        }

        /**
         * The key is matched exactly. A caller handing in {@code NamespacedKey#toString} output is
         * always namespaced and lower case, so accepting a bare {@code mending} would only ever
         * loosen the match for something that is not an enchantment key at all.
         */
        @Test
        @DisplayName("the match is on the full namespaced key")
        void exactKey() {
            assertFalse(MendingTradeRules.carriesMending(Set.of("mending")));
            assertFalse(MendingTradeRules.carriesMending(Set.of("MINECRAFT:MENDING")));
            assertFalse(MendingTradeRules.carriesMending(Set.of("otherplugin:mending")));
        }

        /**
         * The collection is assembled from a registry lookup the rules class does not control, so a
         * {@code null} in it is skipped rather than thrown over — a gate that raised out of an
         * event handler would fail open on the whole trade.
         */
        @Test
        @DisplayName("a null entry is ignored rather than thrown over")
        void nullEntry() {
            assertFalse(MendingTradeRules.carriesMending(Arrays.asList((String) null)));
            assertTrue(MendingTradeRules.carriesMending(
                    Arrays.asList(null, MendingTradeRules.MENDING_KEY)));
        }

        @Test
        @DisplayName("null collection is a programming error")
        void nullCollection() {
            assertThrows(NullPointerException.class, () -> MendingTradeRules.carriesMending(null));
        }
    }

    @Nested
    @DisplayName("what the gate demands")
    class Requirement {

        @Test
        @DisplayName("an armed gate requires exactly the configured advancement")
        void armedRequiresTheConfiguredKey() {
            MilestoneRequirement requirement =
                    MendingTradeRules.requirement(withVillager(true, "minecraft:story/cure_zombie_villager"));

            assertEquals(List.of("minecraft:story/cure_zombie_villager"), requirement.advancements());
            assertEquals(0.0D, requirement.playtimeHours(),
                    "section 8 configures no playtime requirement");
            assertEquals(0, requirement.accountAgeDays(),
                    "section 8 configures no tenure requirement");
        }

        /**
         * The advancement key is configuration, not {@code zombie_doctor} hard-coded. An operator
         * who points section 8 at a different advancement gets that one evaluated.
         */
        @Test
        @DisplayName("the key is whatever the operator configured")
        void theKeyIsConfigurable() {
            assertEquals(List.of("minecraft:nether/root"),
                    MendingTradeRules.requirement(withVillager(true, "minecraft:nether/root"))
                            .advancements());
        }

        /**
         * With the gate off the requirement is empty, so {@code ProgressionManager#evaluate}
         * short-circuits to a pass without a snapshot. The listener never gets this far on a
         * default server -- {@link MendingTradeRules#armed} stops it -- but a requirement that
         * demanded the key anyway would be a trap for the next caller.
         */
        @Test
        @DisplayName("the gate switched off demands nothing")
        void offDemandsNothing() {
            assertTrue(MendingTradeRules.requirement(
                    withVillager(false, "minecraft:story/cure_zombie_villager")).isEmpty());
        }

        /**
         * An armed gate with a blank key. No {@code config.yml} can express this since #92, but
         * {@code VillagerProgression} is a public record, and requiring {@code ""} here would hand
         * the empty key to the advancement lookup -- the exact failure
         * {@code Milestone#villagerTradeAdvancement} exists to prevent. Going through that method
         * rather than reading the field is what makes this hold.
         */
        @Test
        @DisplayName("an armed gate with a blank key demands nothing, not \"\"")
        void blankKeyDemandsNothing() {
            MilestoneRequirement requirement = MendingTradeRules.requirement(withVillager(true, "   "));

            assertTrue(requirement.isEmpty());
            assertFalse(requirement.advancements().contains(""));
        }
    }

    @Nested
    @DisplayName("what the player is told")
    class Feedback {

        /**
         * Section 8 has no {@code hint} of its own to hang player-facing text on, so the refusal
         * line is composed from the evaluation. Shared with the item gate rather than duplicated --
         * two descriptions of the same refusal are two things that can drift.
         */
        @Test
        @DisplayName("the outstanding advancement is what the player is shown")
        void outstandingAdvancement() {
            EligibilityResult blocked = new EligibilityResult(false,
                    List.of("minecraft:story/cure_zombie_villager"), List.of(), 0.0D, 0, false);

            assertEquals("minecraft:story/cure_zombie_villager", ItemGateRules.outstanding(blocked));
        }

        /**
         * A refusal whose only requirement was waived as unresolvable still says something. The
         * listener interpolates this into the operator's {@code rejection-message}, and a blank
         * would render as "Requires: ".
         */
        @Test
        @DisplayName("nothing actionable still produces a line")
        void nothingActionable() {
            EligibilityResult waived = new EligibilityResult(false, List.of(),
                    List.of("minecraft:story/cure_zombie_villager"), 0.0D, 0, false);

            assertEquals("further progression", ItemGateRules.outstanding(waived));
        }
    }

    @Nested
    @DisplayName("the constants the listener keys on")
    class Constants {

        /**
         * The result slot of a vanilla merchant screen. Slots 0 and 1 are the ingredient inputs and
         * hold the player's own items; scoping the withdrawal check to 2 is what stops the gate
         * refusing to hand those back.
         */
        @Test
        @DisplayName("the merchant result is slot 2")
        void resultSlot() {
            assertEquals(2, MendingTradeRules.MERCHANT_RESULT_SLOT);
        }

        /**
         * The feedback key shares the item gate's per-tier cooldown map, so it must not be able to
         * collide with a tier id. Tier ids are YAML mapping keys an operator writes; the colon
         * keeps this one out of that namespace.
         */
        @Test
        @DisplayName("the feedback key cannot collide with a shipped tier id")
        void feedbackKeyIsOutsideTheTierNamespace() {
            assertTrue(MendingTradeRules.FEEDBACK_KEY.indexOf(':') >= 0);
            for (PluginConfig.ItemTier tier : PluginConfig.defaults().itemProgression().gatedItems()) {
                assertFalse(MendingTradeRules.FEEDBACK_KEY.equals(tier.id()),
                        "a tier id colliding with the Mending gate would silently throttle one "
                                + "refusal behind the other");
            }
        }
    }
}
