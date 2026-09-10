package com.ninja6.antispeedrun.listeners;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.progression.IdleReminderEngine;

/**
 * Arms and disarms the idle reminder around a session. Two events, nothing else.
 *
 * <p>The file #4 names, and deliberately almost empty: every decision the idle reminder makes lives
 * in {@code IdleReminderRules}, and the timer and delivery live in {@link IdleReminderEngine}. What a
 * listener is for here is the two moments the engine cannot observe for itself.
 *
 * <p><strong>There is no {@code PlayerMoveEvent} handler, here or anywhere in this plugin.</strong>
 * That is #4's first acceptance criterion, and {@code PlayerMoveEventSweepTest} asserts it across the
 * whole of {@code src/main/java} rather than leaving it to review. Standing still is detected by the
 * engine's per-player {@code EntityScheduler} poll; the reasoning, and finding R-07's resolution of
 * the conflict with #26, are in {@link IdleReminderEngine}.
 *
 * <p>Players already online when the plugin enables never fire {@code PlayerJoinEvent} for this
 * listener, so {@code AntiSpeedrunPlugin} arms them explicitly — on the same path that re-primes
 * every online player after an {@code /asr reload}, which is also the only thing that can turn
 * {@code idle-reminder.enabled} on or off under a live session.
 */
public final class PlayerIdleListener implements Listener {

    private final AntiSpeedrunPlugin plugin;
    private final IdleReminderEngine engine;

    public PlayerIdleListener(AntiSpeedrunPlugin plugin, IdleReminderEngine engine) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * {@code MONITOR} because this only observes: it cancels nothing and changes nothing another
     * plugin could care about.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        engine.refresh(event.getPlayer(), plugin.configuration());
    }

    /**
     * {@code MONITOR} and unconditional: the cancel must happen however the quit was handled.
     *
     * <p>Ordering against {@code ProgressionListener.onQuit}, which clears every registered
     * per-player row including this engine's, is not guaranteed — both are {@code MONITOR} on the
     * same event. It does not need to be. If this runs second the row is already gone and the disarm
     * is a no-op; the task is then retired by Folia with the entity, and the poll would in any case
     * see {@code isOnline() == false} and cancel itself within one second. Finding R-08 records that
     * the quit-time cancel is the redundant half and the retired callback is the load-bearing one.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        engine.disarm(event.getPlayer().getUniqueId());
    }
}
