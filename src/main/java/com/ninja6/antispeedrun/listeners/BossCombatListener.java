package com.ninja6.antispeedrun.listeners;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataType;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

/**
 * Multi-dragon boss combat (Epic 6). This class currently holds the reinforcement window (#37,
 * Task 6.1.1); the rounding modes (#38), secondary AI and boss bars (#21), single-battle
 * reconciliation (#56), XP (#22) and the exit lock (#23) build on it.
 *
 * <h2>The reinforcement window</h2>
 *
 * The first player to enter an End whose dragon has never been killed opens a window of
 * {@code boss-scaling.battle-prep-seconds}. The primary dragon is not held: vanilla activates it on
 * entry and a solo player fights it at once. Players arriving during the window see the countdown.
 * When it reaches zero the party is counted on the main island and the secondary dragons it earns
 * are spawned, each tagged with {@link #SECONDARY_DRAGON_KEY}. The decisions are in
 * {@link DragonReinforcementRules}.
 *
 * <p>A window opens once per fight and only for the first one. Resummoned fights are Task 6.1.3
 * (#20), and a window's state is held in memory, so a restart mid-fight opens a new window on the
 * next entry.
 *
 * <h2>Folia</h2>
 *
 * Every step runs on the thread that owns what it touches (audit finding C-06):
 * <ul>
 *   <li>The countdown and every read of the {@link DragonBattle} run on the {@link
 *       org.bukkit.Server#getRegionScheduler() RegionScheduler} for chunk {@code (0, 0)}, which
 *       refreshes {@link ReinforcementWindow#previouslyKilled()}, the {@code volatile} that the
 *       entry handlers read from each player's own region.</li>
 *   <li>The census asks each online player on their own {@code EntityScheduler} whether they count,
 *       and joins the answers in a {@link CensusTally}. No player's location is read from another
 *       region.</li>
 *   <li>Titles and the action-bar countdown are sent on each recipient's own scheduler.</li>
 *   <li>Each secondary dragon is spawned by a task on the region owning its spawn location.</li>
 * </ul>
 */
public final class BossCombatListener implements Listener {

    /**
     * The persistent-data key marking a dragon this plugin spawned as a secondary. Stored as a byte
     * {@code 1}. Tasks 6.1.5 (#56) and 6.2.1 (#22) identify secondaries by it.
     */
    public static final String SECONDARY_DRAGON_KEY = "n6_asr_secondary_dragon";

    private static final long TICKS_PER_SECOND = 20L;

