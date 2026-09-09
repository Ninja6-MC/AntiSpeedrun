package com.ninja6.antispeedrun.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PluginConfig.DisplayType;
import com.ninja6.antispeedrun.config.PluginConfig.IdleReminder;
import com.ninja6.antispeedrun.progression.IdleReminderRules.Decision;
import com.ninja6.antispeedrun.progression.IdleReminderRules.MilestoneProgress;
import com.ninja6.antispeedrun.progression.IdleReminderRules.Position;
import com.ninja6.antispeedrun.progression.IdleReminderRules.State;

/**
 * The whole of the idle reminder's behaviour, tested without Bukkit, Paper or Folia.
 *
 * <p>That is the point of splitting {@link IdleReminderRules} out of {@link IdleReminderEngine}: the
 * questions worth getting right — has this player stood still long enough, is the cooldown spent,
 * what does the message say — are answerable from values, so they are answered here rather than left
 * to a server nobody can boot in CI.
 */
class IdleReminderRulesTest {

    private static final UUID OVERWORLD = UUID.nameUUIDFromBytes("overworld".getBytes());
    private static final UUID NETHER = UUID.nameUUIDFromBytes("nether".getBytes());

    private static final Position ORIGIN = new Position(OVERWORLD, 0.0D, 64.0D, 0.0D);

    /** The shipped section: enabled, 15s still, 10min cooldown, 5s on the action bar. */
    private static final IdleReminder SHIPPED = PluginConfig.defaults().idleReminder();

    private static IdleReminder settings(boolean enabled, int standStillSeconds, int cooldownMinutes) {
        return new IdleReminder(enabled, standStillSeconds, cooldownMinutes, 5, DisplayType.ACTIONBAR,
                SHIPPED.message());
    }

    @Nested
    @DisplayName("movement")
    class Movement {

        @Test
        @DisplayName("an unchanged position is not movement")
        void identicalIsStill() {
            assertFalse(IdleReminderRules.moved(ORIGIN, ORIGIN));
        }

        @Test
        @DisplayName("sub-block jitter is not movement")
        void jitterIsStill() {
            assertFalse(IdleReminderRules.moved(ORIGIN, new Position(OVERWORLD, 0.2D, 64.0D, 0.2D)));
        }

        @Test
        @DisplayName("crossing the threshold in any one axis is movement")
        void oneAxisIsMovement() {
            assertTrue(IdleReminderRules.moved(ORIGIN, new Position(OVERWORLD, 0.6D, 64.0D, 0.0D)));
            assertTrue(IdleReminderRules.moved(ORIGIN, new Position(OVERWORLD, 0.0D, 64.6D, 0.0D)));
            assertTrue(IdleReminderRules.moved(ORIGIN, new Position(OVERWORLD, 0.0D, 64.0D, -0.6D)));
        }

        @Test
        @DisplayName("a change of world is movement whatever the coordinates say")
        void worldChangeIsMovement() {
            assertTrue(IdleReminderRules.moved(ORIGIN, new Position(NETHER, 0.0D, 64.0D, 0.0D)));
        }
    }

    @Nested
    @DisplayName("the still-clock")
    class StillClock {

        @Test
        @DisplayName("a player who has just moved is not reminded, and the clock restarts there")
        void movingRestartsTheClock() {
            State previous = new State(ORIGIN, 0L, IdleReminderRules.NEVER_REMINDED);
            Position moved = new Position(OVERWORLD, 10.0D, 64.0D, 0.0D);

            Decision decision = IdleReminderRules.poll(previous, moved, 60_000L, settings(true, 15, 10));

            assertFalse(decision.remind());
            assertEquals(moved, decision.state().anchor());
            assertEquals(60_000L, decision.state().stillSinceMillis());
        }

        @Test
        @DisplayName("moving does not spend or refresh the cooldown")
        void movingKeepsTheCooldown() {
            State previous = new State(ORIGIN, 0L, 500L);

            Decision decision = IdleReminderRules.poll(previous,
                    new Position(OVERWORLD, 10.0D, 64.0D, 0.0D), 60_000L, settings(true, 15, 10));

            assertEquals(500L, decision.state().lastReminderMillis());
        }

        @Test
        @DisplayName("standing still short of stand-still-seconds says nothing")
        void tooSoon() {
            State previous = new State(ORIGIN, 0L, IdleReminderRules.NEVER_REMINDED);

            Decision decision = IdleReminderRules.poll(previous, ORIGIN, 14_999L, settings(true, 15, 10));

            assertFalse(decision.remind());
            assertSame(previous, decision.state());
        }

        @Test
        @DisplayName("standing still for exactly stand-still-seconds earns a reminder")
        void exactlyLongEnough() {
            State previous = new State(ORIGIN, 0L, IdleReminderRules.NEVER_REMINDED);

            assertTrue(IdleReminderRules.poll(previous, ORIGIN, 15_000L, settings(true, 15, 10)).remind());
        }

