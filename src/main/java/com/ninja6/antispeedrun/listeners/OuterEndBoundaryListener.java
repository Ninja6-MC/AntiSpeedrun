package com.ninja6.antispeedrun.listeners;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.util.Vector;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.PlayerStateMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Holds players inside {@code anti-cheese.outer-end-radius} of the origin in an End until that
 * world's first dragon has been killed (#26, Task 7.2.1).
 *
 * <p>Off by default: {@code anti-cheese.block-gateway-pre-dragon} ships {@code false}.
 *
 * <h2>Two mechanisms</h2>
 *
 * A player's own pearl or chorus fruit is refused at {@link PlayerTeleportEvent}, so they lose
 * nothing but the throw. Everything else that gets a player across, such as bridging, Elytra, Wind
 * Charge jumps, flying machines or a command, is caught by a bounds poll on each player's own
 * {@code EntityScheduler}, never by a move event (finding R-07). The poll remembers the last
 * position found inside and sends a crosser back to it.
 *
 * <h2>Folia</h2>
 *
 * <ul>
 *   <li>The poll is driven from the global region, which only hands each player to their own
 *       scheduler; every position read and teleport happens there. Repositioning is
 *       {@code teleportAsync} (finding R-09).
 *   <li>{@code hasBeenPreviouslyKilled()} belongs to the region owning chunk {@code (0, 0)}, so it
 *       is read only from a task on that region and cached in a {@code volatile} per world (finding
 *       C-06). Every other thread reads the cache. A world seen killed stays unlocked, so the outer
 *       End unlocks permanently. The primary dragon is held until its extra dragons are dead, so
 *       the flag does not flip early (finding C-05).
 * </ul>
 */
public final class OuterEndBoundaryListener implements Listener {

    /** The standing exemption, shared with the rest of section 7. */
    public static final String BYPASS_PERMISSION = EyeThrowListener.BYPASS_PERMISSION;

    private static final long TICKS_PER_SECOND = 20L;
    private static final long FEEDBACK_COOLDOWN_MILLIS = 3_000L;

    private static final Component REFUSAL = Component.text(
            "The outer End is sealed until the Ender Dragon has been defeated.",
            NamedTextColor.RED);

    private final AntiSpeedrunPlugin plugin;

    /** Per End world: has its first dragon been killed. Written only on the {@code (0, 0)} region. */
    private final Map<UUID, Flag> flags = new ConcurrentHashMap<>();

    /** The last position found inside the boundary. Registered, so quit clears it. */
    private final PlayerStateMap<Location> lastInside;

    private final PlayerStateMap<Long> lastFeedback;