    private static final Title.Times TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(750));

    private final AntiSpeedrunPlugin plugin;
    private final NamespacedKey secondaryDragon;

    /** One window per End world, keyed by world UID. Removed when the world unloads. */
    private final Map<UUID, ReinforcementWindow> windows = new ConcurrentHashMap<>();

    public BossCombatListener(AntiSpeedrunPlugin plugin) {
        this.plugin = plugin;
        this.secondaryDragon = new NamespacedKey(plugin, SECONDARY_DRAGON_KEY);
    }

    /** The key {@link #SECONDARY_DRAGON_KEY} names under this plugin's namespace. */
    public NamespacedKey secondaryDragonKey() {
        return secondaryDragon;
    }

    /** Entry by portal, command or any other transit. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        entered(event.getPlayer().getWorld());
    }

    /** A player who logs out in the End and back in never changes world, but still arrives. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        entered(event.getPlayer().getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        windows.remove(event.getWorld().getUID());
    }

    /**
     * Opens the window if this is the first entry into a fight that has not had one. Runs on the
     * entering player's region, so it reads nothing of the battle: only the cached flag and the
     * phase, both safe from any thread.
     */
    private void entered(World world) {
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        PluginConfig config = plugin.configuration();
        if (!DragonReinforcementRules.armed(config)) {
            return;
        }
        ReinforcementWindow window = windows.computeIfAbsent(world.getUID(), id -> new ReinforcementWindow());
        if (!window.tryOpen()) {
            return;
        }
        int seconds = config.bossScaling().battlePrepSeconds();
        plugin.getServer().getRegionScheduler().execute(plugin, world, 0, 0,
                () -> begin(world, window, seconds));
    }

    /** On the {@code (0, 0)} region: confirms there is a first fight to reinforce, then counts down. */
    private void begin(World world, ReinforcementWindow window, int seconds) {
        if (!refresh(world, window)) {
            window.abandon();
            return;
        }
        if (seconds <= 0) {
            census(world, window);
            return;
        }
        broadcast(world, player -> {
            player.showTitle(Title.title(
                    Component.text("The Ender Dragon awakens", NamedTextColor.DARK_PURPLE),
                    Component.text("Reinforcements are counted in " + seconds + "s", NamedTextColor.GRAY),
                    TITLE_TIMES));
            player.sendActionBar(countdownLine(seconds));
        });
        AtomicInteger remaining = new AtomicInteger(seconds);
        plugin.getServer().getRegionScheduler().runAtFixedRate(plugin, world, 0, 0, task -> {
            if (!refresh(world, window)) {
                task.cancel();
                window.abandon();
                return;
            }
            int left = remaining.decrementAndGet();
            if (left <= 0) {
                task.cancel();
                census(world, window);
                return;
            }
            broadcast(world, player -> player.sendActionBar(countdownLine(left)));
        }, TICKS_PER_SECOND, TICKS_PER_SECOND);
    }

    /**
     * On the {@code (0, 0)} region: reads the battle and refreshes the cached flag.
     *
     * @return true while there is a first fight to reinforce
     */
    private boolean refresh(World world, ReinforcementWindow window) {
        DragonBattle battle = world.getEnderDragonBattle();
        boolean killed = battle == null || battle.hasBeenPreviouslyKilled();
        window.refreshPreviouslyKilled(killed);
        return !killed;
    }

    /**
     * Asks every online player, on their own scheduler, whether they count toward the party. The
     * total comes back to the {@code (0, 0)} region.
     */
    private void census(World world, ReinforcementWindow window) {
        List<? extends Player> online = List.copyOf(plugin.getServer().getOnlinePlayers());
        CensusTally tally = CensusTally.start(online.size(), counted ->
                plugin.getServer().getRegionScheduler().execute(plugin, world, 0, 0,
                        () -> resolve(world, window, counted)));
        for (Player player : online) {
            var scheduled = player.getScheduler().run(plugin, task -> {
                Location at = player.getLocation();
                tally.counted(DragonReinforcementRules.countsTowardParty(
                        world.equals(player.getWorld()), player.isDead(),
                        player.getGameMode().name(), at.getX(), at.getZ()));
            }, tally::missed);
            if (scheduled == null) {
                tally.missed();
            }
        }
    }

    /** On the {@code (0, 0)} region: turns the count into dragons and dispatches each spawn. */
    private void resolve(World world, ReinforcementWindow window, int partySize) {
        if (!window.resolve()) {
            return;
        }
        // The primary may have died during the window; the fight is then over and needs no help.
        if (!refresh(world, window)) {
            return;
        }
        PluginConfig config = plugin.configuration();
        if (!DragonReinforcementRules.armed(config)) {
            return;
        }
        int dragons = DragonReinforcementRules.dragonCount(partySize, config.bossScaling().multiDragon());
        plugin.getLogger().info(() -> "Reinforcement window closed in " + world.getName() + ": "
                + partySize + " on the main island, " + dragons + " dragon(s).");
        broadcast(world, player -> player.showTitle(Title.title(
                Component.text(dragons == 1 ? "1 dragon" : dragons + " dragons", NamedTextColor.DARK_PURPLE),
                Component.text(partySize == 1 ? "for a party of 1" : "for a party of " + partySize,
                        NamedTextColor.GRAY),
                TITLE_TIMES)));
        for (DragonReinforcementRules.SpawnPoint point : DragonReinforcementRules.spawnPoints(dragons - 1)) {
            plugin.getServer().getRegionScheduler().execute(plugin, world, point.chunkX(), point.chunkZ(),
                    () -> spawnSecondary(world, point));
        }
    }

    /** On the region owning {@code point}: spawns one tagged secondary dragon. */
    private void spawnSecondary(World world, DragonReinforcementRules.SpawnPoint point) {
        if (!world.isChunkLoaded(point.chunkX(), point.chunkZ())) {
            // The ring sits inside the pillars, which a party on the main island keeps loaded, so this
            // is an empty End rather than a fight. Spawning into an unloaded chunk is not attempted.
            plugin.getLogger().warning(() -> "Secondary dragon not spawned in " + world.getName()
                    + ": chunk " + point.chunkX() + ", " + point.chunkZ() + " is not loaded.");
            return;
        }
        Location location = new Location(world, point.x(), point.y(), point.z(), point.yaw(), 0.0F);
        world.spawn(location, EnderDragon.class, dragon -> dragon.getPersistentDataContainer()
                .set(secondaryDragon, PersistentDataType.BYTE, (byte) 1));
    }

    /** Runs {@code action} for each player in {@code world}, on that player's own scheduler. */
    private void broadcast(World world, Consumer<Player> action) {
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            player.getScheduler().run(plugin, task -> {
                if (world.equals(player.getWorld())) {
                    action.accept(player);
                }
            }, null);
        }
    }

    private static Component countdownLine(int seconds) {
        return Component.text("Reinforcements counted in " + seconds + "s", NamedTextColor.LIGHT_PURPLE);
    }
}
