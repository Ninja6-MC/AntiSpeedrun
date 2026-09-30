package com.ninja6.antispeedrun.listeners;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EnderDragonChangePhaseEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.ItemStack;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.storage.ReinforcedFightStore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;

/**
 * Multi-dragon boss combat (Epic 6). This class currently holds the reinforcement window (#37,
 * Task 6.1.1), its rounding modes (#38, Task 6.1.2), resummoned fights (#20, Task 6.1.3) and
 * single-battle reconciliation (#56, Task 6.1.5), secondary AI and boss bars (#21), and
 * balanced secondary XP (#22), and the resummoned exit lock and trophies (#23).
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
 * <p>The first fight's window opens once. A resolved one is recorded in {@link ReinforcedFightStore},
 * and a tagged secondary found in a loaded chunk marks it resolved too, so a restart mid-fight never
 * spawns a second set.
 *
 * <h2>Resummoned fights</h2>
 *
 * A dragon brought back with the four End crystals opens a window of its own when it spawns, with
 * {@code boss-scaling.scale-resummoned-dragons} on: the same countdown, census and dragon count as
 * the first fight. It is told apart from the world's first dragon by the battle having been won
 * before, and from {@code /summon} and other plugins' spawns by its spawn reason. The fight lasts as
 * long as its primary, tracked by a {@link ResummonFight} per world. Nothing of it is persisted: a
 * primary loaded after a restart is not a fresh spawn and opens nothing.
 *
 * <h2>One battle, many dragons</h2>
 *
 * Vanilla's victory sequence belongs to the primary alone, so the primary cannot die while a
 * secondary lives: its death is cancelled at one health and its {@code DYING} phase is refused.
 * Secondaries die normally, drop their configured XP, and do not heal from End
 * crystals. The reasoning is in {@link DragonReconciliationRules}; the live count of secondaries is
 * a {@link SecondaryRoster} per world, which every handler reads and writes on the thread that owns
 * the entity the event is about.
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

    /** Refused-death notices go out at most this often per world, however hard the primary is hit. */
    private static final long REFUSAL_NOTICE_INTERVAL_MILLIS = 3_000L;

    /** The opening around the central pillar is inside this square. */
    private static final int EXIT_PORTAL_RADIUS = 2;

    private final AntiSpeedrunPlugin plugin;
    private final NamespacedKey secondaryDragon;
    private final ReinforcedFightStore reinforcedFights;

    /** One window per End world, keyed by world UID. Removed when the world unloads. */
    private final Map<UUID, ReinforcementWindow> windows = new ConcurrentHashMap<>();

    /** Living secondaries per End world, keyed by world UID. Removed when the world unloads. */
    private final Map<UUID, SecondaryRoster> rosters = new ConcurrentHashMap<>();

    /** The current resummoned fight per End world, keyed by world UID. Removed when it ends. */
    private final Map<UUID, ResummonFight> resummons = new ConcurrentHashMap<>();

    /** When each world last told its players the primary cannot fall yet, in epoch milliseconds. */
    private final Map<UUID, AtomicLong> lastRefusalNotice = new ConcurrentHashMap<>();

    /** Only the dragon's region writes its health; viewers consume immutable snapshots on their own. */
    private final SecondaryBarBoard secondaryBars = new SecondaryBarBoard();
    private final Set<UUID> publishingDragons = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ViewerBars<BossBar>> viewerBars = new ConcurrentHashMap<>();

    public BossCombatListener(AntiSpeedrunPlugin plugin, ReinforcedFightStore reinforcedFights) {
        this.plugin = plugin;
        this.secondaryDragon = new NamespacedKey(plugin, SECONDARY_DRAGON_KEY);
        this.reinforcedFights = reinforcedFights;
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
                player.getScheduler().run(plugin, scheduled -> refreshBars(player), null);
            }
        }, SecondaryDragonRules.BAR_INTERVAL_TICKS, SecondaryDragonRules.BAR_INTERVAL_TICKS);
    }

    /** The key {@link #SECONDARY_DRAGON_KEY} names under this plugin's namespace. */
    public NamespacedKey secondaryDragonKey() {
        return secondaryDragon;
    }

    /** Entry by portal, command or any other transit. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        clearBars(event.getPlayer());
        entered(event.getPlayer().getWorld());
    }

    /** A player who logs out in the End and back in never changes world, but still arrives. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        entered(event.getPlayer().getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clearBars(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        windows.remove(world);
        rosters.remove(world);
        secondaryBars.forgetWorld(world);
        lastRefusalNotice.remove(world);
        ResummonFight resummon = resummons.remove(world);
        if (resummon != null) {
            resummon.supersede();
        }
    }

    /**
     * On the region owning the chunk: a secondary saved in an earlier run rejoins the roster, and its
     * presence proves the fight was already reinforced even if the record of it was lost.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        World world = event.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        for (Entity entity : event.getEntities()) {
            if (entity instanceof EnderDragon dragon && isSecondary(dragon)) {
                roster(world).track(dragon.getUniqueId());
                activateSecondary(dragon);
                if (window(world).markResolved()) {
                    reinforcedFights.markReinforced(world.getUID(), System.currentTimeMillis());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof EnderDragon dragon && isSecondary(dragon)) {
                secondaryBars.withdraw(event.getWorld().getUID(), dragon.getUniqueId());
                publishingDragons.remove(dragon.getUniqueId());
            }
        }
    }

    /**
     * On the dragon's region. The primary cannot die while a secondary lives, whether it is killed in
     * flight, on the perch or by {@code /kill}; a secondary's XP follows balanced-xp.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)
                || dragon.getWorld().getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (isSecondary(dragon)) {
            event.setDroppedExp(DragonReconciliationRules.secondaryExperience(event.getDroppedExp(),
                    plugin.configuration().bossScaling().multiDragon().balancedXp()));
            maybeDropHead(event);
            return;
        }
        int living = roster(dragon.getWorld()).living();
        if (DragonReconciliationRules.refusesDeath(false, living)) {
            event.setCancelled(true);
            event.setReviveHealth(1.0D);
            noticeRefusal(dragon.getWorld(), living);
            return;
        }
        maybeDropHead(event);
    }

    /**
     * After every other plugin has had its say: a secondary that did die leaves the roster, and a
     * resummoned primary that did die ends its fight.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDied(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon) {
            ResummonFight ended = gone(dragon);
            if (ended != null) {
                finishResummon(dragon.getWorld(), ended, true);
            }
        }
    }

    /** A dragon removed any way but an unload is gone: a plugin, a discard or a kill. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragonRemoved(EntityRemoveEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon) {
            if (isSecondary(dragon)) {
                secondaryBars.withdraw(dragon.getWorld().getUID(), dragon.getUniqueId());
                publishingDragons.remove(dragon.getUniqueId());
            }
            if (!DragonReconciliationRules.survivesRemoval(event.getCause().name())) {
                ResummonFight ended = gone(dragon);
                if (ended != null) {
                    finishResummon(dragon.getWorld(), ended, false);
                }
            }
        }
    }

    /** A successful player hit postpones the resummoned fight's inactivity escape. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)
                || dragon.getWorld().getEnvironment() != World.Environment.THE_END
                || event.getFinalDamage() <= 0.0D
                || !(event.getDamageSource().getCausingEntity() instanceof Player)) {
            return;
        }
        ResummonFight fight = resummons.get(dragon.getWorld().getUID());
        if (fight != null && fight.ongoing()
                && (fight.primary().equals(dragon.getUniqueId()) || isSecondary(dragon))) {
            fight.playerDamagedDragon(System.currentTimeMillis());
        }
    }

    /** Refuse an exit even if another plugin recreates portal blocks during a locked resummon. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExitPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL
                || event.getFrom().getWorld().getEnvironment() != World.Environment.THE_END) {
            return;
        }
        PluginConfig.BossScaling config = plugin.configuration().bossScaling();
        ResummonFight fight = resummons.get(event.getFrom().getWorld().getUID());
        if (config.enabled() && config.exitPortalLockDuringBattle()
                && fight != null && fight.ongoing() && !fight.exitReleased()) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text(
                    "The exit portal is sealed while the dragons fight", NamedTextColor.LIGHT_PURPLE));
        }
    }

    /**
     * On the new dragon's region. A dragon the four-crystal ritual summons (#20) opens a window for
     * its fight; that the battle really has been won before is checked on the {@code (0, 0)} region,
     * the only one that may read the battle.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        World world = dragon.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END
                || !DragonReinforcementRules.mayBeResummon(event.getSpawnReason().name(), isSecondary(dragon))) {
            return;
        }
        PluginConfig config = plugin.configuration();
        if (!config.bossScaling().enabled()) {
            return;
        }
        ResummonFight fight = new ResummonFight(dragon.getUniqueId());
        ResummonFight previous = resummons.put(world.getUID(), fight);
        if (previous != null) {
            previous.supersede();
        }
        plugin.getServer().getRegionScheduler().execute(plugin, world, 0, 0,
                () -> startResummon(world, fight));
    }

    /** Confirms the ritual on the battle's region before touching its portal or opening a window. */
    private void startResummon(World world, ResummonFight fight) {
        Resummoned resummoned = new Resummoned(world, fight);
        if (!resummoned.ongoing()) {
            resummons.remove(world.getUID(), fight);
            return;
        }
        PluginConfig.BossScaling config = plugin.configuration().bossScaling();
        if (config.enabled() && config.exitPortalLockDuringBattle()) {
            lockExit(world, fight);
        } else {
            fight.releaseExit();
        }
        if (DragonReinforcementRules.resummonArmed(config) && fight.window().tryOpen()) {
            begin(resummoned, config.battlePrepSeconds());
        }
    }

    /**
     * The second half of the hold. Vanilla sets a dragon's health to one and asks for {@code DYING}
     * after a lethal hit in flight; with the death already refused this should not arrive, and if it
     * does it is refused too.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDragonPhase(EnderDragonChangePhaseEvent event) {
        EnderDragon dragon = event.getEntity();
        if (isSecondary(dragon)
                && SecondaryDragonRules.needsFightingPhase(event.getNewPhase().name())) {
            event.setNewPhase(EnderDragon.Phase.CIRCLING);
        }
        if (event.getNewPhase() != EnderDragon.Phase.DYING
                || dragon.getWorld().getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (DragonReconciliationRules.refusesDeath(isSecondary(dragon), roster(dragon.getWorld()).living())) {
            event.setCancelled(true);
        }
    }

    /** Secondaries do not heal from End crystals. See {@link DragonReconciliationRules}. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDragonRegain(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon
                && DragonReconciliationRules.refusesRegain(isSecondary(dragon), event.getRegainReason().name())) {
            event.setCancelled(true);
        }
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
        ReinforcementWindow window = window(world);
        if (!window.tryOpen()) {
            return;
        }
        int seconds = config.bossScaling().battlePrepSeconds();
        Fight first = new FirstFight(world, window);
        plugin.getServer().getRegionScheduler().execute(plugin, world, 0, 0, () -> begin(first, seconds));
    }

    /** On the {@code (0, 0)} region: confirms there is a fight to reinforce, then counts down. */
    private void begin(Fight fight, int seconds) {
        World world = fight.world;
        if (!fight.ongoing()) {
            fight.abandon();
            return;
        }
        if (seconds <= 0) {
            census(fight);
            return;
        }
        broadcast(world, player -> {
            player.showTitle(Title.title(
                    Component.text(fight.headline(), NamedTextColor.DARK_PURPLE),
                    Component.text("Reinforcements are counted in " + seconds + "s", NamedTextColor.GRAY),
                    TITLE_TIMES));
            player.sendActionBar(countdownLine(seconds));
        });
        AtomicInteger remaining = new AtomicInteger(seconds);
        plugin.getServer().getRegionScheduler().runAtFixedRate(plugin, world, 0, 0, task -> {
            if (!fight.ongoing()) {
                task.cancel();
                fight.abandon();
                return;
            }
            int left = remaining.decrementAndGet();
            if (left <= 0) {
                task.cancel();
                census(fight);
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
    private void census(Fight fight) {
        World world = fight.world;
        List<? extends Player> online = List.copyOf(plugin.getServer().getOnlinePlayers());
        CensusTally tally = CensusTally.start(online.size(), counted ->
                plugin.getServer().getRegionScheduler().execute(plugin, world, 0, 0,
                        () -> resolve(fight, counted)));
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
    private void resolve(Fight fight, int partySize) {
        World world = fight.world;
        if (!fight.window.resolve()) {
            return;
        }
        // Recorded before anything is spawned: a crash in between costs dragons, never doubles them.
        fight.resolved();
        // The primary may have died during the window; the fight is then over and needs no help.
        if (!fight.ongoing()) {
            return;
        }
        PluginConfig config = plugin.configuration();
        if (!fight.armed(config)) {
            return;
        }
        int dragons = DragonReinforcementRules.dragonCount(partySize, config.bossScaling().multiDragon());
        plugin.getLogger().info(() -> "Reinforcement window closed in " + world.getName() + " for "
                + fight.describe() + ": " + partySize + " on the main island, " + dragons + " dragon(s).");
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
        EnderDragon dragon = world.spawn(location, EnderDragon.class, spawned -> spawned
                .getPersistentDataContainer().set(secondaryDragon, PersistentDataType.BYTE, (byte) 1));
        // World#spawn returns the entity even when another plugin cancelled its spawn. A dragon that
        // never entered the world must not hold the primary.
        if (!roster(world).trackSpawned(dragon.getUniqueId(), dragon.isValid())) {
            plugin.getLogger().info(() -> "Secondary dragon spawn in " + world.getName()
                    + " was cancelled; it is not counted.");
        } else {
            activateSecondary(dragon);
        }
    }

    /** Starts vanilla's active phase and publishes health on the dragon's own region. */
    private void activateSecondary(EnderDragon dragon) {
        if (SecondaryDragonRules.needsFightingPhase(dragon.getPhase().name())) {
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
        }
        UUID id = dragon.getUniqueId();
        if (!publishingDragons.add(id)) {
            return;
        }
        dragon.getScheduler().runAtFixedRate(plugin, task -> {
            if (!dragon.isValid() || dragon.isDead()) {
                secondaryBars.withdraw(dragon.getWorld().getUID(), id);
                publishingDragons.remove(id);
                task.cancel();
                return;
            }
            secondaryBars.publish(dragon.getWorld().getUID(), id,
                    SecondaryDragonRules.progress(dragon.getHealth(), dragon.getMaxHealth()));
        }, () -> publishingDragons.remove(id), 1L, SecondaryDragonRules.BAR_INTERVAL_TICKS);
    }

    /** Called on the player's region; no dragon or other player's live state is touched. */
    private void refreshBars(Player player) {
        UUID worldId = player.getWorld().getUID();
        Location at = player.getLocation();
        boolean visible = player.getWorld().getEnvironment() == World.Environment.THE_END
                && SecondaryDragonRules.seesBossBars(true, player.isDead(),
                        at.getX(), at.getY(), at.getZ());
        ViewerBars<BossBar> bars = viewerBars.computeIfAbsent(player.getUniqueId(), id ->
                new ViewerBars<>(new ViewerBars.Display<>() {
                    @Override
                    public BossBar create(float progress) {
                        return BossBar.bossBar(Component.text("Ender Dragon Reinforcement"), progress,
                                BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
                    }

                    @Override
                    public void show(BossBar bar) {
                        player.showBossBar(bar);
                    }

                    @Override
                    public void fill(BossBar bar, float progress) {
                        bar.progress(progress);
                    }

                    @Override
                    public void hide(BossBar bar) {
                        player.hideBossBar(bar);
                    }
                }));
        bars.sync(visible ? secondaryBars.snapshot(worldId) : Map.of());
    }

    private void clearBars(Player player) {
        ViewerBars<BossBar> bars = viewerBars.remove(player.getUniqueId());
        if (bars != null) {
            bars.hideAll();
        }
    }

    /**
     * On {@code dragon}'s region, once it has died or been removed: a secondary leaves the roster and
     * a resummoned primary ends its fight.
     */
    private ResummonFight gone(EnderDragon dragon) {
        World world = dragon.getWorld();
        if (isSecondary(dragon)) {
            roster(world).forget(dragon.getUniqueId());
            secondaryBars.withdraw(world.getUID(), dragon.getUniqueId());
            publishingDragons.remove(dragon.getUniqueId());
            return null;
        }
        ResummonFight fight = resummons.get(world.getUID());
        if (fight != null && fight.end(dragon.getUniqueId())) {
            resummons.remove(world.getUID(), fight);
            return fight;
        }
        return null;
    }

    /** Give each actual dragon death its configured head chance, respecting doMobLoot. */
    private void maybeDropHead(EntityDeathEvent event) {
        PluginConfig.BossScaling config = plugin.configuration().bossScaling();
        if (config.enabled()
                && Boolean.TRUE.equals(event.getEntity().getWorld().getGameRuleValue(GameRule.DO_MOB_LOOT))
                && DragonExitRules.dropsHead(config.skullDropChance(),
                        ThreadLocalRandom.current().nextDouble())) {
            event.getDrops().add(new ItemStack(Material.DRAGON_HEAD));
        }
    }

    /** On the battle region: fill the portal's open basin and run the inactivity escape timer. */
    private void lockExit(World world, ResummonFight fight) {
        DragonBattle battle = world.getEnderDragonBattle();
        Location portal = battle == null ? null : battle.getEndPortalLocation();
        if (portal == null) {
            plugin.getLogger().warning("Cannot seal the End exit in " + world.getName()
                    + ": its dragon battle has no portal location.");
            fight.releaseExit();
            return;
        }
        int centerX = portal.getBlockX();
        int centerY = portal.getBlockY();
        int centerZ = portal.getBlockZ();
        for (int chunkX = Math.floorDiv(centerX - EXIT_PORTAL_RADIUS, 16);
                chunkX <= Math.floorDiv(centerX + EXIT_PORTAL_RADIUS, 16); chunkX++) {
            for (int chunkZ = Math.floorDiv(centerZ - EXIT_PORTAL_RADIUS, 16);
                    chunkZ <= Math.floorDiv(centerZ + EXIT_PORTAL_RADIUS, 16); chunkZ++) {
                int fromX = Math.max(centerX - EXIT_PORTAL_RADIUS, chunkX << 4);
                int toX = Math.min(centerX + EXIT_PORTAL_RADIUS, (chunkX << 4) + 15);
                int fromZ = Math.max(centerZ - EXIT_PORTAL_RADIUS, chunkZ << 4);
                int toZ = Math.min(centerZ + EXIT_PORTAL_RADIUS, (chunkZ << 4) + 15);
                plugin.getServer().getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
                    if (resummons.get(world.getUID()) != fight || fight.exitReleased()) {
                        return;
                    }
                    for (int x = fromX; x <= toX; x++) {
                        for (int z = fromZ; z <= toZ; z++) {
                            int dx = x - centerX;
                            int dz = z - centerZ;
                            if (!DragonExitRules.inBasin(dx, dz)) {
                                continue;
                            }
                            Material existing = world.getBlockAt(x, centerY, z).getType();
                            if (existing == Material.AIR || existing == Material.END_PORTAL) {
                                world.getBlockAt(x, centerY, z).setType(Material.BEDROCK, false);
                            }
                        }
                    }
                });
            }
        }
        plugin.getServer().getRegionScheduler().runAtFixedRate(plugin, world, 0, 0, task -> {
            if (resummons.get(world.getUID()) != fight || !fight.ongoing()) {
                task.cancel();
                return;
            }
            PluginConfig.BossScaling config = plugin.configuration().bossScaling();
            if ((!config.enabled() || !config.exitPortalLockDuringBattle()) && fight.releaseExit()
                    || fight.releaseExitAfter(System.currentTimeMillis(),
                            config.exitPortalLockReleaseMinutes())) {
                task.cancel();
                reopenExit(world);
            }
        }, TICKS_PER_SECOND, TICKS_PER_SECOND);
    }

    /** The battle API restores the canonical portal shape; false keeps the first-kill egg unique. */
    private void reopenExit(World world) {
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle != null) {
            battle.generateEndPortal(false);
        }
    }

    /** A death earns a repeat-fight egg; any other primary removal only restores the exit. */
    private void finishResummon(World world, ResummonFight fight, boolean victory) {
        plugin.getServer().getRegionScheduler().runDelayed(plugin, world, 0, 0, task -> {
            if (resummons.containsKey(world.getUID())) {
                return;
            }
            if (!fight.exitReleased()) {
                reopenExit(world);
            }
            PluginConfig.ExitPortalEgg egg = plugin.configuration().bossScaling().exitPortalEgg();
            if (!victory || !egg.enabled() || egg.placementMode() == PluginConfig.PlacementMode.NONE) {
                return;
            }
            DragonBattle battle = world.getEnderDragonBattle();
            Location portal = battle == null ? null : battle.getEndPortalLocation();
            if (portal == null) {
                plugin.getLogger().warning("Cannot place the repeat-fight egg in " + world.getName()
                        + ": its dragon battle has no portal location.");
                return;
            }
            Location trophy = portal.clone().add(0.5D, 4.0D, 0.5D);
            if (egg.placementMode() == PluginConfig.PlacementMode.TOP_PILLAR
                    && trophy.getBlock().getType() == Material.AIR) {
                trophy.getBlock().setType(Material.DRAGON_EGG, false);
            } else {
                world.dropItemNaturally(trophy, new ItemStack(Material.DRAGON_EGG));
            }
        }, 1L);
    }

    /** This world's window, created on first use from the persisted record. */
    private ReinforcementWindow window(World world) {
        return windows.computeIfAbsent(world.getUID(),
                id -> new ReinforcementWindow(reinforcedFights.isReinforced(id)));
    }

    private SecondaryRoster roster(World world) {
        return rosters.computeIfAbsent(world.getUID(), id -> new SecondaryRoster());
    }

    /** Whether {@code dragon} carries the secondary tag. Called on the dragon's own region. */
    private boolean isSecondary(EnderDragon dragon) {
        return dragon.getPersistentDataContainer().has(secondaryDragon, PersistentDataType.BYTE);
    }

    /** Tells the End the primary is being held, at most once per {@link #REFUSAL_NOTICE_INTERVAL_MILLIS}. */
    private void noticeRefusal(World world, int living) {
        long now = System.currentTimeMillis();
        AtomicLong last = lastRefusalNotice.computeIfAbsent(world.getUID(), id -> new AtomicLong());
        long previous = last.get();
        if (now - previous < REFUSAL_NOTICE_INTERVAL_MILLIS || !last.compareAndSet(previous, now)) {
            return;
        }
        Component line = Component.text("The Ender Dragon cannot fall while "
                + (living == 1 ? "1 reinforcement remains" : living + " reinforcements remain"),
                NamedTextColor.LIGHT_PURPLE);
        broadcast(world, player -> player.sendActionBar(line));
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

    /**
     * A fight a window reinforces: the world's first, or a resummoned one. Every method runs on the
     * {@code (0, 0)} region.
     */
    private abstract static class Fight {

        final World world;
        final ReinforcementWindow window;

        Fight(World world, ReinforcementWindow window) {
            this.world = world;
            this.window = window;
        }

        /** Whether there is still a fight to reinforce: before the countdown, on each tick, at the census. */
        abstract boolean ongoing();

        /** Whether the configuration still reinforces this kind of fight. */
        abstract boolean armed(PluginConfig config);

        /** Records that the census was taken. Called before anything is spawned. */
        abstract void resolved();

        /** Closes the window without a census: the fight has ended, or was never one to reinforce. */
        void abandon() {
            window.abandon();
        }

        /** The title shown when the countdown starts. */
        abstract String headline();

        /** How the log names the fight. */
        abstract String describe();
    }

    /** The world's first fight (#37): on until its dragon has been killed once. */
    private final class FirstFight extends Fight {

        FirstFight(World world, ReinforcementWindow window) {
            super(world, window);
        }

        @Override
        boolean ongoing() {
            return refresh(world, window);
        }

        @Override
        boolean armed(PluginConfig config) {
            return DragonReinforcementRules.armed(config);
        }

        @Override
        void resolved() {
            reinforcedFights.markReinforced(world.getUID(), System.currentTimeMillis());
        }

        @Override
        String headline() {
            return "The Ender Dragon awakens";
        }

        @Override
        String describe() {
            return "the first fight";
        }
    }

    /** A fight resummoned with the four End crystals (#20): on while its primary lives. */
    private final class Resummoned extends Fight {

        private final ResummonFight fight;

        Resummoned(World world, ResummonFight fight) {
            super(world, fight.window());
            this.fight = fight;
        }

        /**
         * The battle having been won before is what makes this a resummon rather than the world's
         * first dragon, which the entry window reinforces instead.
         */
        @Override
        boolean ongoing() {
            DragonBattle battle = world.getEnderDragonBattle();
            return battle != null && battle.hasBeenPreviouslyKilled()
                    && resummons.get(world.getUID()) == fight && fight.ongoing();
        }

        @Override
        boolean armed(PluginConfig config) {
            return DragonReinforcementRules.resummonArmed(config.bossScaling());
        }

        @Override
        void resolved() {
            // Nothing is persisted for a resummoned fight; see ResummonFight.
        }

        @Override
        void abandon() {
            super.abandon();
            resummons.remove(world.getUID(), fight);
        }

        @Override
        String headline() {
            return "The Ender Dragon returns";
        }

        @Override
        String describe() {
            return "a resummoned fight";
        }
    }

    private static Component countdownLine(int seconds) {
        return Component.text("Reinforcements counted in " + seconds + "s", NamedTextColor.LIGHT_PURPLE);
    }
}
