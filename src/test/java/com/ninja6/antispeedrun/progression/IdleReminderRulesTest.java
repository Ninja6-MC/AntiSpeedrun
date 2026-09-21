package com.ninja6.antispeedrun.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PluginConfig.DisplayType;
import com.ninja6.antispeedrun.config.PluginConfig.IdleReminder;
import com.ninja6.antispeedrun.progression.IdleReminderRules.Decision;
import com.ninja6.antispeedrun.progression.IdleReminderRules.FailureLogThrottle;
import com.ninja6.antispeedrun.progression.IdleReminderRules.MilestoneProgress;
import com.ninja6.antispeedrun.progression.IdleReminderRules.Position;
import com.ninja6.antispeedrun.progression.IdleReminderRules.Stage;
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
        @DisplayName("polled with the feature off, the decision is silence — unreachable from the engine")
        void disabledIsSilent() {
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

    /**
     * The ordering the whole of PR #110's review turned on: the cooldown stamp is earned by the
     * attempt at delivery, not by its success, and a delivery that throws is contained rather than
     * allowed out of the poll.
     *
     * <p>These run the loop the engine runs, one poll at a time, with a delivery that throws exactly
     * the way a malformed {@code idle-reminder.message} makes MiniMessage throw. That the subject is
     * a pure function is what makes that possible without a Bukkit harness.
     */
    @Nested
    @DisplayName("one whole poll")
    class Advance {

        private final List<Long> delivered = new ArrayList<>();
        private final List<Throwable> failures = new ArrayList<>();
        private final List<Stage> stages = new ArrayList<>();

        private void record(Stage stage, Throwable thrown) {
            stages.add(stage);
            failures.add(thrown);
        }

        private State advance(State previous, Position where, long nowMillis, IdleReminder settings,
                              boolean deliveryThrows) {
            return IdleReminderRules.advance(previous, where, nowMillis, settings,
                    () -> Optional.of("step"),
                    step -> {
                        delivered.add(nowMillis);
                        if (deliveryThrows) {
                            throw new IllegalStateException("malformed template");
                        }
                    },
                    this::record);
        }

        private static State earnable() {
            return new State(ORIGIN, 0L, IdleReminderRules.NEVER_REMINDED);
        }

        @Test
        @DisplayName("an evaluation that throws is reported as EVALUATE, and nothing is sent")
        void throwingEvaluationIsDistinguished() {
            State next = IdleReminderRules.advance(earnable(), ORIGIN, 15_000L, settings(true, 15, 10),
                    () -> {
                        throw new IllegalStateException("evaluate broke");
                    },
                    step -> delivered.add(15_000L),
                    this::record);

            assertEquals(List.of(Stage.EVALUATE), stages,
                    "a progression fault is not reported as a bad idle-reminder.message");
            assertEquals(List.of(), delivered);
            assertEquals(15_000L, next.lastReminderMillis(), "and the attempt still pays the cooldown");
        }

        @Test
        @DisplayName("a send that throws is reported as SEND")
        void throwingSendIsDistinguished() {
            advance(earnable(), ORIGIN, 15_000L, settings(true, 15, 10), true);

            assertEquals(List.of(Stage.SEND), stages);
        }

        @Test
        @DisplayName("a failure report that itself throws cannot carry the stamped state away")
        void throwingReportIsContained() {
            State next = IdleReminderRules.advance(earnable(), ORIGIN, 15_000L, settings(true, 15, 10),
                    () -> Optional.of("step"),
                    step -> {
                        throw new IllegalStateException("malformed template");
                    },
                    (stage, thrown) -> {
                        throw new IllegalStateException("the logger broke too");
                    });

            // Before the report was guarded this threw out of advance, the engine never stored the
            // stamp, and the next poll a second later tried -- and failed -- all over again.
            assertEquals(15_000L, next.lastReminderMillis());
        }

        @Test
        @DisplayName("a StackOverflowError from the send is contained like any other failure")
        void stackOverflowIsContained() {
            State next = IdleReminderRules.advance(earnable(), ORIGIN, 15_000L, settings(true, 15, 10),
                    () -> Optional.of("step"),
                    step -> {
                        throw new StackOverflowError("template nested too deep");
                    },
                    this::record);

            assertEquals(List.of(Stage.SEND), stages);
            assertTrue(failures.get(0) instanceof StackOverflowError);
            assertEquals(15_000L, next.lastReminderMillis());
        }

        @Test
        @DisplayName("any other Error is not the reminder's to swallow")
        void otherErrorsPropagate() {
            assertThrows(OutOfMemoryError.class, () -> IdleReminderRules.advance(earnable(), ORIGIN,
                    15_000L, settings(true, 15, 10),
                    () -> Optional.of("step"),
                    step -> {
                        throw new OutOfMemoryError("not ours");
                    },
                    this::record));
        }

        @Test
        @DisplayName("a poll that earns nothing does not deliver and returns the poll's own state")
        void silentPollDeliversNothing() {
            State previous = new State(ORIGIN, 0L, IdleReminderRules.NEVER_REMINDED);

            State next = advance(previous, ORIGIN, 1_000L, settings(true, 15, 10), false);

            assertEquals(List.of(), delivered);
            assertEquals(List.of(), failures);
            assertSame(previous, next);
        }

        @Test
        @DisplayName("an earned poll delivers and returns a state stamped at that instant")
        void earnedPollStamps() {
            State next = advance(new State(ORIGIN, 0L, IdleReminderRules.NEVER_REMINDED),
                    ORIGIN, 15_000L, settings(true, 15, 10), false);

            assertEquals(List.of(15_000L), delivered);
            assertEquals(List.of(), failures);
            assertEquals(15_000L, next.lastReminderMillis());
            assertEquals(15_000L, next.stillSinceMillis());
        }

        @Test
        @DisplayName("a delivery that throws does not escape the poll, and the failure is reported")
        void throwingDeliveryIsContained() {
            State next = advance(new State(ORIGIN, 0L, IdleReminderRules.NEVER_REMINDED),
                    ORIGIN, 15_000L, settings(true, 15, 10), true);

            assertEquals(1, failures.size(), "the thrown exception is handed to onFailure");
            assertEquals("malformed template", failures.get(0).getMessage());
            assertEquals(15_000L, next.lastReminderMillis(), "and the stamp still lands");
        }

        @Test
        @DisplayName("a delivery that throws is still charged the cooldown, so the next poll is silent")
        void throwingDeliveryPaysTheCooldown() {
            IdleReminder settings = settings(true, 15, 10);
            State state = new State(ORIGIN, 0L, IdleReminderRules.NEVER_REMINDED);

            // The engine's loop, once a second, with a template that cannot be rendered. Before the
            // fix the stamp was written after delivery, so it never landed: every one of these polls
            // recomputed the same decision and threw again.
            for (long nowMillis = 15_000L; nowMillis <= 60_000L; nowMillis += 1_000L) {
                state = advance(state, ORIGIN, nowMillis, settings, true);
            }

            assertEquals(List.of(15_000L), delivered,
                    "one attempt in the first 45 seconds of standing still, not forty-six");
            assertEquals(1, failures.size(), "and one reported failure, not one per second");

            // The cooldown really is a cooldown: the next attempt is a full window later.
            assertFalse(IdleReminderRules.poll(state, ORIGIN, 615_000L - 1L, settings).remind());
            assertTrue(IdleReminderRules.poll(state, ORIGIN, 615_000L, settings).remind());
        }

        @Test
        @DisplayName("a delivery that says nothing is charged the cooldown just the same")
        void silentDeliveryStillStamps() {
            State next = IdleReminderRules.advance(
                    new State(ORIGIN, 0L, IdleReminderRules.NEVER_REMINDED), ORIGIN, 15_000L,
                    settings(true, 15, 10), Optional::empty, step -> delivered.add(15_000L),
                    this::record);

            assertEquals(List.of(), delivered, "nothing to say, so nothing is sent");
            assertEquals(15_000L, next.lastReminderMillis(),
                    "a player who has cleared every gate is not re-evaluated on every poll");
        }
    }

    /**
     * The floor under the failure log rate. The cooldown stamp alone limits failures to one per
     * {@code max(cooldown-minutes, stand-still-seconds)}, and {@code cooldown-minutes} may be 0.
     */
    @Nested
    @DisplayName("the failure log throttle")
    class FailureLog {

        @Test
        @DisplayName("the first failure is logged, with nothing dropped before it")
        void firstIsAdmitted() {
            FailureLogThrottle throttle = new FailureLogThrottle();

            assertEquals(OptionalLong.of(0L), throttle.admit(1_000L));
        }

        @Test
        @DisplayName("cooldown-minutes: 0 with stand-still-seconds: 1 logs once a minute, not once a second")
        void zeroCooldownIsFloored() {
            FailureLogThrottle throttle = new FailureLogThrottle();
            IdleReminder settings = settings(true, 1, 0);
            State state = new State(ORIGIN, 0L, IdleReminderRules.NEVER_REMINDED);
            List<Long> logged = new ArrayList<>();

            // The engine's loop with a template that always throws, for two minutes.
            for (long nowMillis = 1_000L; nowMillis <= 120_000L; nowMillis += 1_000L) {
                long at = nowMillis;
                state = IdleReminderRules.advance(state, ORIGIN, nowMillis, settings,
                        () -> Optional.of("step"),
                        step -> {
                            throw new IllegalStateException("malformed template");
                        },
                        (stage, thrown) -> throttle.admit(at).ifPresent(dropped -> logged.add(at)));
            }

            assertEquals(List.of(1_000L, 61_000L), logged,
                    "the cooldown does not limit the rate here, so the throttle has to");
        }

        @Test
        @DisplayName("the admitted line counts what was dropped since the last one")
        void droppedAreCounted() {
            FailureLogThrottle throttle = new FailureLogThrottle(60_000L);

            throttle.admit(0L);
            assertEquals(OptionalLong.empty(), throttle.admit(1_000L));
            assertEquals(OptionalLong.empty(), throttle.admit(59_999L));
            assertEquals(OptionalLong.of(2L), throttle.admit(60_000L));
            assertEquals(OptionalLong.empty(), throttle.admit(60_001L));
            assertEquals(OptionalLong.of(1L), throttle.admit(120_000L));
        }

        @Test
        @DisplayName("a wall clock corrected backwards does not silence the log until it catches up")
        void backwardsClockAdmits() {
            FailureLogThrottle throttle = new FailureLogThrottle(60_000L);

            throttle.admit(900_000L);
            assertTrue(throttle.admit(1_000L).isPresent());
        }

        @Test
        @DisplayName("an interval below one millisecond is refused")
        void zeroIntervalRefused() {
            assertThrows(IllegalArgumentException.class, () -> new FailureLogThrottle(0L));
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