        @Test
        @DisplayName("the anchor is not re-seeded, so drift under the threshold still accumulates")
        void anchorSurvivesAStillPoll() {
            State previous = new State(ORIGIN, 0L, IdleReminderRules.NEVER_REMINDED);
            Position drifted = new Position(OVERWORLD, 0.4D, 64.0D, 0.0D);

            State next = IdleReminderRules.poll(previous, drifted, 1_000L, settings(true, 15, 10)).state();

            assertEquals(ORIGIN, next.anchor());
            // A second drift of the same size is now past the threshold from the original anchor,
            // which is the whole reason the anchor is kept: a boat on ice would otherwise read as
            // motionless while crossing the world.
            assertTrue(IdleReminderRules.moved(next.anchor(), new Position(OVERWORLD, 0.8D, 64.0D, 0.0D)));
        }

        @Test
        @DisplayName("a wall clock corrected backwards restarts the clock rather than going negative")
        void clockGoingBackwards() {
            State previous = new State(ORIGIN, 100_000L, IdleReminderRules.NEVER_REMINDED);

            Decision decision = IdleReminderRules.poll(previous, ORIGIN, 40_000L, settings(true, 15, 10));

            assertFalse(decision.remind());
            assertEquals(40_000L, decision.state().stillSinceMillis());
        }

        @Test
        @DisplayName("a disabled reminder still tracks position but never speaks")
        void disabledTracksButIsSilent() {
            State previous = new State(ORIGIN, 0L, IdleReminderRules.NEVER_REMINDED);

            Decision decision = IdleReminderRules.poll(previous, ORIGIN, 600_000L, settings(false, 15, 10));

            assertFalse(decision.remind());
            assertSame(previous, decision.state());
        }
    }

    @Nested
    @DisplayName("the cooldown")
    class Cooldown {

        @Test
        @DisplayName("a player who has never been reminded is not on cooldown")
        void neverReminded() {
            assertTrue(IdleReminderRules.cooldownElapsed(
                    IdleReminderRules.NEVER_REMINDED, 0L, settings(true, 15, 10)));
        }

        @Test
        @DisplayName("standing still through the window twice inside the cooldown says nothing twice")
        void secondReminderWaits() {
            State reminded = new State(ORIGIN, 0L, 0L);

            // Still for 15s, but only 15s after the last reminder and the cooldown is 10 minutes.
            assertFalse(IdleReminderRules.poll(reminded, ORIGIN, 15_000L, settings(true, 15, 10)).remind());
            // Ten minutes on, and still standing there since the last reminder reset the clock.
            assertTrue(IdleReminderRules.poll(reminded, ORIGIN, 600_000L, settings(true, 15, 10)).remind());
        }

        @Test
        @DisplayName("cooldown-minutes: 0 means no cooldown, which the reader permits")
        void zeroCooldown() {
            State reminded = new State(ORIGIN, 0L, 0L);

            assertTrue(IdleReminderRules.poll(reminded, ORIGIN, 15_000L, settings(true, 15, 0)).remind());
        }

        @Test
        @DisplayName("a backwards clock correction does not silence the player until it catches up")
        void backwardsClockDoesNotSilence() {
            assertTrue(IdleReminderRules.cooldownElapsed(900_000L, 1_000L, settings(true, 15, 10)));
        }

        @Test
        @DisplayName("being reminded stamps the cooldown and restarts the still-clock")
        void remindedStampsBoth() {
            State stamped = IdleReminderRules.reminded(
                    new State(ORIGIN, 0L, IdleReminderRules.NEVER_REMINDED), 15_000L);

            assertEquals(ORIGIN, stamped.anchor());
            assertEquals(15_000L, stamped.stillSinceMillis());
            assertEquals(15_000L, stamped.lastReminderMillis());
            // The still-clock restart is what stops a motionless player being reminded the instant
            // the cooldown expires: they owe another full stand-still window first.
            assertFalse(IdleReminderRules.poll(stamped, ORIGIN, 615_000L - 1L, settings(true, 15, 10))
                    .remind());
            assertTrue(IdleReminderRules.poll(stamped, ORIGIN, 615_000L, settings(true, 15, 10)).remind());
        }
    }

    @Nested
    @DisplayName("the message")
    class Message {

        @Test
        @DisplayName("the shipped {NEXT_STEP} placeholder becomes a MiniMessage tag")
        void placeholderIsRewritten() {
            assertEquals("<yellow>💡 Next Goal: <white><next_step> <gray>(Run <gold>/progress<gray>)",
                    IdleReminderRules.template(SHIPPED.message()));
        }

        @Test
        @DisplayName("a message with no placeholder is left exactly as the operator wrote it")
        void noPlaceholder() {
            assertEquals("<red>get on with it", IdleReminderRules.template("<red>get on with it"));
        }

