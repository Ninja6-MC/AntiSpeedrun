package com.ninja6.antispeedrun.progression;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.ninja6.antispeedrun.config.PluginConfig;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Announces the gate that opens because time passed, and nothing else did.
 *
 * <p>{@link ProgressionManager#announceNewUnlocks} is driven by {@code PlayerAdvancementDoneEvent},
 * which is the right trigger for every gate whose last outstanding requirement is an advancement.
 * A gate down to {@code require-playtime-hours} or {@code require-account-age-days} has no such
 * moment: the requirement is satisfied by the clock, the clock raises no event, and the player is
 * told nothing until they happen to earn some unrelated advancement — possibly hours later,
 * possibly never. This class is that missing trigger.
 *
 * <h2>Why this is not a sweeper</h2>
 *
 * The obvious implementation — one repeating task walking the online players — is illegal on Folia
 * and is the cross-region read finding C-07 names: re-evaluating a player means capturing a
 * snapshot, and a capture reads {@code Player#getStatistic} and {@code Player#getAdvancementProgress},
 * which may only be touched from the region that owns that player. {@link ProgressionCache} argues
 * the same point for cache eviction and reaches the same conclusion. So there is no global task
 * here and no task on the plugin scheduler.
 *
 * <p>What there is instead is a task on the player's own {@code EntityScheduler}, which Folia runs
 * on whichever region owns them at the time and moves with them across regions. It is armed only
 * while {@link ProgressionManager#awaitingTimeBasedUnlock} holds — that is, while some gate this
 * player has not been told about is outstanding on a duration and nothing else — and it cancels
 * itself the moment that stops being true. On the shipped configuration, where both durations are
 * {@code 0} on both gates, the condition never holds and no task is ever created: the cost of this
 * class on a default server is one evaluation on join, against a snapshot the join already
 * captured.
 *
 * <h2>The R-08 contract</h2>
 *
 * <ul>
 *   <li>A <strong>retired callback</strong> is supplied. Folia runs it when the player leaves or is
 *       otherwise removed, and it drops this class's handle on them. Without it the handle map is
 *       exactly the map that grows for the lifetime of the server.</li>
 *   <li>The <strong>initial delay is at least 1</strong>. Folia rejects a zero or negative delay on
 *       {@code runAtFixedRate}, and there is nothing useful to do on tick zero in any case: the
 *       caller has just evaluated this player, which is how the task came to be armed.</li>
 *   <li>Every write the task performs goes through {@link ProgressionManager}, whose insertion
 *       points check {@code isOnline()} — a scheduled task being precisely the caller that can
 *       outlive its player.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * {@link #refresh} evaluates, so it inherits the package rule: call it from a context that owns the
 * player. The task body runs on the player's own region thread by construction. {@link #disarm} takes
 * only a {@link UUID} and is safe from anywhere.
 */
public final class UnlockWatch {

    /**
     * How often an armed watch re-checks, in ticks. One minute, matching
     * {@link ProgressionCache#DEFAULT_TIME_TO_LIVE}: requirements are configured in whole hours and
     * whole days, so a minute already resolves them an order of magnitude more finely than they can
     * be expressed, and checking faster would only re-capture snapshots the cache would have to
     * throw away anyway.
     */
    public static final long DEFAULT_PERIOD_TICKS = 1200L;

    /**
     * Folia rejects an initial delay below 1 on {@code runAtFixedRate}. There is no reason to want
     * one here; see the class javadoc.
     */
    private static final long INITIAL_DELAY_TICKS = 1L;

    private final Plugin plugin;
    private final Supplier<PluginConfig> configuration;
    private final ProgressionManager progression;
    private final long periodTicks;

    /**
     * The armed task per player, so a second {@link #refresh} does not stack a second task and
     * {@link #disarm} can cancel one. Registered with the same {@link PlayerStateRegistry} as every
     * other per-player map, so the row cannot outlive the player.
     *
     * <p>Registration is <strong>not</strong> a substitute for {@link #disarm}, and this is the one
     * registered map for which that distinction matters. {@link PlayerStateRegistry#forget} drops
     * the row; it does not cancel what the row holds, and a {@code ScheduledTask} is a live resource
     * rather than a value. So any caller reaching {@code forget} — the quit listener via
     * {@link ProgressionManager#forget}, or a path added later — must disarm first, which is why
     * {@link #disarm}'s contract says so and why {@code ProgressionListener.onQuit} is ordered the
     * way it is. Nothing here leaks if that ordering is missed: {@link #tick} re-checks
     * {@code isOnline} and cancels itself within one period, and the retired callback drops the row
     * in any case. The registry is not given a general cancel-on-removal hook for one map's sake; if
     * a second map ever holds a cancellable resource, that hook is the fix rather than a second
     * comment like this one.
     */
    private final PlayerStateMap<ScheduledTask> armed;

    /**
     * @param plugin        the owning plugin, for the scheduler
     * @param configuration reads the live configuration snapshot; the task calls it once per run,
     *                      per the contract in {@code com.ninja6.antispeedrun.config}, rather than
     *                      capturing a snapshot that would straddle every reload for the rest of
     *                      the session
     * @param progression   the manager that evaluates and announces
     */
    public UnlockWatch(Plugin plugin, Supplier<PluginConfig> configuration,
                       ProgressionManager progression) {
        this(plugin, configuration, progression, DEFAULT_PERIOD_TICKS);
    }

    /** As above, with an explicit period. For tests and for a server that wants a finer grain. */
    public UnlockWatch(Plugin plugin, Supplier<PluginConfig> configuration,
                       ProgressionManager progression, long periodTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.progression = Objects.requireNonNull(progression, "progression");
        if (periodTicks < 1L) {
            throw new IllegalArgumentException("periodTicks must be at least 1, was " + periodTicks);
        }
        this.periodTicks = periodTicks;
        this.armed = progression.state().register("progression-unlock-watches");
    }

    /**
     * Arms or cancels this player's watch to match what they are currently waiting on.
     *
     * <p>Idempotent, and cheap enough to call from any handler that has just changed what a player
     * is waiting on: join, an advancement, and {@code /asr reload} once the reload path re-primes.
     * It evaluates, so it must run on the player's own region thread.
     */
    public void refresh(Player player, PluginConfig config) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(config, "config");

        UUID id = player.getUniqueId();
        if (!progression.awaitingTimeBasedUnlock(player, config)) {
            // Covers the offline case too: awaitingTimeBasedUnlock is false for a player who has
            // quit, so a watch armed for them is cancelled here rather than left to the retired
            // callback alone.
            disarm(id);
            return;
        }
        if (armed.contains(id)) {
            return;
        }

        // The retired callback has to name the task it is retiring, and Folia wants the callback
        // before it hands the handle back. The holder closes that circle: the callback removes the
        // row only while it still holds *this* task, so a retirement that lands after the player has
        // rejoined and armed a fresh one cannot drop the new handle. Plain field semantics would do
        // -- the write below happens-before any region thread runs the callback -- but an
        // AtomicReference says that rather than leaving a reader to reconstruct it.
        AtomicReference<ScheduledTask> retiring = new AtomicReference<>();
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin,
                scheduled -> tick(player, scheduled),
                () -> {
                    ScheduledTask retired = retiring.get();
                    if (retired != null) {
                        armed.remove(id, retired);
                    }
                    // Null only if retirement beats the assignment below, in which case nothing has
                    // been stored under this id yet and there is nothing to remove. The store then
                    // fails the isOnline check on the next tick, or on the quit that retired it.
                },
                INITIAL_DELAY_TICKS, periodTicks);
        retiring.set(task);
        if (task == null) {
            // Folia returns null when the entity has already been retired -- the player quit
            // between the evaluation above and this call. Nothing to arm and nothing to clean up.
            return;
        }
        if (armed.putIfAbsent(id, task).isPresent()) {
            // Cannot happen while callers honour the threading contract, since a player's watch is
            // only ever touched from their own region thread. Cheap to be right about anyway.
            task.cancel();
        }
    }

    /**
     * Cancels this player's watch if one is armed. Idempotent.
     *
     * <p>Call it from {@code PlayerQuitEvent} <em>before</em> {@link ProgressionManager#forget},
     * which clears the handle without cancelling the task behind it.
     */
    public void disarm(UUID player) {
        armed.remove(Objects.requireNonNull(player, "player")).ifPresent(ScheduledTask::cancel);
    }

    /** Whether a watch is currently armed for this player. Diagnostics and tests. */
    public boolean isArmed(UUID player) {
        return armed.contains(Objects.requireNonNull(player, "player"));
    }

    /** How many players currently have a watch armed. For {@code /asr inspect}. */
    public int armedCount() {
        return armed.size();
    }

    /**
     * One check. Runs on the player's own region thread, which is what makes the capture inside
     * {@code announceNewUnlocks} legal.
     */
    private void tick(Player player, ScheduledTask self) {
        UUID id = player.getUniqueId();
        if (!player.isOnline()) {
            armed.remove(id, self);
            self.cancel();
            return;
        }

        PluginConfig config = configuration.get();
        // Playtime has advanced since the last capture by definition -- that is the whole reason
        // this task exists -- so the held snapshot cannot answer the question being asked.
        progression.invalidate(id);
        progression.announceNewUnlocks(player, config);

        if (!progression.awaitingTimeBasedUnlock(player, config)) {
            armed.remove(id, self);
            self.cancel();
        }
    }
}
