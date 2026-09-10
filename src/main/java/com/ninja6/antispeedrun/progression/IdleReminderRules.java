package com.ninja6.antispeedrun.progression;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.ninja6.antispeedrun.config.PluginConfig.IdleReminder;

/**
 * Every decision the idle reminder makes, with no Bukkit type anywhere in the signature.
 *
 * <p>The shape {@link MilestoneEvaluator} and {@code DimensionGateRules} set: the decision is a pure
 * function and {@link IdleReminderEngine} is the thin wiring that reads the player, calls in here and
 * acts on the answer. Two questions live here — <em>should this player be reminded now?</em> and
 * <em>what does the reminder say?</em> — and both are answerable from values alone, which is what
 * lets the whole of #4's behaviour be tested without booting Paper or Folia.
 *
 * <h2>Standing still without {@code PlayerMoveEvent}</h2>
 *
 * Issue #4 forbids {@code PlayerMoveEvent} outright, and finding R-07 resolved the conflict with #26
 * in this task's favour. The event fires several times per player per tick on a busy server and
 * exists here only to answer "has this player moved", which a periodic comparison of two positions
 * answers just as well for a fraction of the cost. So the engine polls, and this class holds the
 * comparison.
 *
 * <p>The anchor is deliberately <strong>not</strong> re-seeded on a poll that finds the player still.
 * Re-seeding would compare each poll only against the previous one, so a player drifting less than
 * {@link #MOVEMENT_THRESHOLD_BLOCKS} per poll — a boat on ice, a slow water current, a piston-fed
 * AFK farm — would read as motionless forever while crossing the world. Keeping the position the
 * still-clock started from means the threshold is a total displacement, not a per-poll one.
 */
public final class IdleReminderRules {

    /**
     * How far a player may drift from the anchor and still count as standing still, in blocks.
     *
     * <p>Not zero, and not tiny. A player who is genuinely stationary reports an unchanging location,
     * but one standing on a slab edge, riding a stationary boat, or being nudged by a mob reports
     * sub-block jitter that no player would call movement. Half a block is inside the hitbox and well
     * below anything an operator would describe as "went somewhere".
     */
    public static final double MOVEMENT_THRESHOLD_BLOCKS = 0.5D;

    /** {@link #MOVEMENT_THRESHOLD_BLOCKS} squared, so the comparison needs no square root. */
    public static final double MOVEMENT_THRESHOLD_SQUARED =
            MOVEMENT_THRESHOLD_BLOCKS * MOVEMENT_THRESHOLD_BLOCKS;

    /**
     * The literal placeholder {@code config.yml} documents in {@code idle-reminder.message}.
     *
     * <p>It is not MiniMessage syntax, so it cannot be resolved by a tag resolver as it stands.
     * {@link #template(String)} rewrites it into {@link #NEXT_STEP_TAG} before the message is parsed.
     */
    public static final String NEXT_STEP_PLACEHOLDER = "{NEXT_STEP}";

    /** The MiniMessage tag {@link #NEXT_STEP_PLACEHOLDER} is rewritten to. */
    public static final String NEXT_STEP_TAG = "next_step";

    /**
     * How often the action bar is re-sent while a reminder is on screen, in ticks.
     *
     * <p>The vanilla client holds action bar text for about 60 ticks and spends the last of those
     * fading it out, so an operator asking for {@code display-duration-seconds: 5} gets three seconds
     * and a fade unless something re-sends. Two seconds is comfortably inside the fade and costs at
     * most two extra packets per reminder.
     */
    public static final long ACTION_BAR_REFRESH_TICKS = 40L;

    /** Ticks per second, for turning configured seconds into scheduler delays. */
    public static final long TICKS_PER_SECOND = 20L;

    /**
     * The {@code lastReminderMillis} of a player who has not been reminded this session.
     *
     * <p>A sentinel rather than {@code 0}: the cooldown is an elapsed-time subtraction, and zero is a
     * real instant that a test clock — or a server whose clock has been corrected — can legitimately
     * sit near. {@link #cooldownElapsed} tests the sentinel by identity before it subtracts anything,
     * so no arithmetic is ever performed on it.
     */
    public static final long NEVER_REMINDED = Long.MIN_VALUE;