        @Test
        @DisplayName("a five-second action bar is re-sent twice, and never at delay zero")
        void actionBarRefreshes() {
            assertEquals(List.of(40L, 80L), IdleReminderRules.actionBarRefreshDelays(5));
        }

        @Test
        @DisplayName("a duration the client already covers schedules nothing")
        void shortDurationSchedulesNothing() {
            assertEquals(List.of(), IdleReminderRules.actionBarRefreshDelays(1));
            assertEquals(List.of(), IdleReminderRules.actionBarRefreshDelays(2));
        }

        @Test
        @DisplayName("every scheduled refresh is at least one tick — Folia rejects zero (R-08)")
        void everyDelayIsAtLeastOne() {
            for (int seconds = 1; seconds <= 120; seconds++) {
                for (long delay : IdleReminderRules.actionBarRefreshDelays(seconds)) {
                    assertTrue(delay >= 1L, "delay " + delay + " at " + seconds + "s");
                }
            }
        }
    }

    @Nested
    @DisplayName("the next goal")
    class NextGoal {

        private static MilestoneProgress cleared(String id) {
            return new MilestoneProgress(new Milestone(id, id, MilestoneRequirement.none()),
                    EligibilityResult.pass());
        }

        private static MilestoneProgress outstanding(String displayName, EligibilityResult result) {
            return new MilestoneProgress(
                    new Milestone("dimension:x", displayName, MilestoneRequirement.none()), result);
        }

        @Test
        @DisplayName("a player who has cleared everything is told nothing")
        void nothingOutstanding() {
            assertEquals(Optional.empty(),
                    IdleReminderRules.nextStep(List.of(cleared("a"), cleared("b"))));
        }

        @Test
        @DisplayName("the first outstanding milestone in configured order wins")
        void firstOutstandingWins() {
            EligibilityResult missing = new EligibilityResult(
                    false, List.of("minecraft:story/smelt_iron"), List.of(), 0.0D, 0, false);

            assertEquals(Optional.of("The Nether - earn minecraft:story/smelt_iron"),
                    IdleReminderRules.nextStep(
                            List.of(cleared("a"), outstanding("The Nether", missing))));
        }

        @Test
        @DisplayName("several missing advancements are listed")
        void severalAdvancements() {
            EligibilityResult missing = new EligibilityResult(
                    false, List.of("minecraft:story/smelt_iron", "minecraft:nether/root"),
                    List.of(), 0.0D, 0, false);

            assertEquals(Optional.of(
                            "The End - earn minecraft:story/smelt_iron, minecraft:nether/root"),
                    IdleReminderRules.nextStep(List.of(outstanding("The End", missing))));
        }

        @Test
        @DisplayName("remaining playtime under an hour reads in minutes")
        void playtimeInMinutes() {
            EligibilityResult missing =
                    new EligibilityResult(false, List.of(), List.of(), 0.25D, 0, false);

            assertEquals(Optional.of("The Nether - 15 minutes more playtime"),
                    IdleReminderRules.nextStep(List.of(outstanding("The Nether", missing))));
        }

        @Test
        @DisplayName("remaining playtime over an hour reads in hours, locale-independently")
        void playtimeInHours() {
            EligibilityResult missing =
                    new EligibilityResult(false, List.of(), List.of(), 2.5D, 0, false);

            assertEquals(Optional.of("The Nether - 2.5 hours more playtime"),
                    IdleReminderRules.nextStep(List.of(outstanding("The Nether", missing))));
        }

        @Test
        @DisplayName("tenure is singular at one day and plural otherwise")
        void tenureIsPluralised() {
            assertEquals(Optional.of("The End - 1 more day on this server"),
                    IdleReminderRules.nextStep(List.of(outstanding("The End",
                            new EligibilityResult(false, List.of(), List.of(), 0.0D, 1, false)))));
            assertEquals(Optional.of("The End - 3 more days on this server"),
                    IdleReminderRules.nextStep(List.of(outstanding("The End",
                            new EligibilityResult(false, List.of(), List.of(), 0.0D, 3, false)))));
        }

        @Test
        @DisplayName("an advancement and a duration are both named")
        void bothKinds() {
            EligibilityResult missing = new EligibilityResult(
                    false, List.of("minecraft:story/smelt_iron"), List.of(), 3.0D, 2, false);

            assertEquals(Optional.of("The Nether - earn minecraft:story/smelt_iron and "
                            + "3.0 hours more playtime and 2 more days on this server"),
                    IdleReminderRules.nextStep(List.of(outstanding("The Nether", missing))));
        }

        @Test
        @DisplayName("an outstanding milestone with nothing actionable is skipped, not half-rendered")
        void nothingActionableIsSkipped() {
            EligibilityResult unactionable = new EligibilityResult(
                    false, List.of(), List.of("minecraft:story/smelt_iron"), 0.0D, 0, false);

            assertEquals(Optional.empty(),
                    IdleReminderRules.nextStep(List.of(outstanding("The Nether", unactionable))));
        }
    }
}
