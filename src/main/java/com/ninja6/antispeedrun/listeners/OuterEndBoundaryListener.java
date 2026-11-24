package com.ninja6.antispeedrun.listeners;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

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
import com.ninja6.antispeedrun.storage.PersonalCredit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Holds players inside {@code anti-cheese.outer-end-radius} of the origin in an End: everyone until
 * that world's first dragon has been killed (#26, Task 7.2.1), and each player until they hold the
 * {@code dragon-fight} credit (#278). {@link OuterEndBoundaryRules} decides which rule applies.
 *
 * <p>The world rule ships off ({@code anti-cheese.block-gateway-pre-dragon: false}); the personal
 * rule ships on ({@code anti-cheese.outer-end-requires-dragon-fight: true}).
 *
 * <h2>Two mechanisms</h2>
 *
 * A player's own pearl or chorus fruit is refused at {@link PlayerTeleportEvent}, so they lose
 * nothing but the throw. Everything else that gets a player across, such as bridging, Elytra, Wind
 * Charge jumps, flying machines, End gateways, a command or another plugin's teleport, and a login
 * out there, is caught by a bounds poll on each player's own {@code EntityScheduler}, never by a
 * move event (finding R-07). The poll remembers the last position found inside on standing ground
 * and sends a crosser back to it. With nothing remembered, it sends them to standing ground beside
 * the exit portal.
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
 *       End unlocks permanently for the world rule. The primary dragon is held until its extra
 *       dragons are dead, so the flag does not flip early (finding C-05).
 *   <li>The {@code dragon-fight} credit is one {@code volatile} read of the credit store, legal on
 *       any thread.
 *   <li>The ground beside the exit portal is searched by a task on the region owning chunk
 *       {@code (0, 0)}, after that chunk has been loaded, and the player is then moved from their
 *       own scheduler.
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

    private static final Component PERSONAL_REFUSAL = Component.text(
            "The outer End opens once you have helped defeat an Ender Dragon.",
            NamedTextColor.RED);

    private final AntiSpeedrunPlugin plugin;

    /** Per End world: has its first dragon been killed. Written only on the {@code (0, 0)} region. */
    private final Map<UUID, Flag> flags = new ConcurrentHashMap<>();

    /** The last position found inside the boundary. Registered, so quit clears it. */
    private final PlayerStateMap<Location> lastInside;

    private final PlayerStateMap<Long> lastFeedback;

    /** Players with a return to the dragon area in flight, so a slow chunk load is not repeated. */
    private final Set<UUID> returning = ConcurrentHashMap.newKeySet();

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
            // Refreshed every second whether or not the rule is armed, so a kill that happens
            // while it is off is already cached when an operator turns it on.
            for (World world : plugin.getServer().getWorlds()) {
                refresh(world);
            }
            PluginConfig config = plugin.configuration();
            if (!OuterEndBoundaryRules.armed(config)
                    || elapsed.incrementAndGet() < config.antiCheese().outerEndPollSeconds()) {
                return;
            }
            elapsed.set(0);
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
        OuterEndBoundaryRules.Hold hold = hold(player, to.getWorld(), config);
        if (!OuterEndBoundaryRules.refuses(hold.held(), to.getX(), to.getZ(),
                config.antiCheese().outerEndRadius()) || exempt(player)) {
            return;
        }
        event.setCancelled(true);
        notify(player, hold);
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
        OuterEndBoundaryRules.Hold hold = hold(player, world, config);
        switch (OuterEndBoundaryRules.poll(hold.held(), at.getX(), at.getZ(), radius,
                remembered != null)) {
            case FORGET -> lastInside.remove(player.getUniqueId());
            case REMEMBER -> {
                // Only standing ground: between the main island and the radius there is mostly
                // void, so a position sampled mid-glide or mid-jump is no place to send anyone.
                if (OuterEndBoundaryRules.remembers(player.isOnGround(), player.isGliding(),
                        player.isFlying())) {
                    lastInside.put(player.getUniqueId(), at.clone());
                }
            }
            case RETURN_TO_LAST -> sendBack(player, remembered, hold);
            case RETURN_TO_DRAGON_AREA -> returnToDragonArea(player, world);
        }
    }

    private void sendBack(Player player, Location target, OuterEndBoundaryRules.Hold hold) {
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(moved -> {
            if (moved) {
                player.getScheduler().run(plugin, task -> {
                    player.setVelocity(new Vector(0, 0, 0));
                    player.setFallDistance(0.0F);
                    notify(player, hold);
                }, null);
            }
        });
    }

    /**
     * Sends a player found outside, with no position inside remembered, to standing ground in the
     * safe column beside the exit portal.
     *
     * <p>Three hops, each on the thread that owns what it touches: the column's chunk is loaded, the
     * ground is searched by a task on the region owning it, and the player is moved from their own
     * scheduler after checking that they are still held and still outside. The search reads only
     * loaded blocks that region owns ({@link ProgressionGateListener#terrainOf}). If it finds no
     * standing ground there, such as an island dug away, or the configured radius is too small to
     * contain the column, the player goes to the Overworld's spawn rather than to a guess in the End.
     */
    private void returnToDragonArea(Player player, World world) {
        UUID id = player.getUniqueId();
        if (!returning.add(id)) {
            return;
        }
        int chunkX = OuterEndBoundaryRules.SAFE_COLUMN_X >> 4;
        int chunkZ = OuterEndBoundaryRules.SAFE_COLUMN_Z >> 4;
        world.getChunkAtAsync(chunkX, chunkZ).whenComplete((chunk, failure) ->
                plugin.getServer().getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
                    SafeRetreat.Landing landing = SafeRetreat.spawnLanding(
                            OuterEndBoundaryRules.SAFE_COLUMN_X + 0.5D,
                            OuterEndBoundaryRules.SAFE_SEARCH_START_Y,
                            OuterEndBoundaryRules.SAFE_COLUMN_Z + 0.5D,
                            ProgressionGateListener.terrainOf(world), world.getMinHeight(),
                            world.getMaxHeight());
                    boolean scheduled = player.getScheduler().run(plugin,
                            task -> finishReturn(player, world, landing), () -> returning.remove(id)) != null;
                    if (!scheduled) {
                        returning.remove(id);
                    }
                }));
    }

    private void finishReturn(Player player, World world, SafeRetreat.Landing landing) {
        returning.remove(player.getUniqueId());
        PluginConfig config = plugin.configuration();
        Location at = player.getLocation();
        OuterEndBoundaryRules.Hold hold = hold(player, world, config);
        if (!OuterEndBoundaryRules.armed(config) || !world.equals(at.getWorld()) || exempt(player)
                || !hold.held() || !OuterEndBoundaryRules.outside(at.getX(), at.getZ(),
                        config.antiCheese().outerEndRadius())) {
            return;
        }
        // A radius under twelve blocks leaves the safe column outside it too.
        if (landing.retreated() && !OuterEndBoundaryRules.outside(landing.x(), landing.z(),
                config.antiCheese().outerEndRadius())) {
            sendBack(player, new Location(world, landing.x(), landing.y(), landing.z(),
                    at.getYaw(), at.getPitch()), hold);
            return;
        }
        World overworld = plugin.getServer().getWorlds().stream()
                .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
                .findFirst().orElse(null);
        if (overworld == null) {
            plugin.getLogger().warning("Cannot return " + player.getName() + " inside the outer End "
                    + "boundary of " + world.getName() + ": there is no standing ground beside its "
                    + "exit portal and no Overworld is loaded.");
            return;
        }
        plugin.getLogger().warning("No standing ground beside the exit portal of " + world.getName()
                + " inside outer-end-radius; sending " + player.getName()
                + " to the Overworld spawn instead.");
        player.teleportAsync(overworld.getSpawnLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN)
                .whenComplete((moved, error) -> {
                    if (error != null || !Boolean.TRUE.equals(moved)) {
                        plugin.getLogger().log(Level.WARNING, "Could not move " + player.getName()
                                + " out of the outer End.", error);
                    }
                });
    }

    private boolean exempt(Player player) {
        boolean waived = player.hasPermission(BYPASS_PERMISSION)
                || plugin.bypasses().hasBypass(player, System.currentTimeMillis());
        return !OuterEndBoundaryRules.applies(player.getGameMode().name(), waived);
    }

    private void notify(Player player, OuterEndBoundaryRules.Hold hold) {
        long now = System.currentTimeMillis();
        long last = lastFeedback.getOrDefault(player.getUniqueId(), 0L);
        if (!ItemGateRules.shouldNotify(now, last, FEEDBACK_COOLDOWN_MILLIS)) {
            return;
        }
        lastFeedback.put(player.getUniqueId(), now);
        player.sendActionBar(hold == OuterEndBoundaryRules.Hold.PERSONAL ? PERSONAL_REFUSAL : REFUSAL);
    }

    /** Which rule, if either, holds this player inside the boundary of {@code world}. */
    private OuterEndBoundaryRules.Hold hold(Player player, World world, PluginConfig config) {
        if (world.getEnvironment() != World.Environment.THE_END) {
            return OuterEndBoundaryRules.Hold.NONE;
        }
        Flag flag = flags.get(world.getUID());
        if (flag == null) {
            return OuterEndBoundaryRules.Hold.NONE;
        }
        boolean qualified = plugin.personalCredits()
                .has(player.getUniqueId(), PersonalCredit.DRAGON_FIGHT, false);
        return OuterEndBoundaryRules.hold(config, flag.known, flag.killed, flag.battle, qualified);
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
            flag.battle = battle != null;
            flag.latched = killed;
            flag.known = true;
        });
    }

    /** One world's cached answer. Written only by the {@code (0, 0)} region task. */
    private static final class Flag {
        volatile boolean killed;

        /**
         * Whether the battle has been read at least once. The read is a task on the region owning
         * chunk (0, 0), which Folia holds back while that chunk is unloaded, so until it runs the
         * answer is unknown and nobody is held or sent back.
         */
        volatile boolean known;

        /** Whether the world has a dragon battle. Without one neither rule holds anybody there. */
        volatile boolean battle;

        /**
         * Set once a kill has been seen; never cleared, so the outer End stays open under the world
         * rule. The battle is not read again after that, so {@link #battle} keeps its last value.
         */
        volatile boolean latched;
    }
}
