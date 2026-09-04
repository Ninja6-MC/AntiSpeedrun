package com.ninja6.antispeedrun.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.MapConfigSection;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PluginConfig.DimensionGate;
import com.ninja6.antispeedrun.config.PluginConfig.ItemTier;

/**
 * The evaluation rules of #33 and their failure policies. Bukkit-free, like the type under test:
 * {@code paper-api} is {@code compileOnly} and is not on the test classpath.
 */
class MilestoneEvaluatorTest {

    private static final long NOW = 1_700_000_000_000L;

    private static PlayerProgressionSnapshot snapshot(Set<String> queried, Set<String> earned,
                                                      Set<String> unresolvable, double hours,
                                                      long ageDays, boolean ageKnown) {
        return new PlayerProgressionSnapshot(queried, earned, unresolvable, hours, ageDays, ageKnown, NOW);
    }

    private static PlayerProgressionSnapshot withAdvancements(Set<String> queried, Set<String> earned) {
        return snapshot(queried, earned, Set.of(), 100.0D, 365L, true);
    }

    @Nested
    @DisplayName("advancement requirements")
    class Advancements {

        @Test
        void everyRequiredAdvancementMustBeEarned() {
            MilestoneRequirement requirement =
                    new MilestoneRequirement(List.of("minecraft:story/smelt_iron"), 0.0D, 0);

            EligibilityResult earned = MilestoneEvaluator.evaluate(requirement,
                    withAdvancements(Set.of("minecraft:story/smelt_iron"), Set.of("minecraft:story/smelt_iron")));
            assertTrue(earned.eligible());

            EligibilityResult notEarned = MilestoneEvaluator.evaluate(requirement,
                    withAdvancements(Set.of("minecraft:story/smelt_iron"), Set.of()));
            assertFalse(notEarned.eligible());
            assertEquals(List.of("minecraft:story/smelt_iron"), notEarned.missingAdvancements());
        }

        @Test
        void missingAdvancementsKeepConfiguredOrder() {
            MilestoneRequirement requirement = new MilestoneRequirement(
                    List.of("a:one", "a:two", "a:three"), 0.0D, 0);

            EligibilityResult result = MilestoneEvaluator.evaluate(requirement,
                    withAdvancements(Set.of("a:one", "a:two", "a:three"), Set.of("a:two")));

            assertEquals(List.of("a:one", "a:three"), result.missingAdvancements());
        }

        @Test
        @DisplayName("an unresolvable advancement is waived, not treated as unearned")
        void unresolvableAdvancementIsWaived() {
            MilestoneRequirement requirement = new MilestoneRequirement(List.of("a:gone"), 0.0D, 0);

            EligibilityResult result = MilestoneEvaluator.evaluate(requirement,
                    snapshot(Set.of("a:gone"), Set.of(), Set.of("a:gone"), 0.0D, 0L, true));

            // Failing closed would seal the gate for every player forever, with no in-game remedy.
            assertTrue(result.eligible());
            assertTrue(result.missingAdvancements().isEmpty());
            assertEquals(List.of("a:gone"), result.unresolvableAdvancements());
            assertTrue(result.fallbackHint().isPresent());
            assertTrue(result.fallbackHint().orElseThrow().contains("a:gone"));
        }

        @Test
        void aResolvedRequirementProducesNoFallbackHint() {
            EligibilityResult result = MilestoneEvaluator.evaluate(
                    new MilestoneRequirement(List.of("a:one"), 0.0D, 0),
                    withAdvancements(Set.of("a:one"), Set.of()));

            assertTrue(result.fallbackHint().isEmpty());
        }
    }

    @Nested
    @DisplayName("playtime requirements")
    class Playtime {

        @Test
        void shortfallIsReportedInHours() {
            EligibilityResult result = MilestoneEvaluator.evaluate(
                    new MilestoneRequirement(List.of(), 10.0D, 0),
                    snapshot(Set.of(), Set.of(), Set.of(), 4.0D, 365L, true));

            assertFalse(result.eligible());
            assertEquals(6.0D, result.missingPlaytimeHours(), 1.0e-9D);
        }

        @Test
        void exactlyMeetingTheRequirementPasses() {
            EligibilityResult result = MilestoneEvaluator.evaluate(
                    new MilestoneRequirement(List.of(), 10.0D, 0),
                    snapshot(Set.of(), Set.of(), Set.of(), 10.0D, 365L, true));

            assertTrue(result.eligible());
            assertEquals(0.0D, result.missingPlaytimeHours());
        }

        @Test
        @DisplayName("PLAY_ONE_MINUTE counts ticks, not minutes")
        void statisticIsConvertedFromTicks() {
            // One hour is 20 ticks a second for 3,600 seconds.
            assertEquals(1.0D, PlayerProgressionSnapshot.playtimeHours(72_000), 1.0e-9D);
            assertEquals(0.5D, PlayerProgressionSnapshot.playtimeHours(36_000), 1.0e-9D);
            assertEquals(0.0D, PlayerProgressionSnapshot.playtimeHours(0));
            assertEquals(0.0D, PlayerProgressionSnapshot.playtimeHours(-1));
        }
    }

    @Nested
    @DisplayName("account age — finding R-15")
    class AccountAge {

        @Test
        void shortfallIsReportedInDays() {
            EligibilityResult result = MilestoneEvaluator.evaluate(
                    new MilestoneRequirement(List.of(), 0.0D, 7),
                    snapshot(Set.of(), Set.of(), Set.of(), 0.0D, 2L, true));

            assertFalse(result.eligible());
            assertEquals(5, result.missingAccountAgeDays());
            assertFalse(result.accountAgeUnknown());
        }

