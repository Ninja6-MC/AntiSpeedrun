package com.ninja6.antispeedrun.listeners;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.ninja6.antispeedrun.config.PluginConfig;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Starts vanilla's dragon respawn when an eligible player enters an End whose dragon is dead (#271).
 * {@link BossCombatListener} calls {@link #entered} for every arrival it accepts: by portal, by
 * teleport, by respawn and by logging in already in the End. A player the End's dimension gate
 * does not admit on their own account never triggers it, whether or not the gate has marked the
 * arrival yet: a login already in the End is checked by the gate a tick later.
 *
 * <h2>The respawn</h2>
 *
 * The plugin does what the four-crystal ritual does: it sets an End Crystal on each of the four
 * ritual positions of the exit portal's rim and starts the sequence with those crystals through
 * {@code DragonBattle#initiateRespawn(Collection)}. Not the no-argument {@code initiateRespawn()},
 * which is vanilla's ritual check and needs player-placed crystals, and not the collection form
 * with no crystals: on 26.2 servers that sequence starts and is then abandoned without a dragon,
 * while on 1.21.11 it completes (#302's probes). With the crystals it is the ritual's own sequence
 * on every supported server, so the pillars, crystals and cages are rebuilt, the four crystals
 * explode at the end as the ritual's do, and the dragon is summoned as a resummoned fight:
 * {@code boss-scaling.scale-resummoned-dragons} and the exit portal lock apply as for the ritual.
 * A sequence that the server ends without a dragon is reported in the log.
 *
 * <h2>One respawn per world</h2>
 *
 * The decision is made on the region owning chunk {@code (0, 0)}, the only thread that may read
 * the battle, and from the battle's live state each time: a previous kill, no dragon alive, no
 * respawn running ({@link DragonRespawnRules#needsRespawn}). Tasks on one region run one after
 * another, so two players entering in the same tick start one respawn: the second task finds a
 * respawn running. A per-world claim keeps a burst of arrivals from queueing more than one task.
 * Nothing is persisted, so after a restart the battle's own state decides: a sequence a restart cut
 * short leaves the dragon dead and no respawn running, and the next entry starts it again.
 *
 * <p>Who may trigger is {@link DragonRespawnRules#eligible} and the {@link RespawnCooldown} given at
 * construction, read on the entering player's region. A player on cooldown starts nothing, and is
 * told how long is left if the End they entered is dragonless. The cooldown running out while they
 * are in the End starts nothing either: only a later entry does.
 */
public final class DragonRespawnOnEntry {

    /** How long after a start the battle is checked for a sequence the server abandoned. */
    private static final long CHECK_TICKS = 15L * 20L;

    private final Plugin plugin;
    private final Supplier<PluginConfig> configuration;
    private final RespawnCooldown cooldown;
    private final BiPredicate<Player, PluginConfig> admittedToEnd;
    private final Logger logger;

    /**
     * End worlds with a start task queued. Released when the task runs, and on every path that
     * would keep it from running, so one failure cannot stop every later entry.
     */
    private final Set<UUID> claims = ConcurrentHashMap.newKeySet();

    /**
     * @param admittedToEnd whether the End's dimension gate admits the player on their own
     *                      account, read on the player's region
     */
    public DragonRespawnOnEntry(Plugin plugin, Supplier<PluginConfig> configuration, RespawnCooldown cooldown,
                                BiPredicate<Player, PluginConfig> admittedToEnd) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.admittedToEnd = Objects.requireNonNull(admittedToEnd, "admittedToEnd");
        this.logger = plugin.getLogger();
    }

    /** On the entering player's region. Reads only the player and the configuration. */
    public void entered(Player player, World world) {
        PluginConfig config = configuration.get();
        if (world.getEnvironment() != World.Environment.THE_END || !DragonRespawnRules.armed(config)) {
            return;
        }
        if (!DragonRespawnRules.eligible(player.getGameMode().name())
                // The gate's check of a login already in the End runs a tick after this, so its
                // refusal mark is not there yet: ask the gate's verdict directly.
                || !admittedToEnd.test(player, config)) {
            return;
        }
        Optional<Duration> wait = cooldown.remaining(player.getUniqueId(), System.currentTimeMillis());
        if (wait.isPresent()) {
            tellIfDragonless(player, world, wait.get());
            return;
        }
        UUID id = world.getUID();
        if (!claims.add(id)) {
            return;
        }
        String trigger = player.getName();
        // Not from inside the arrival event: the server refuses a chunk ticket change while it is
        // adding the entity. The player's own scheduler runs it on the next tick.
        boolean scheduled = player.getScheduler().run(plugin, task -> load(world, trigger),
                () -> claims.remove(id)) != null;
        if (!scheduled) {
            claims.remove(id);
        }
    }

    /**
     * Loads chunk {@code (0, 0)} and hands the decision to the region owning it. Loaded first,
     * because a task for an unloaded chunk would wait on Folia until something loads it.
     */
    private void load(World world, String trigger) {
        UUID id = world.getUID();
        try {
            world.getChunkAtAsync(0, 0).whenComplete((chunk, failure) ->
                    plugin.getServer().getRegionScheduler().execute(plugin, world, 0, 0, () -> {
                        try {
                            start(world, trigger);
                        } finally {
                            claims.remove(id);
                        }
                    }));
        } catch (RuntimeException failure) {
            claims.remove(id);
            throw failure;
        }
    }

    /**
     * A player on cooldown is told how long is left, but only when the End they entered actually
     * needs a respawn, which only the battle's region can say. Nothing is claimed or started.
     */
    private void tellIfDragonless(Player player, World world, Duration wait) {
        player.getScheduler().run(plugin, task -> world.getChunkAtAsync(0, 0).whenComplete((chunk, failure) ->
                plugin.getServer().getRegionScheduler().execute(plugin, world, 0, 0, () -> {
                    if (!needsRespawn(world)) {
                        return;
                    }
                    player.getScheduler().run(plugin, told -> player.sendMessage(Component.text(
                            "You helped defeat an Ender Dragon recently. You can wake the next one by "
                                    + "entering the End in " + RespawnCooldownRules.describe(wait) + ".",
                            NamedTextColor.GOLD)), null);
                })), null);
    }

    /** Forgets a world's claim when it unloads. */
    public void forget(UUID world) {
        claims.remove(world);
    }

    /** On the {@code (0, 0)} region: the battle's live state, as {@link DragonRespawnRules#needsRespawn}. */
    private static boolean needsRespawn(World world) {
        DragonBattle battle = world.getEnderDragonBattle();
        boolean previouslyKilled = battle != null && battle.hasBeenPreviouslyKilled();
        boolean alive = battle != null && battle.getEnderDragon() != null;
        String phase = battle == null ? null : battle.getRespawnPhase().name();
        return DragonRespawnRules.needsRespawn(battle != null, previouslyKilled, alive, phase);
    }

    /** On the {@code (0, 0)} region: reads the battle and starts the respawn if it needs one. */
    private void start(World world, String trigger) {
        if (!DragonRespawnRules.armed(configuration.get())) {
            return;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        if (!needsRespawn(world)) {
            return;
        }
        List<EnderCrystal> crystals = ritualCrystals(world, battle);
        if (crystals.isEmpty()) {
            logger.warning(() -> "Could not start the dragon respawn in " + world.getName() + " for "
                    + trigger + "'s entry: the four ritual positions on the exit portal's rim are not "
                    + "all bedrock with room above, or not loaded. The next entry tries again.");
            return;
        }
        if (!battle.initiateRespawn(crystals)) {
            crystals.forEach(EnderCrystal::remove);
            logger.warning(() -> "The server declined to start the dragon respawn in " + world.getName()
                    + " for " + trigger + "'s entry, though its dragon is dead and no respawn is running.");
            return;
        }
        logger.info(() -> trigger + " entered " + world.getName()
                + " with its dragon dead; the dragon respawn has started.");
        plugin.getServer().getRegionScheduler().runDelayed(plugin, world, 0, 0, task -> {
            DragonBattle now = world.getEnderDragonBattle();
            if (now != null && now.getEnderDragon() == null
                    && now.getRespawnPhase() == DragonBattle.RespawnPhase.NONE) {
                logger.warning(() -> "The dragon respawn in " + world.getName()
                        + " ended without a dragon. The next entry tries again.");
            }
        }, CHECK_TICKS);
    }

    /**
     * On the {@code (0, 0)} region: an End Crystal on each ritual position of the exit portal's rim,
     * or none at all if any position is unusable or belongs to another region.
     */
    private List<EnderCrystal> ritualCrystals(World world, DragonBattle battle) {
        Location portal = battle.getEndPortalLocation();
        if (portal == null && battle.generateEndPortal(false)) {
            portal = battle.getEndPortalLocation();
        }
        if (portal == null) {
            return List.of();
        }
        int cx = portal.getBlockX();
        int cz = portal.getBlockZ();
        List<Location> spots = new ArrayList<>();
        for (int[] offset : DragonRespawnRules.RITUAL_OFFSETS) {
            int x = cx + offset[0];
            int z = cz + offset[1];
            if (!Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4) || !world.isChunkLoaded(x >> 4, z >> 4)) {
                return List.of();
            }
            OptionalInt top = DragonRespawnRules.rimTop(portal.getBlockY(),
                    y -> world.getBlockAt(x, y, z).getType() == Material.BEDROCK,
                    y -> world.getBlockAt(x, y, z).isPassable());
            if (top.isEmpty()) {
                return List.of();
            }
            spots.add(new Location(world, x + 0.5D, top.getAsInt() + 1, z + 0.5D));
        }
        List<EnderCrystal> crystals = new ArrayList<>();
        for (Location spot : spots) {
            crystals.add(world.spawn(spot, EnderCrystal.class, crystal -> crystal.setShowingBottom(false)));
        }
        return crystals;
    }
}
