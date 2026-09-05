package com.ninja6.antispeedrun.progression;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The predicate that decides whether a milestone needs watching — #68, item 1.
 *
 * <p>{@link UnlockWatch} arms a per-player {@code EntityScheduler} task on exactly this condition,
 * so getting it wrong is either a gate that still opens in silence or a task per player that never
 * had a reason to run. It lives on {@link EligibilityResult} rather than on
 * {@code ProgressionManager} so that it can be exercised here at all: the manager touches Bukkit,
 * which is a {@code compileOnly} dependency and is absent from the test runtime.
 */
class EligibilityResultTest {

    private static EligibilityResult outstanding(List<String> missingAdvancements,
                                                 double missingHours, int missingDays) {
        return new EligibilityResult(false, missingAdvancements, List.of(), missingHours,
                missingDays, false);
    }

    @Nested
    @DisplayName("outstandingOnTimeAlone")
    class OutstandingOnTimeAlone {

        @Test
        void anEligibleMilestoneIsNotOutstanding() {
            assertFalse(EligibilityResult.pass().outstandingOnTimeAlone());
        }

        @Test
        @DisplayName("a gate still waiting on an advancement is left to PlayerAdvancementDoneEvent")
        void anAdvancementStillOutstandingDoesNotArm() {
            assertFalse(outstanding(List.of("minecraft:story/iron_tools"), 2.0D, 0)
                            .outstandingOnTimeAlone(),
                    "the advancement event will fire and re-ask; arming now would be a task doing "
                            + "nothing until it does");
        }

        @Test
        @DisplayName("playtime alone is the case that has no event to fire on")
        void playtimeAloneArms() {
            assertTrue(outstanding(List.of(), 1.5D, 0).outstandingOnTimeAlone());
        }

        @Test
        @DisplayName("tenure alone likewise")
        void tenureAloneArms() {
            assertTrue(outstanding(List.of(), 0.0D, 3).outstandingOnTimeAlone());
        }

        @Test
        @DisplayName("a waived advancement does not count as outstanding")
        void anUnresolvableAdvancementDoesNotBlockArming() {
            EligibilityResult result = new EligibilityResult(false, List.of(),
                    List.of("minecraft:story/typo"), 1.0D, 0, false);
            assertTrue(result.outstandingOnTimeAlone(),
                    "MilestoneEvaluator already waives an unresolvable key, so the only thing left "
                            + "here really is the clock");
        }

        @Test
        @DisplayName("a milestone with nothing outstanding at all does not arm")
        void nothingOutstandingDoesNotArm() {
            assertFalse(outstanding(List.of(), 0.0D, 0).outstandingOnTimeAlone(),
                    "an ineligible result with nothing missing cannot be fixed by waiting, so a "
                            + "watch would spin forever");
        }
    }

    @Test
    @DisplayName("hasActionableRequirements still covers the advancement case")
    void actionableRequirementsAreUnchanged() {
        assertTrue(outstanding(List.of("minecraft:story/iron_tools"), 0.0D, 0)
                .hasActionableRequirements());
        assertFalse(EligibilityResult.pass().hasActionableRequirements());
    }
}