        @Test
        @DisplayName("an unrecorded first join waives the requirement and says so")
        void unknownAgeIsWaived() {
            EligibilityResult result = MilestoneEvaluator.evaluate(
                    new MilestoneRequirement(List.of(), 0.0D, 7),
                    snapshot(Set.of(), Set.of(), Set.of(), 0.0D, 0L, false));

            assertTrue(result.eligible());
            assertTrue(result.accountAgeUnknown());
            assertEquals(0, result.missingAccountAgeDays());
        }

        @Test
        @DisplayName("getFirstPlayed() == 0 is unknown, not the Unix epoch")
        void zeroFirstPlayedIsUnknown() {
            assertEquals(-1L, PlayerProgressionSnapshot.accountAgeDays(0L, NOW));
            assertEquals(-1L, PlayerProgressionSnapshot.accountAgeDays(-5L, NOW));
        }

        @Test
        @DisplayName("a first join in the future is unknown, not a negative age")
        void futureFirstPlayedIsUnknown() {
            assertEquals(-1L, PlayerProgressionSnapshot.accountAgeDays(NOW + 1L, NOW));
        }

        @Test
        void wholeDaysAreCountedDown() {
            long threeAndAHalfDays = (long) (3.5D * PlayerProgressionSnapshot.MILLIS_PER_DAY);
            assertEquals(3L, PlayerProgressionSnapshot.accountAgeDays(NOW - threeAndAHalfDays, NOW));
            assertEquals(0L, PlayerProgressionSnapshot.accountAgeDays(NOW - 1L, NOW));
        }

        @Test
        @DisplayName("the shipped default is 0 days, so a fresh world is not sealed")
        void shippedDefaultRequiresNoTenure() {
            PluginConfig defaults = PluginConfig.defaults();
            assertEquals(0, defaults.dimensionGates().nether().requireAccountAgeDays());
            assertEquals(0, defaults.dimensionGates().theEnd().requireAccountAgeDays());
        }
    }

    @Nested
    @DisplayName("requirements lifted from configuration")
    class FromConfiguration {

        @Test
        void aDimensionGateBecomesARequirement() {
            DimensionGate gate = new DimensionGate(
                    true, 2.5D, 7, List.of("minecraft:story/smelt_iron"), "<red>no");

            MilestoneRequirement requirement = MilestoneRequirement.of(gate);

            assertEquals(List.of("minecraft:story/smelt_iron"), requirement.advancements());
            assertEquals(2.5D, requirement.playtimeHours());
            assertEquals(7, requirement.accountAgeDays());
            assertFalse(requirement.isEmpty());
        }

        @Test
        void anItemTierBecomesTheSameShape() {
            ItemTier tier = new ItemTier("iron-tier", List.of("IRON_*"), List.of(), List.of(),
                    List.of("minecraft:story/smelt_iron"), 1.0D, 0, "Smelt an iron ingot");

            MilestoneRequirement requirement = MilestoneRequirement.of(tier);

            assertEquals(List.of("minecraft:story/smelt_iron"), requirement.advancements());
            assertEquals(1.0D, requirement.playtimeHours());
            assertEquals(0, requirement.accountAgeDays());
        }

        @Test
        void anEmptyRequirementShortCircuits() {
            MilestoneRequirement requirement = MilestoneRequirement.none();
            assertTrue(requirement.isEmpty());
            assertTrue(MilestoneEvaluator.evaluate(requirement,
                    snapshot(Set.of(), Set.of(), Set.of(), 0.0D, 0L, false)).eligible());
        }

        @Test
        void bothShippedDimensionGatesAreMilestones() {
            List<Milestone> milestones = Milestone.dimensionGates(PluginConfig.defaults());

            assertEquals(2, milestones.size());
            assertEquals(Milestone.NETHER_ID, milestones.get(0).id());
            assertEquals(Milestone.END_ID, milestones.get(1).id());
        }

        @Test
        @DisplayName("a disabled gate is not a milestone, so it is never announced as unlocked")
        void disabledGatesAreNotMilestones() throws Exception {
            PluginConfig config = PluginConfig.from(MapConfigSection.of(Map.of(
                    "dimension-gates", Map.of("nether", Map.of("enabled", false)))));

            List<Milestone> milestones = Milestone.dimensionGates(config);

            assertEquals(1, milestones.size());
            assertEquals(Milestone.END_ID, milestones.get(0).id());
        }

        @Test
        void everyConfiguredAdvancementKeyIsGatheredForOneCapture() {
            Set<String> keys = Milestone.allRequiredAdvancements(PluginConfig.defaults());

            assertTrue(keys.contains("minecraft:story/smelt_iron"));
            assertTrue(keys.contains("minecraft:story/mine_diamond"));
            assertTrue(keys.contains("minecraft:nether/obtain_blaze_rod"));
            assertTrue(keys.contains("minecraft:nether/find_fortress"));
            assertTrue(keys.contains("minecraft:story/cure_zombie_villager"));
        }
    }

    @Nested
    @DisplayName("snapshot coverage")
    class Coverage {

        @Test
        void aKeyThatWasNeverQueriedIsNotCovered() {
            PlayerProgressionSnapshot held = withAdvancements(Set.of("a:one"), Set.of("a:one"));

            assertTrue(held.covers(List.of("a:one")));
            assertFalse(held.covers(List.of("a:one", "a:two")));
        }
    }
}
