package com.ninja6.antispeedrun.progression;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PluginConfig.IdleReminder;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;

/**
 * Tells a player who has stopped what to do next, on their own region's scheduler and nowhere else.
 *
 * <p>Task 2.2.1 (#4). The wiring half of the feature: {@link IdleReminderRules} decides whether to
 * speak and what to say, and this class is what reads the player, runs the timer and delivers the
 * message. It is the same split, and for the same reason, as {@link MilestoneEvaluator} against
 * {@link ProgressionManager}.
 *
 * <h2>Why there is no global tick loop — and no {@code PlayerMoveEvent}</h2>
 *
 * The two obvious implementations are both wrong here, and each is wrong in its own way:
 *
 * <ul>
 *   <li>A {@code BukkitScheduler} repeating task walking the online players is illegal on Folia.
 *       Reading a player's location, sending them a message and evaluating their progression are all
 *       owned by the region that owns them, and one task on one thread cannot own every region. This
 *       is finding C-07's shape exactly, and {@link UnlockWatch} and {@link ProgressionCache} both
 *       argue it at length. So there is no global task here, and nothing in this package touches
 *       {@code BukkitScheduler} at all.</li>
 *   <li>A {@code PlayerMoveEvent} listener is what #4's acceptance criteria forbid outright. It
 *       fires several times per player per tick and every one of those firings exists, for this
 *       feature, to answer a question a once-a-second position comparison answers just as well.
 *       Finding R-07 records that #26 was amended to poll rather than have this task concede.</li>
 * </ul>
 *
 * What there is instead is one task per player on their own {@code EntityScheduler}, which Folia runs
 * on whichever region owns them at the time and which follows them across worlds and regions without
 * this class knowing that happened. The whole of the cross-region problem is delegated to the
 * scheduler that was built for it.
 *
 * <h2>The R-08 contract</h2>
 *
 * <ul>
 *   <li>The five-argument {@code runAtFixedRate} is used, with a <strong>retired callback</strong>.
 *       Folia runs it when the player is removed, and it is where this class's rows are dropped —
 *       not {@code PlayerQuitEvent}, which R-08 notes is redundant for the task itself and is only
 *       ever a best-effort early cancel here.</li>
 *   <li>{@code initialDelay} and {@code period} are both at least 1. Folia throws
 *       {@link IllegalArgumentException} at registration otherwise, and there is nothing to do on
 *       tick zero: a player who has just been armed has by definition not stood still yet.</li>
 *   <li>Both per-player maps are {@link PlayerStateMap}s obtained from the shared
 *       {@link PlayerStateRegistry}, so they are {@code ConcurrentHashMap}-backed (#51) and are
 *       cleared by the one quit hook rather than by a cleanup path this class has to remember.</li>
 * </ul>
 *
 * <h2>Nothing here opens a file</h2>
 *
 * The tick reads the player's location, evaluates through {@link ProgressionManager} — which reads
 * statistics and advancement progress from memory, and caches — and sends a message. No store, no
 * persistence, no {@code config.yml}. The configuration arrives through a {@link Supplier} that reads
 * the live snapshot, so a task armed before an {@code /asr reload} picks the new snapshot up on its
 * next tick instead of holding a stale one for the rest of the session.
 *
 * <h2>Threading</h2>
 *
 * {@link #refresh} reads nothing region-owned and is safe from anywhere, but every caller has the
 * player to hand on their own thread anyway. The task body runs on the player's region by
 * construction. {@link #disarm} takes only a {@link UUID} and is safe from anywhere.
 */
public final class IdleReminderEngine {

    /**
     * How often an armed engine looks at the player, in ticks.
     *
     * <p>One second. {@code stand-still-seconds} is configured in whole seconds and has a floor of 1,
     * so this resolves it exactly; anything finer would burn packets to answer a question nobody
     * asked more precisely. The work per poll is a location read and two {@code double} subtractions
     * — this is the cheapest thing in the plugin that runs on a timer.
     */
    public static final long DEFAULT_POLL_PERIOD_TICKS = IdleReminderRules.TICKS_PER_SECOND;

