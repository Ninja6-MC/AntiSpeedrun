package com.ninja6.antispeedrun.progression;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * Keeps the progression cache and the per-player state honest across a session.
 *
 * <p>Three events, one job each:
 *
 * <ul>
 *   <li>{@code PlayerJoinEvent} — records which gates the player already satisfies, silently, so
 *       the first advancement of the session does not congratulate them on a gate they cleared
 *       weeks ago.</li>
 *   <li>{@code PlayerAdvancementDoneEvent} — the only moment a player's advancement set changes.
 *       Invalidates the cached snapshot and announces any gate that has just opened. Both happen on
 *       the player's own region thread, which is where the event fires, so no cross-region work is
 *       involved.</li>
 *   <li>{@code PlayerQuitEvent} — drops every per-player map entry. Finding R-08: without this the
 *       maps grow for the lifetime of the server.</li>
 * </ul>
 *
 * <p>This listener does not gate anything. Dimension entry (#34, #35), item pickup (#11) and the
 * commands (#40) own their own listeners and call {@link ProgressionManager} from them.
 */
public final class ProgressionListener implements Listener {

    private final AntiSpeedrunPlugin plugin;
    private final ProgressionManager progression;

    public ProgressionListener(AntiSpeedrunPlugin plugin, ProgressionManager progression) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.progression = Objects.requireNonNull(progression, "progression");
    }

    /**
     * {@code MONITOR} because this only observes: it cancels nothing and changes nothing another
     * plugin could care about.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PluginConfig config = plugin.configuration();
        progression.primeUnlocks(event.getPlayer(), config);
    }

    /**
     * Invalidate first, then evaluate, so the announcement is decided against the advancement that
     * has just been granted rather than against the snapshot taken before it.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        PluginConfig config = plugin.configuration();
        progression.invalidate(event.getPlayer().getUniqueId());
        progression.announceNewUnlocks(event.getPlayer(), config);
    }

    /**
     * {@code MONITOR} and unconditional: state cleanup must happen however the quit was handled.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        progression.forget(event.getPlayer().getUniqueId());
    }
}
