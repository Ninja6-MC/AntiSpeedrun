package com.ninja6.antispeedrun.listeners;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.bukkit.Location;
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
import org.bukkit.event.entity.EnderDragonChangePhaseEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataType;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.storage.ReinforcedFightStore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

/**
 * Multi-dragon boss combat (Epic 6). This class currently holds the reinforcement window (#37,
 * Task 6.1.1), its rounding modes (#38, Task 6.1.2), resummoned fights (#20, Task 6.1.3) and
 * single-battle reconciliation (#56, Task 6.1.5); secondary AI and boss bars (#21), XP (#22) and the
 * exit lock (#23) build on it.
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
 * Secondaries die normally, drop at most vanilla's repeat-kill XP, and do not heal from End
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

    public BossCombatListener(AntiSpeedrunPlugin plugin, ReinforcedFightStore reinforcedFights) {
        this.plugin = plugin;
        this.secondaryDragon = new NamespacedKey(plugin, SECONDARY_DRAGON_KEY);
        this.reinforcedFights = reinforcedFights;
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
        UUID world = event.getWorld().getUID();
        windows.remove(world);
        rosters.remove(world);
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
                if (window(world).markResolved()) {
                    reinforcedFights.markReinforced(world.getUID(), System.currentTimeMillis());
                }
            }
        }
    }

    /**
     * On the dragon's region. The primary cannot die while a secondary lives, whether it is killed in
     * flight, on the perch or by {@code /kill}; a secondary's XP is capped at vanilla's repeat-kill
     * amount.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)
                || dragon.getWorld().getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (isSecondary(dragon)) {
            event.setDroppedExp(DragonReconciliationRules.secondaryExperience(event.getDroppedExp()));
            return;
        }
        int living = roster(dragon.getWorld()).living();
        if (DragonReconciliationRules.refusesDeath(false, living)) {
            event.setCancelled(true);
            event.setReviveHealth(1.0D);
            noticeRefusal(dragon.getWorld(), living);
        }
    }

    /**
     * After every other plugin has had its say: a secondary that did die leaves the roster, and a
     * resummoned primary that did die ends its fight.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDied(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon) {
            gone(dragon);
        }
    }

    /** A dragon removed any way but an unload is gone: a plugin, a discard or a kill. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragonRemoved(EntityRemoveEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon
                && !DragonReconciliationRules.survivesRemoval(event.getCause().name())) {
            gone(dragon);
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
        if (!DragonReinforcementRules.resummonArmed(config.bossScaling())) {
            return;
        }
        ResummonFight fight = new ResummonFight(dragon.getUniqueId());
        ResummonFight previous = resummons.put(world.getUID(), fight);
        if (previous != null) {
            previous.supersede();
        }
        if (!fight.window().tryOpen()) {
            return;
        }
        int seconds = config.bossScaling().battlePrepSeconds();
        Fight resummoned = new Resummoned(world, fight);
        plugin.getServer().getRegionScheduler().execute(plugin, world, 0, 0, () -> begin(resummoned, seconds));
    }

    /**
     * The second half of the hold. Vanilla sets a dragon's health to one and asks for {@code DYING}
     * after a lethal hit in flight; with the death already refused this should not arrive, and if it
     * does it is refused too.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDragonPhase(EnderDragonChangePhaseEvent event) {
        EnderDragon dragon = event.getEntity();
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
        }
    }

    /**
     * On {@code dragon}'s region, once it has died or been removed: a secondary leaves the roster and
     * a resummoned primary ends its fight.
     */
    private void gone(EnderDragon dragon) {
        World world = dragon.getWorld();
        if (isSecondary(dragon)) {
            roster(world).forget(dragon.getUniqueId());
            return;
        }
        ResummonFight fight = resummons.get(world.getUID());
        if (fight != null && fight.end(dragon.getUniqueId())) {
            resummons.remove(world.getUID(), fight);
        }
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