    /** Folia rejects an initial delay below 1 on {@code runAtFixedRate} — finding R-08. */
    private static final long INITIAL_DELAY_TICKS = 1L;

    /** Title fade in and out, when {@code display-type} is {@code TITLE}. */
    private static final Duration TITLE_FADE = Duration.ofMillis(500L);

    private final Plugin plugin;
    private final Supplier<PluginConfig> configuration;
    private final ProgressionManager progression;
    private final Supplier<Long> clock;
    private final long periodTicks;

    /**
     * The armed poll task per player. Registered with the shared {@link PlayerStateRegistry}, so the
     * row cannot outlive the player.
     *
     * <p>As {@link UnlockWatch} records for its own handle map: registration drops the row, it does
     * not cancel what the row holds, so {@link #disarm} exists and quit ordering matters. Nothing
     * leaks if that ordering is missed — {@link #tick} re-checks {@code isOnline} and cancels itself
     * within one period, which here is one second, and the retired callback drops the row regardless.
     */
    private final PlayerStateMap<ScheduledTask> armed;

    /** Where each armed player was last seen, and when they were last spoken to. */
    private final PlayerStateMap<IdleReminderRules.State> tracked;

    /**
     * @param plugin        the owning plugin, for the scheduler
     * @param configuration reads the live configuration snapshot; the task calls it once per poll,
     *                      per the contract in {@code com.ninja6.antispeedrun.config}
     * @param progression   the manager the next-goal text is evaluated through
     */
    public IdleReminderEngine(Plugin plugin, Supplier<PluginConfig> configuration,
                              ProgressionManager progression) {
        this(plugin, configuration, progression, System::currentTimeMillis, DEFAULT_POLL_PERIOD_TICKS);
    }

    /** As above, with an explicit clock and poll period. For tests and for a finer grain. */
    public IdleReminderEngine(Plugin plugin, Supplier<PluginConfig> configuration,
                              ProgressionManager progression, Supplier<Long> clock, long periodTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.progression = Objects.requireNonNull(progression, "progression");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (periodTicks < 1L) {
            throw new IllegalArgumentException("periodTicks must be at least 1, was " + periodTicks);
        }
        this.periodTicks = periodTicks;
        this.armed = progression.state().register("progression-idle-reminder-tasks");
        this.tracked = progression.state().register("progression-idle-reminder-state");
    }

    /**
     * Arms or cancels this player's poll to match {@code idle-reminder.enabled}.
     *
     * <p>Idempotent. Called on join, for players already online when the plugin enables, and for
     * every online player after an {@code /asr reload} — which is the only thing that can switch the
     * feature off under an armed task, or back on under a player who was never armed.
     */
    public void refresh(Player player, PluginConfig config) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(config, "config");

        UUID id = player.getUniqueId();
        if (!config.idleReminder().enabled() || !player.isOnline()) {
            disarm(id);
            return;
        }
        if (armed.contains(id)) {
            return;
        }