    public OuterEndBoundaryListener(AntiSpeedrunPlugin plugin) {
        this.plugin = plugin;
        this.lastInside = plugin.playerState().register("outer-end-last-inside");
        this.lastFeedback = plugin.playerState().register("outer-end-feedback");
        for (World world : plugin.getServer().getWorlds()) {
            track(world);
        }
        // Ticks once a second and skips cycles, so a reload that changes outer-end-poll-seconds
        // applies without re-registering the task.
        AtomicInteger elapsed = new AtomicInteger();
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            PluginConfig config = plugin.configuration();
            if (!OuterEndBoundaryRules.armed(config)
                    || elapsed.incrementAndGet() < config.antiCheese().outerEndPollSeconds()) {
                return;
            }
            elapsed.set(0);
            for (World world : plugin.getServer().getWorlds()) {
                refresh(world);
            }
            for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
                player.getScheduler().run(plugin, scheduled -> poll(player), null);
            }
        }, TICKS_PER_SECOND, TICKS_PER_SECOND);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        track(event.getWorld());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        flags.remove(event.getWorld().getUID());
    }

    /**
     * Refuses a pearl or chorus fruit that would land beyond the boundary. A single-player event on
     * the player's region; it reads the volatile cache and the configuration, nothing else.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!OuterEndBoundaryRules.selfInflicted(event.getCause().name())) {
            return;
        }
        Location to = event.getTo();
        PluginConfig config = plugin.configuration();
        if (to == null || to.getWorld() == null || !OuterEndBoundaryRules.armed(config)) {
            return;
        }
        Player player = event.getPlayer();
        if (!OuterEndBoundaryRules.refuses(locked(to.getWorld()), to.getX(), to.getZ(),
                config.antiCheese().outerEndRadius()) || exempt(player)) {
            return;
        }
        event.setCancelled(true);
        notify(player);
    }

    /** On the player's own region: remembers where they stood, or sends them back. */
    private void poll(Player player) {
        PluginConfig config = plugin.configuration();
        Location at = player.getLocation();
        World world = at.getWorld();
        if (!OuterEndBoundaryRules.armed(config) || world == null
                || world.getEnvironment() != World.Environment.THE_END) {
            lastInside.remove(player.getUniqueId());
            return;
        }
        if (exempt(player)) {
            return;
        }
        int radius = config.antiCheese().outerEndRadius();
        Location remembered = lastInside.get(player.getUniqueId()).orElse(null);
        if (remembered != null && !world.equals(remembered.getWorld())) {
            remembered = null;
        }
        switch (OuterEndBoundaryRules.poll(locked(world), at.getX(), at.getZ(), radius,
                remembered != null)) {
            case FORGET -> lastInside.remove(player.getUniqueId());
            case REMEMBER -> lastInside.put(player.getUniqueId(), at.clone());
            case RETURN_TO_LAST -> sendBack(player, remembered);
            case RETURN_TO_EDGE -> {
                double[] edge = OuterEndBoundaryRules.edge(at.getX(), at.getZ(), radius);
                Location target = at.clone();
                target.setX(edge[0]);
                target.setZ(edge[1]);
                sendBack(player, target);
            }
        }
    }

    private void sendBack(Player player, Location target) {
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(moved -> {
            if (moved) {
                player.getScheduler().run(plugin, task -> {
                    player.setVelocity(new Vector(0, 0, 0));
                    player.setFallDistance(0.0F);
                    notify(player);
                }, null);
            }
        });
    }

    private boolean exempt(Player player) {
        boolean waived = player.hasPermission(BYPASS_PERMISSION)
                || plugin.bypasses().hasBypass(player, System.currentTimeMillis());
        return !OuterEndBoundaryRules.applies(player.getGameMode().name(), waived);
    }

    private void notify(Player player) {
        long now = System.currentTimeMillis();
        long last = lastFeedback.getOrDefault(player.getUniqueId(), 0L);
        if (!ItemGateRules.shouldNotify(now, last, FEEDBACK_COOLDOWN_MILLIS)) {
            return;
        }
        lastFeedback.put(player.getUniqueId(), now);
        player.sendActionBar(REFUSAL);
    }

    /** Whether the boundary is up in this world: an End whose first dragon has not been killed. */
    private boolean locked(World world) {
        if (world.getEnvironment() != World.Environment.THE_END) {
            return false;
        }
        Flag flag = flags.get(world.getUID());
        return flag == null || !flag.killed;
    }

    private void track(World world) {
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        flags.computeIfAbsent(world.getUID(), id -> new Flag());
        refresh(world);
    }

    /** Schedules a read of the battle on the region that owns it. */
    private void refresh(World world) {
        Flag flag = flags.get(world.getUID());
        if (flag == null || flag.latched) {
            return;
        }
        plugin.getServer().getRegionScheduler().execute(plugin, world, 0, 0, () -> {
            DragonBattle battle = world.getEnderDragonBattle();
            // No battle means no fight to gate, so the world is open, but that is not latched: a
            // battle that is only unavailable for now must not unlock the world for good.
            boolean killed = battle != null && battle.hasBeenPreviouslyKilled();
            flag.killed = killed || battle == null;
            flag.latched = killed;
        });
    }

    /** One world's cached answer. Written only by the {@code (0, 0)} region task. */
    private static final class Flag {
        volatile boolean killed;

        /** Set once a kill has been seen; never cleared, so the outer End stays open. */
        volatile boolean latched;
    }
}
