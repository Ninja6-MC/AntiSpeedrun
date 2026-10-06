package com.ninja6.antispeedrun.progression;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The one path a change to a player's personal credits takes (#216): the recorder, the furnace
 * loader and {@code /asr credit} all call {@link #changed} after the store reports a change.
 *
 * <p>A credit is what opens a protected gate now, not the vanilla advancement, so
 * {@code PlayerAdvancementDoneEvent} no longer marks that moment. This does what that event does
 * for an advancement, through the same {@link ProgressionListener#refreshUnlocks}: drop the cached
 * snapshot, announce any gate that has just opened, and re-arm the time watch.
 *
 * <h2>Threads</h2>
 *
 * The invalidation runs at once, on whatever thread recorded the credit. It is a remove on a
 * thread-safe map, legal anywhere, and it is what makes the gate answer from the new record on its
 * very next check. The announcement evaluates the player, which only the region that owns them may
 * do, so it runs:
 *
 * <ul>
 *   <li><strong>inline</strong> when this thread owns the player: a block broken, a pickaxe crafted,
 *       a blaze killed in the player's own region. The announcement lands in the tick the credit was
 *       recorded;</li>
 *   <li><strong>on the player's own scheduler</strong> otherwise: loot generated or a furnace
 *       finishing in another region, or the async command. The task drops the snapshot again before
 *       announcing, since the owner may have captured one while the store was being written;</li>
 *   <li><strong>not at all</strong> for a player who is offline, or whose scheduler is already
 *       retired. The next join announces it: {@link ProgressionManager#announceUnlocksClearedWhileAway}
 *       announces every gate the player's persisted record has not told them about, which covers a
 *       gate opened while they were away by any means.</li>
 * </ul>
 */
public final class CreditRefresh {

    /** The server operations the refresh needs. {@link #bukkit} in production. */
    public interface Host {

        /** The online player with this id, or {@code null}. Any thread. */
        Player online(UUID player);

        /** Whether the calling thread owns {@code player}'s region. Any thread. */
        boolean ownedHere(Player player);

        /**
         * Runs {@code task} on {@code player}'s own scheduler.
         *
         * @return {@code false} if the player's scheduler is retired and the task will never run
         */
        boolean schedule(Player player, Runnable task);
    }

    private final Host host;
    private final Consumer<UUID> invalidate;
    private final Consumer<Player> refreshUnlocks;

    /**
     * @param host           the server
     * @param invalidate     drops a player's cached snapshot; legal from any thread
     * @param refreshUnlocks invalidates, announces and re-arms; only on the player's own region
     */
    public CreditRefresh(Host host, Consumer<UUID> invalidate, Consumer<Player> refreshUnlocks) {
        this.host = Objects.requireNonNull(host, "host");
        this.invalidate = Objects.requireNonNull(invalidate, "invalidate");
        this.refreshUnlocks = Objects.requireNonNull(refreshUnlocks, "refreshUnlocks");
    }

    /** The production host: {@code plugin}'s server and each player's {@code EntityScheduler}. */
    public static Host bukkit(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return new Host() {
            @Override
            public Player online(UUID player) {
                return plugin.getServer().getPlayer(player);
            }

            @Override
            public boolean ownedHere(Player player) {
                return plugin.getServer().isOwnedByCurrentRegion(player);
            }

            @Override
            public boolean schedule(Player player, Runnable task) {
                return player.getScheduler().run(plugin, scheduled -> task.run(), null) != null;
            }
        };
    }

    /**
     * Refreshes {@code player}'s gates after their credits changed. Any thread, online or offline.
     * Call it once per change, after the store has published it.
     */
    public void changed(UUID player) {
        Objects.requireNonNull(player, "player");
        invalidate.accept(player);
        Player online = host.online(player);
        if (online == null) {
            return;
        }
        if (host.ownedHere(online)) {
            refreshUnlocks.accept(online);
            return;
        }
        host.schedule(online, () -> {
            if (online.isOnline()) {
                refreshUnlocks.accept(online);
            }
        });
    }
}