        // Same shape as UnlockWatch#refresh: the retired callback has to be able to name the task it
        // is retiring, and Folia wants the callback before it hands the handle back, so the holder
        // closes the circle. Removing only while the row still holds *this* task means a retirement
        // landing after the player has rejoined cannot drop the new handle.
        //
        // The still-clock is dropped under the *same* guard, and not unconditionally. Both rows
        // belong to one session, so a retirement arriving after a fast quit-and-rejoin must either
        // clear both of the old session's rows or neither of the new session's; clearing tracked
        // regardless would wipe the anchor the rejoin has already started standing on and cost that
        // player a whole stand-still window. A retirement that finds the row already replaced has
        // nothing of its own left to drop -- the rejoin's refresh took the row -- and quit cleanup
        // is the registry's anyway (R-08), so declining to remove leaks nothing.
        AtomicReference<ScheduledTask> retiring = new AtomicReference<>();
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin,
                scheduled -> tick(player, scheduled),
                () -> {
                    ScheduledTask retired = retiring.get();
                    if (retired != null && armed.remove(id, retired)) {
                        tracked.remove(id);
                    }
                },
                INITIAL_DELAY_TICKS, periodTicks);
        retiring.set(task);
        if (task == null) {
            // Folia returns null when the entity has already been retired -- the player quit between
            // the check above and this call. Nothing armed, nothing to clean up.
            return;
        }
        if (armed.putIfAbsent(id, task).isPresent()) {
            // Unreachable while callers honour the threading contract, since a player's own region
            // thread is the only one that touches their row. Cheap to be right about anyway.
            task.cancel();
        }
    }

    /**
     * Cancels this player's poll if one is armed, and forgets where they were standing. Idempotent.
     *
     * <p>Call it from {@code PlayerQuitEvent} <em>before</em> {@link ProgressionManager#forget},
     * which clears the row that holds the task handle without cancelling the task behind it. Finding
     * R-08 notes that this quit-time cancel is redundant as far as Folia is concerned — it retires
     * the task with the entity — and it is kept because it is not redundant as far as this class's
     * own rows are concerned when a retirement is slow to arrive.
     */
    public void disarm(UUID player) {
        Objects.requireNonNull(player, "player");
        armed.remove(player).ifPresent(ScheduledTask::cancel);
        tracked.remove(player);
    }

    /** Whether a poll is currently armed for this player. Diagnostics and tests. */
    public boolean isArmed(UUID player) {
        return armed.contains(Objects.requireNonNull(player, "player"));
    }

    /** How many players currently have a poll armed. For {@code /asr inspect}. */
    public int armedCount() {
        return armed.size();
    }

    /**
     * One poll. Runs on the player's own region thread, which is what makes the location read, the
     * progression evaluation and the message send below all legal.
     */
    private void tick(Player player, ScheduledTask self) {
        UUID id = player.getUniqueId();
        if (!player.isOnline()) {
            standDown(id, self);
            return;
        }

        PluginConfig config = configuration.get();
        IdleReminder settings = config.idleReminder();
        if (!settings.enabled()) {
            // Switched off by a reload under a task that was armed when it was on. Stand down rather
            // than poll a disabled feature for the rest of the session; the reload's own re-prime
            // re-arms every online player if it is switched back on.
            standDown(id, self);
            return;
        }

        long now = clock.get();
        IdleReminderRules.Position where = positionOf(player);
        IdleReminderRules.State previous = tracked.get(id)
                .orElseGet(() -> IdleReminderRules.begin(where, now));

        // The stamp-before-delivery ordering, and the fact that a throwing delivery cannot end the
        // poll, are both IdleReminderRules#advance's -- they are decisions about when the cooldown
        // is earned, not wiring, and they are unit-tested there without a Bukkit type in sight.
        IdleReminderRules.State next = IdleReminderRules.advance(previous, where, now, settings,
                () -> IdleReminderRules.nextStep(progressOf(player, config))
                        .ifPresent(step -> deliver(player, settings, step)),
                thrown -> reportDeliveryFailure(player, thrown));
        tracked.put(id, next);
    }

    /**
     * Cancels this poll and drops the rows it owns.
     *
     * <p>The still-clock goes only if the task row was still {@code self}, for the reason
     * {@link #refresh}'s retired callback gives: a poll that finds its own row already replaced is
     * looking at a session that has ended, and the anchor in {@code tracked} now belongs to the
     * rejoin. The cancel is unconditional either way — {@code self} has no business running on.
     */
    private void standDown(UUID id, ScheduledTask self) {
        if (armed.remove(id, self)) {
            tracked.remove(id);
        }
        self.cancel();
    }

    /**
     * Logs a delivery that threw, once per {@code cooldown-minutes} per player rather than per poll.
     *
     * <p>The rate is what {@link IdleReminderRules#advance} buys: the cooldown is stamped before the
     * delivery is attempted, so a template that throws produces one line per cooldown window and not
     * one per second. {@code idle-reminder.message} is validated at config load, so reaching here at
     * all means either a MiniMessage failure mode the validation does not model or a fault in the
     * send itself; both are worth a line, and neither is worth the player's poll.
     */
    private void reportDeliveryFailure(Player player, RuntimeException thrown) {
        plugin.getLogger().log(Level.WARNING,
                "idle-reminder: could not deliver the reminder to " + player.getName()
                        + "; check idle-reminder.message in config.yml", thrown);
    }

    /** The player's position, in the Bukkit-free terms {@link IdleReminderRules} compares. */
    private static IdleReminderRules.Position positionOf(Player player) {
        Location at = player.getLocation();
        return new IdleReminderRules.Position(
                at.getWorld().getUID(), at.getX(), at.getY(), at.getZ());
    }

    /**
     * Every dimension milestone with the verdict on it, in configured order.
     *
     * <p>Evaluation goes through {@link ProgressionManager}, so it reuses the cached snapshot rather
     * than re-deriving eligibility or re-reading the server. On the shipped configuration that is two
     * comparisons against a capture the join already paid for.
     */
    private List<IdleReminderRules.MilestoneProgress> progressOf(Player player, PluginConfig config) {
        List<Milestone> milestones = Milestone.dimensionGates(config);
        List<IdleReminderRules.MilestoneProgress> progress = new ArrayList<>(milestones.size());
        for (Milestone milestone : milestones) {
            progress.add(new IdleReminderRules.MilestoneProgress(
                    milestone, progression.evaluate(player, config, milestone)));
        }
        return progress;
    }

    /**
     * Sends one reminder, in whichever of the three shapes {@code display-type} names.
     *
     * <p>The operator's message is MiniMessage and is deserialised as markup; the next step is
     * resolved through an unparsed placeholder, so advancement keys read from {@code config.yml}
     * cannot be parsed as tags. See {@link IdleReminderRules#template(String)}.
     */
    private void deliver(Player player, IdleReminder settings, String step) {
        Component message = MiniMessage.miniMessage().deserialize(
                IdleReminderRules.template(settings.message()),
                Placeholder.unparsed(IdleReminderRules.NEXT_STEP_TAG, step));

        switch (settings.displayType()) {
            case ACTIONBAR -> sendActionBar(player, message, settings.displayDurationSeconds());
            case TITLE -> player.showTitle(Title.title(Component.empty(), message,
                    Title.Times.times(TITLE_FADE,
                            Duration.ofSeconds(settings.displayDurationSeconds()), TITLE_FADE)));
            // display-duration-seconds has no meaning in chat: the line stays in the log until it
            // scrolls, and there is no way to withdraw it.
            case CHAT -> player.sendMessage(message);
        }
    }

    /**
     * Sends an action bar and keeps it up for {@code display-duration-seconds}.
     *
     * <p>The client holds action bar text for about three seconds and fades it, so honouring a
     * longer configured duration means re-sending. The re-sends are one-shot delayed tasks on the
     * player's own {@code EntityScheduler} — the same scheduler the poll runs on, so they stay with
     * the player across a region change — each with a null retired callback, because a player who is
     * gone before one runs needs nothing done. {@code isOnline} covers the same case for one that
     * lands during the quit.
     */
    private void sendActionBar(Player player, Component message, int displayDurationSeconds) {
        player.sendActionBar(message);
        for (long delay : IdleReminderRules.actionBarRefreshDelays(displayDurationSeconds)) {
            player.getScheduler().runDelayed(plugin, task -> {
                if (player.isOnline()) {
                    player.sendActionBar(message);
                }
            }, null, delay);
        }
    }
}