    private IdleReminderRules() {
    }

    /**
     * Where a player is, in the only terms the idle check needs.
     *
     * @param world the world's unique id; a change of world is movement however close the coordinates
     * @param x     block x
     * @param y     block y
     * @param z     block z
     */
    public record Position(UUID world, double x, double y, double z) {
        public Position {
            Objects.requireNonNull(world, "world");
        }
    }

    /**
     * What the engine remembers about one player between polls.
     *
     * @param anchor             where the player was when the still-clock started
     * @param stillSinceMillis   when they were last seen to have moved, or joined
     * @param lastReminderMillis when they were last reminded, or {@link #NEVER_REMINDED}
     */
    public record State(Position anchor, long stillSinceMillis, long lastReminderMillis) {
        public State {
            Objects.requireNonNull(anchor, "anchor");
        }
    }

    /**
     * The outcome of one poll: the state to store, and whether the player has earned a reminder.
     *
     * <p>{@link #state()} deliberately does <em>not</em> record a reminder even when
     * {@link #remind()} is true. Whether one actually goes out depends on there being something to
     * say, which needs the player's progression and so cannot be decided here. The caller stamps the
     * outcome with {@link #reminded(State, long)} once it knows.
     */
    public record Decision(State state, boolean remind) {
        public Decision {
            Objects.requireNonNull(state, "state");
        }
    }

    /** The state a player starts on: standing where they are, never yet reminded. */
    public static State begin(Position where, long nowMillis) {
        return new State(Objects.requireNonNull(where, "where"), nowMillis, NEVER_REMINDED);
    }

