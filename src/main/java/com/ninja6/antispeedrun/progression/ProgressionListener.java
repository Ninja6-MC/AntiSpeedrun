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
 *       weeks ago, and arms the {@link UnlockWatch} if anything is outstanding on time alone.</li>
 *   <li>{@code PlayerAdvancementDoneEvent} — the only moment a player's advancement set changes.
 *       Invalidates the cached snapshot and announces any gate that has just opened. Both happen on
 *       the player's own region thread, which is where the event fires, so no cross-region work is
 *       involved.</li>
 *   <li>{@code PlayerQuitEvent} — cancels the watch, then drops every per-player map entry. Finding
 *       R-08: without this the maps grow for the lifetime of the server.</li>
 * </ul>
 *
 * <h2>The Folia dispatch thread for join — #68, item 7</h2>
 *
 * {@link #onJoin} captures a snapshot, and the package contract requires a capture to run on the
 * region that owns the player. What Folia guarantees, verbatim from its {@code README.md} section
 * <em>Thread contexts for API</em>, is: <q>Events involving a single entity (i.e player
 * breaks/places block) are called on the region owning entity.</q> {@code PlayerJoinEvent} is a
 * single-entity event, so the rule covers it and the capture is legal.
 *
 * <p>What is worth being precise about is that Folia does <strong>not</strong> name
 * {@code PlayerJoinEvent} in that document, so this rests on the general rule rather than on a
 * statement about this event, and the same README lists <q>some player login API</q> among the
 * currently broken API. The rule is the right thing to rely on — it is Folia's own normative
 * statement about the class of event this belongs to — but it is a class-level guarantee, not a
 * case-level one, and the distinction is recorded here rather than glossed. Note also that the
 * login <em>pipeline</em> before the event does begin on the global region, because no region owns
 * a player who is not yet in a world; ownership transfers to the region holding their spawn chunk
 * before their tick loop, and therefore before this event, runs. {@code PlayerQuitEvent} raises the
 * same question and is safe either way, since {@link ProgressionManager#forget} takes only a
 * {@code UUID}.
 *
 * <p>This listener does not gate anything. Dimension entry (#34, #35), item pickup (#11) and the
 * commands (#40) own their own listeners and call {@link ProgressionManager} from them.
 */
public final class ProgressionListener implements Listener {

    private final AntiSpeedrunPlugin plugin;
    private final ProgressionManager progression;
    private final UnlockWatch watch;

    public ProgressionListener(AntiSpeedrunPlugin plugin, ProgressionManager progression) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.progression = Objects.requireNonNull(progression, "progression");
        this.watch = new UnlockWatch(plugin, plugin::configuration, progression);
    }

    /**
     * The time-based unlock watch this listener arms.
     *
     * <p>Exposed so {@code /asr reload} can {@link UnlockWatch#refresh} each online player where it
     * already re-primes them: a reload can turn an advancement-driven gate into a time-driven one,
     * and until the watch is refreshed nothing would arm for it until that player's next join or
     * next advancement.
     */
    public UnlockWatch watch() {
        return watch;
    }

    /**
     * {@code MONITOR} because this only observes: it cancels nothing and changes nothing another
     * plugin could care about.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PluginConfig config = plugin.configuration();
        progression.primeUnlocks(event.getPlayer(), config);
        watch.refresh(event.getPlayer(), config);
    }

    /**
     * Invalidate first, then evaluate, so the announcement is decided against the advancement that
     * has just been granted rather than against the snapshot taken before it.
     *
     * <p>The watch is refreshed afterwards: earning an advancement is what turns a gate that was
     * waiting on two things into one waiting on a duration alone, which is the moment a watch needs
     * arming — and, symmetrically, an advancement can be the last thing a time-armed gate needed,
     * in which case the announcement above has just made the watch redundant.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        PluginConfig config = plugin.configuration();
        progression.invalidate(event.getPlayer().getUniqueId());
        progression.announceNewUnlocks(event.getPlayer(), config);
        watch.refresh(event.getPlayer(), config);
    }

    /**
     * {@code MONITOR} and unconditional: state cleanup must happen however the quit was handled.
     *
     * <p>Disarm before forgetting. {@link ProgressionManager#forget} clears the registry row that
     * holds the task handle, so doing it the other way round would drop the handle and leave the
     * task to be cleaned up by its retired callback alone — which does happen, but only when Folia
     * gets round to retiring the entity, and only for the scheduler's own bookkeeping.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        watch.disarm(event.getPlayer().getUniqueId());
        progression.forget(event.getPlayer().getUniqueId());
    }
}