    /**
     * Whether {@code now} is far enough from {@code anchor} to count as having moved.
     *
     * <p>A different world always counts, whatever the coordinates say: two worlds share a coordinate
     * space and a player who has changed dimension has unambiguously gone somewhere.
     */
    public static boolean moved(Position anchor, Position now) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(now, "now");
        if (!anchor.world().equals(now.world())) {
            return true;
        }
        double dx = now.x() - anchor.x();
        double dy = now.y() - anchor.y();
        double dz = now.z() - anchor.z();
        return dx * dx + dy * dy + dz * dz > MOVEMENT_THRESHOLD_SQUARED;
    }

    /**
     * One poll of one player.
     *
     * <p>Four outcomes, in order:
     *
     * <ol>
     *   <li><strong>Moved.</strong> The still-clock restarts from the new position. Nothing else is
     *       disturbed — in particular the cooldown survives, because it governs how often a player is
     *       spoken to and not how often they stand still.</li>
     *   <li><strong>The clock went backwards.</strong> {@code stillSinceMillis} is in the future,
     *       which means the wall clock was corrected between two polls. Restart the still-clock
     *       rather than report a negative duration, which would otherwise read as "not still yet" for
     *       however far back the clock jumped.</li>
     *   <li><strong>Still, but not for long enough, or too soon after the last reminder.</strong>
     *       Nothing to do; the anchor and both timestamps carry forward unchanged.</li>
     *   <li><strong>Still long enough, and the cooldown has elapsed.</strong>
     *       {@link Decision#remind()} is true.</li>
     * </ol>
     *
     * <p>{@code settings.enabled()} is tested here as well, so that this function is total over the
     * settings it is handed rather than carrying an unstated precondition. It is <em>not</em>
     * reachable with {@code false} from production: {@link IdleReminderEngine#refresh} never arms a
     * disabled reminder, and a poll that finds the feature switched off under an already-armed task
     * cancels itself and drops the row before it gets here. So the engine does not track position
     * while disabled — it disarms and forgets, and a re-enable starts a fresh stand-still window
     * from {@link #begin}. The branch exists for a caller that polls a disabled section anyway.
     *
     * @param previous what the last poll left behind
     * @param now      where the player is at this poll
     * @param nowMillis wall clock at this poll
     * @param settings the {@code idle-reminder} section of the snapshot the caller is holding
     */
    public static Decision poll(State previous, Position now, long nowMillis, IdleReminder settings) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(settings, "settings");

        if (moved(previous.anchor(), now)) {
            return new Decision(new State(now, nowMillis, previous.lastReminderMillis()), false);
        }
        if (nowMillis < previous.stillSinceMillis()) {
            return new Decision(
                    new State(previous.anchor(), nowMillis, previous.lastReminderMillis()), false);
        }

        boolean earned = settings.enabled()
                && nowMillis - previous.stillSinceMillis() >= seconds(settings.standStillSeconds())
                && cooldownElapsed(previous.lastReminderMillis(), nowMillis, settings);
        return new Decision(previous, earned);
    }

    /**
     * The side of one poll that needs a player: look the next step up and say it.
     *
     * <p>Separated from {@link #advance} so that the ordering rule below — the cooldown stamp is
     * earned by the <em>attempt</em>, not by its success — is testable without a Bukkit type
     * anywhere near it. {@link IdleReminderEngine} supplies the one implementation.
     */
    @FunctionalInterface
    public interface Delivery {

        /** Delivers the reminder, if there is one to deliver. May throw; {@link #advance} expects it. */
        void deliver();
    }

    /**
     * One whole poll: decide, deliver if earned, and return the state the caller must store.
     *
     * <p>The ordering here is the point, and it is a decision rather than wiring, so it lives in
     * this class rather than in the engine.
     *
     * <p><strong>The cooldown stamp is earned by the attempt.</strong> {@link #reminded} is applied
     * before {@code delivery} runs, and the returned state carries the stamp whether the delivery
     * succeeded, said nothing, or threw. Stamping afterwards is what a straightforward reading of
     * this code would do, and it is wrong in a way that only shows up when something goes wrong: a
     * delivery that throws would leave {@code lastReminderMillis} at {@link #NEVER_REMINDED} and
     * {@code stillSinceMillis} untouched, so the very next poll — one second later — would recompute
     * the identical decision and throw again, every second for as long as the player stood there,
     * instead of once per {@code cooldown-minutes}. The cooldown is the only thing keeping the
     * expensive half of the poll off the hot path, and stamping late bypasses it in exactly the case
     * where the poll is already failing.
     *
     * <p><strong>A throwing delivery cannot end the poll.</strong> Whatever {@code delivery} throws
     * is handed to {@code onFailure} and not rethrown — and the hand-off is itself guarded, so a
     * {@code onFailure} that throws cannot end the poll either. That second guard is #115's first
     * finding: with the report outside the guarded region, a failing logger propagated out of this
     * method, {@link IdleReminderEngine#tick} never stored the state returned below, and the
     * once-a-second loop the stamp ordering exists to close reopened.
     *
     * <p>It was worth settling what the scheduler actually does here rather than guessing, because
     * the two answers have very different costs. Read from the implementation —
     * {@code paper-server/src/main/java/io/papermc/paper/threadedregions/scheduler/FoliaEntityScheduler.java},
     * {@code EntityScheduledTask#accept} — a repeating entity task whose body throws is
     * <em>not</em> cancelled: the call is wrapped in {@code try}, {@link Throwable} is caught and
     * logged as "Entity task for &lt;plugin&gt; generated an exception", and the {@code finally}
     * block reschedules unless the task was cancelled or the entity retired. So the armed row never
     * holds a dead handle and a player is not silently dropped for the session. What the task does
     * not survive intact is the log: one stack trace per poll, which at a one-second period is once
     * a second for as long as the player stands there. Catching here is therefore about the rate and
     * about not depending on a scheduler internal, not about rescuing a task that would otherwise
     * die.
     *
     * <p>Both halves are belt to the braces of validating {@code idle-reminder.message} at config
     * load, which is where a template MiniMessage refuses is reported to the operator. That
     * validation is narrower than it sounds — the lenient MiniMessage instance swallows most bad
     * markup and renders it as text — so this is what covers everything it does not model, the send
     * itself included.
     *
     * @param previous  what the last poll left behind
     * @param now       where the player is at this poll
     * @param nowMillis wall clock at this poll
     * @param settings  the {@code idle-reminder} section of the snapshot the caller is holding
     * @param delivery  runs only when the poll earns a reminder
     * @param onFailure told what {@code delivery} threw, if it threw
     * @return the state to store, stamped if a reminder was attempted
     */
    public static State advance(State previous, Position now, long nowMillis, IdleReminder settings,
                                Delivery delivery, Consumer<RuntimeException> onFailure) {
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(onFailure, "onFailure");

        Decision decision = poll(previous, now, nowMillis, settings);
        if (!decision.remind()) {
            return decision.state();
        }

        // Stamped whether or not anything is said, and before anything is said. The cooldown then
        // governs how often this player is looked at as well as how often they are spoken to, which
        // keeps a player who has cleared every gate -- nextStep empty, nothing to deliver -- from
        // being re-evaluated on every poll for as long as they stand there.
        State next = reminded(decision.state(), nowMillis);
        try {
            delivery.deliver();
        } catch (RuntimeException thrown) {
            // Guarded too, and this is #115's first finding rather than belt and braces. onFailure
            // is a log call, so it throwing is unlikely -- but an unguarded accept propagates out
            // of advance, IdleReminderEngine#tick then never stores the stamped state, and the
            // once-a-second loop this method's whole ordering exists to close is back. There is
            // nowhere to report a failure of the reporting channel to, so it is dropped: the poll
            // returning a stamped state is worth more than a line nothing can write anyway.
            try {
                onFailure.accept(thrown);
            } catch (RuntimeException ignored) {
                // Deliberately empty; see above.
            }
        }
        return next;
    }

    /**
     * The state to store once a reminder has actually been decided upon.
     *
     * <p>Restarts the still-clock as well as stamping the cooldown, so a player who never moves again
     * has to earn each subsequent reminder with a fresh stand-still window rather than being handed
     * one the instant the cooldown expires.
     */
    public static State reminded(State state, long nowMillis) {
        Objects.requireNonNull(state, "state");
        return new State(state.anchor(), nowMillis, nowMillis);
    }

    /**
     * Whether {@code cooldown-minutes} has elapsed since the last reminder.
     *
     * <p>True for a player who has never been reminded, and true for any cooldown of zero, which the
     * configuration reader permits and which means "no cooldown at all".
     */
    public static boolean cooldownElapsed(long lastReminderMillis, long nowMillis,
                                          IdleReminder settings) {
        Objects.requireNonNull(settings, "settings");
        if (lastReminderMillis == NEVER_REMINDED) {
            return true;
        }
        if (nowMillis < lastReminderMillis) {
            // The wall clock was corrected backwards. Treat the cooldown as spent rather than
            // silencing the player until the clock catches up, which could be arbitrarily long.
            return true;
        }
        return nowMillis - lastReminderMillis >= seconds(settings.cooldownMinutes() * 60L);
    }

    /**
     * The operator's message with {@link #NEXT_STEP_PLACEHOLDER} rewritten to a MiniMessage tag.
     *
     * <p>Substituting the next step into the string directly would let it be parsed as markup, and
     * the step's text interpolates advancement keys read straight from {@code config.yml}. Rewriting
     * the placeholder into a tag and resolving that tag with an unparsed placeholder makes the
     * injected half literal text permanently, while leaving the operator's own half as the markup
     * they wrote — the same split {@link ProgressionManager#UNLOCK_ANNOUNCEMENT} makes, and the one
     * {@code DimensionGateRules} documents for the rejection hint.
     */
    public static String template(String configured) {
        return Objects.requireNonNull(configured, "configured")
                .replace(NEXT_STEP_PLACEHOLDER, "<" + NEXT_STEP_TAG + ">");
    }

    /**
     * The tick delays at which an action bar reminder must be re-sent to stay up for
     * {@code displayDurationSeconds}.
     *
     * <p>Empty for any duration the client already covers on its own, so the common case schedules
     * nothing. Every delay returned is at least 1, because Folia rejects a zero delay — the same
     * constraint finding R-08 records for {@code runAtFixedRate}, and it applies to a delayed task
     * just as it does to a repeating one.
     *
     * @param displayDurationSeconds {@code idle-reminder.display-duration-seconds}
     * @return delays in ticks from the first send, ascending; never contains 0
     */
    public static List<Long> actionBarRefreshDelays(int displayDurationSeconds) {
        List<Long> delays = new ArrayList<>();
        long total = displayDurationSeconds * TICKS_PER_SECOND;
        for (long at = ACTION_BAR_REFRESH_TICKS; at < total; at += ACTION_BAR_REFRESH_TICKS) {
            delays.add(at);
        }
        return List.copyOf(delays);
    }

    /** One milestone paired with the verdict on it, so {@link #nextStep} needs no evaluator. */
    public record MilestoneProgress(Milestone milestone, EligibilityResult result) {
        public MilestoneProgress {
            Objects.requireNonNull(milestone, "milestone");
            Objects.requireNonNull(result, "result");
        }
    }

    /**
     * What to put in {@code {NEXT_STEP}}: the first milestone in configured order that this player
     * has not cleared, and what it is still waiting on.
     *
     * <p>Empty when every milestone is cleared, which the engine reads as "say nothing". A reminder
     * whose next step is blank would be a nag with no content, and the player it would go to is
     * precisely the one who has finished.
     *
     * <p>Also empty for a milestone that is outstanding but has nothing actionable — every remaining
     * requirement waived because the server cannot resolve it. {@link EligibilityResult} already
     * treats those as satisfied, so such a milestone cannot in fact be outstanding; the guard is
     * there so that a future requirement kind this method does not know how to describe produces
     * silence rather than a milestone name followed by nothing.
     */
    public static Optional<String> nextStep(List<MilestoneProgress> progress) {
        Objects.requireNonNull(progress, "progress");
        for (MilestoneProgress entry : progress) {
            if (entry.result().eligible()) {
                continue;
            }
            Optional<String> outstanding = outstanding(entry.result());
            if (outstanding.isEmpty()) {
                continue;
            }
            return Optional.of(entry.milestone().displayName() + " - " + outstanding.get());
        }
        return Optional.empty();
    }

    /**
     * What one verdict is still waiting on, phrased for the player rather than the operator.
     *
     * <p>Public because it is the half of {@link #nextStep} that {@code /progress} needs per row:
     * the card names every milestone and what each is short of, where the reminder names only the
     * first. Both go through this method on purpose. An idle reminder and a card that disagreed
     * about what a player owes a gate would be a real bug and a confusing one, and the way that
     * happens is a second implementation written because the first returned the wrong shape.
     *
     * @return the clauses joined with "and", or empty when nothing outstanding is actionable
     * @see #nextStep(List)
     */
    public static Optional<String> outstanding(EligibilityResult result) {
        List<String> clauses = clauses(Objects.requireNonNull(result, "result"));
        return clauses.isEmpty() ? Optional.empty() : Optional.of(String.join(" and ", clauses));
    }

    /** The outstanding requirements of one verdict, in the order a player would tackle them. */
    private static List<String> clauses(EligibilityResult result) {
        List<String> clauses = new ArrayList<>(3);
        if (!result.missingAdvancements().isEmpty()) {
            clauses.add("earn " + String.join(", ", result.missingAdvancements()));
        }
        if (result.missingPlaytimeHours() > 0.0D) {
            clauses.add(hours(result.missingPlaytimeHours()) + " more playtime");
        }
        if (result.missingAccountAgeDays() > 0) {
            clauses.add(result.missingAccountAgeDays()
                    + (result.missingAccountAgeDays() == 1 ? " more day" : " more days")
                    + " on this server");
        }
        return clauses;
    }

    /**
     * A remaining playtime figure a player can read.
     *
     * <p>Rendered in minutes below the hour, because "0.1 hours" is a worse answer than "6 minutes"
     * to somebody deciding whether to wait. {@link Locale#ROOT} so a server running under a
     * comma-decimal locale does not produce a different string from the one the tests pin.
     */
    private static String hours(double remaining) {
        if (remaining < 1.0D) {
            long minutes = Math.max(1L, Math.round(remaining * 60.0D));
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return String.format(Locale.ROOT, "%.1f hours", remaining);
    }

    /** Whole seconds as milliseconds. */
    private static long seconds(long count) {
        return count * 1000L;
    }
}
