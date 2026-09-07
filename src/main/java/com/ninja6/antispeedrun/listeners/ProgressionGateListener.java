package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.PlayerStateMap;
import com.ninja6.antispeedrun.storage.DimensionUnlock;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * The dimension gate: the first thing in this plugin that actually stops a player.
 *
 * <p>Three routes into the Nether or the End, one rule behind all of them.
 *
 * <ul>
 *   <li><strong>{@link PlayerPortalEvent}</strong> (#34) — a player walking into a portal. Cancelled
 *       outright, with a nudge back out so the next tick does not simply re-fire it.</li>
 *   <li><strong>{@link EntityPortalEvent}</strong> (#35) — a boat, minecart or camel carrying
 *       riders. Triaged rather than cancelled: see {@link VehicleTransit}.</li>
 *   <li><strong>{@link PlayerTeleportEvent}</strong> (#6) — an Ender pearl thrown through a portal
 *       and pulled from a stasis chamber, and the other player-driven teleport causes that can
 *       cross a dimension boundary.</li>
 * </ul>
 *
 * <p>Everything that decides anything lives in {@link DimensionGateRules}, {@link VehicleTransit}
 * and {@link SafeRetreat}, all of which are free of Bukkit and covered by tests. What is left here
 * is reading the event, calling into those, and applying the answer — which is the only part that
 * genuinely needs a server.
 *
 * <h2>Folia</h2>
 *
 * <ul>
 *   <li>All three events are single-entity events, so Folia calls them on the region owning that
 *       entity. Evaluating progression for a player, reading their bypass grant from their PDC and
 *       nudging their velocity are therefore all legal inline.</li>
 *   <li>A vehicle's passengers are in the vehicle's region by construction, so
 *       {@link #onEntityPortal} may evaluate them without hopping.</li>
 *   <li><strong>The dismount is not inline.</strong> Audit finding R-09: mutating the passenger
 *       list while the portal transfer is still resolving is a known source of ghost entities, so
 *       {@link #ejectAndReposition} runs on the <em>vehicle's</em> {@code EntityScheduler}, which
 *       is next tick on the vehicle's region.</li>
 *   <li><strong>Repositioning uses {@code teleportAsync}</strong> and continues in the returned
 *       future. {@code Entity#teleport} throws on Folia the moment the destination leaves the
 *       current region, and a two-block retreat can cross a region boundary.</li>
 *   <li>Nothing here opens a file or touches a store on a region thread. The dimension-unlock
 *       override is an in-memory read, and the bypass grant is a PDC read on the owning region.</li>
 * </ul>
 *
 * <h2>Thresholds — audit finding R-02</h2>
 *
 * No number in this class describes a requirement. Playtime, account age and advancements all come
 * from {@code dimension-gates.<dimension>.require-*}, which the shipped {@code config.yml} sets to
 * {@code 0}, {@code 0} and the two advancement lists respectively. See {@link DimensionGateRules}.
 */
public final class ProgressionGateListener implements Listener {

    /**
     * The standing exemption, exactly as {@code plugin.yml} declares it. A child of
     * {@code antispeedrun.bypass}, never of {@code antispeedrun.admin} — see the note in
     * {@code plugin.yml} about why operators are gated like everyone else.
     */
    public static final String BYPASS_PERMISSION = "antispeedrun.bypass.gates";

    /**
     * The teleport causes this plugin will cancel across a dimension boundary.
     *
     * <p>An allow-list rather than a deny-list, and the distinction is the point of #6's second
     * acceptance criterion. What is <em>absent</em> here — {@code PLUGIN}, {@code COMMAND},
     * {@code UNKNOWN}, {@code SPECTATE} — is every teleport another plugin or an operator asked
     * for. A hub, a warp plugin or {@code /tp} moving a player between dimensions is that server's
     * decision, and a progression plugin silently vetoing it would be a defect in this plugin
     * rather than a gate. The causes listed are the ones a player produces for themselves, which is
     * what the gate exists to regulate.
     *
     * <p>{@code ENDER_PEARL} is the one #6 names: a pearl thrown into a portal and parked in a
     * stasis chamber is the classic cross-dimensional skip. The rest are here because they are the
     * same shape of thing, not because a specific exploit was reported for each.
     */
    private static final Set<PlayerTeleportEvent.TeleportCause> GATED_CAUSES = EnumSet.of(
            PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
            PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT,
            PlayerTeleportEvent.TeleportCause.END_GATEWAY,
            PlayerTeleportEvent.TeleportCause.END_PORTAL,
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

    /**
     * How long a blocked player goes without being told again.
     *
     * <p>A cancelled portal leaves the player standing in it, and a rider being pushed by somebody
     * else is pushed again immediately, so without this the rejection line would repeat every time
     * the server re-offers the transit. Three seconds matches
     * {@code item-progression.feedback-cooldown-seconds}' shipped value, deliberately, so the two
     * feedback paths feel the same — but it is a constant rather than a read of that key, because
     * that key describes item pickups and borrowing it would tie two unrelated settings together.
     * {@code dimension-gates} has no cooldown key of its own and inventing one is a configuration
     * change, not a listener change.
     */
    private static final long FEEDBACK_COOLDOWN_MILLIS = 3_000L;

    /** Horizontal strength of the nudge out of a cancelled portal. Cosmetic; not a requirement. */
    private static final double PUSHBACK_STRENGTH = 0.45D;

    /** A small hop, so the nudge clears a block lip instead of grinding into it. */
    private static final double PUSHBACK_LIFT = 0.2D;

    private final AntiSpeedrunPlugin plugin;

    /**
     * When each player was last told they are gated. Registered with
     * {@link com.ninja6.antispeedrun.progression.PlayerStateRegistry} rather than held as a bare
     * map, so quit cleanup happens without this class remembering to do it — finding R-08.
     */
    private final PlayerStateMap<Long> lastFeedback;

    public ProgressionGateListener(AntiSpeedrunPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lastFeedback = plugin.playerState().register("dimension-gate-feedback");
    }

    // -------------------------------------------------------------------------------------------
    // #34 - on foot
    // -------------------------------------------------------------------------------------------

    /**
     * A player walking into a Nether or End portal.
     *
     * <p>{@code HIGH} with {@code ignoreCancelled}: late enough that a plugin with an opinion about
     * portals has already had it, early enough that {@code MONITOR} observers see the final answer.
     *
     * <p>{@code PlayerPortalEvent} has its own {@code HandlerList} despite extending
     * {@code PlayerTeleportEvent}, so {@link #onPlayerTeleport} does not also see this event and
     * the two cannot double-handle one transit.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Location to = event.getTo();
        if (to == null) {
            // Paper allows a null destination, which means the server has not resolved where this
            // portal goes. There is nothing to attribute to a gate, so the transit is left alone.
            // A deliberate fail-open, and a known silent-waiver path -- see #92.
            return;
        }
        Player player = event.getPlayer();
        PluginConfig config = plugin.configuration();
        Optional<DimensionUnlock> destination =
                DimensionGateRules.gatedDestination(kindOf(event.getFrom()), kindOf(to), config);
        if (destination.isEmpty()) {
            return;
        }
        DimensionUnlock dimension = destination.get();
        if (waived(player, dimension)) {
            return;
        }
        EligibilityResult result = evaluate(player, config, dimension);
        if (result.eligible()) {
            return;
        }

        event.setCancelled(true);
        reject(player, config, dimension, result);
        pushBack(player);
    }

    // -------------------------------------------------------------------------------------------
    // #35 - in a vehicle
    // -------------------------------------------------------------------------------------------

    /**
     * A vehicle carrying riders into a portal.
     *
     * <p>The event fires for the vehicle, not for its passengers, so a player being ferried into
     * the Nether never reaches {@link #onPlayerPortal} at all. Riders are gathered through the
     * whole passenger tree — a player on a horse in a boat is still a rider — and triaged by
     * {@link VehicleTransit}: unqualified riders come off, and the vehicle only stops if there is
     * nobody qualified left aboard to carry.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        Location to = event.getTo();
        if (to == null) {
            // As in onPlayerPortal: no resolved destination, so no gate to apply. See #92.
            return;
        }
        Entity vehicle = event.getEntity();
        List<Player> riders = ridersOf(vehicle);
        if (riders.isEmpty()) {
            return;
        }

        PluginConfig config = plugin.configuration();
        Optional<DimensionUnlock> destination =
                DimensionGateRules.gatedDestination(kindOf(event.getFrom()), kindOf(to), config);
        if (destination.isEmpty()) {
            return;
        }
        DimensionUnlock dimension = destination.get();

        VehicleTransit.Plan<Player> plan = VehicleTransit.plan(riders, rider -> {
            if (waived(rider, dimension)) {
                return false;
            }
            EligibilityResult result = evaluate(rider, config, dimension);
            if (result.eligible()) {
                return false;
            }
            // Sent from the triage pass rather than a second loop, so the message and the verdict
            // cannot disagree about who was blocked.
            reject(rider, config, dimension, result);
            return true;
        });
        if (plan.isNoOp()) {
            return;
        }
        if (plan.cancelTransit()) {
            event.setCancelled(true);
        }
        ejectAndReposition(vehicle, plan.ejected());
    }

    // -------------------------------------------------------------------------------------------
    // #6 - cross-dimensional teleport
    // -------------------------------------------------------------------------------------------

    /**
     * A cross-dimensional teleport the player brought about themselves — an Ender pearl above all.
     *
     * <p>The intra-dimensional case is decided in {@link DimensionGateRules#gatedDestination},
     * which returns empty whenever the two ends are the same kind of dimension. That is what keeps
     * {@code /spawn}, a random-teleport plugin and a chorus fruit untouched, and it is checked by a
     * test of its own rather than left to follow from the rest.
     *
     * <p>Nothing is pushed back here. A cancelled pearl simply does not move the player, and there
     * is no portal to climb out of.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!GATED_CAUSES.contains(event.getCause())) {
            return;
        }
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Player player = event.getPlayer();
        PluginConfig config = plugin.configuration();
        Optional<DimensionUnlock> destination =
                DimensionGateRules.gatedDestination(kindOf(event.getFrom()), kindOf(to), config);
        if (destination.isEmpty()) {
            return;
        }
        DimensionUnlock dimension = destination.get();
        if (waived(player, dimension)) {
            return;
        }
        EligibilityResult result = evaluate(player, config, dimension);
        if (result.eligible()) {
            return;
        }

        event.setCancelled(true);
        reject(player, config, dimension, result);
    }

    // -------------------------------------------------------------------------------------------
    // Shared decision plumbing
    // -------------------------------------------------------------------------------------------

    private static EnvironmentKind kindOf(Location location) {
        if (location == null) {
            return EnvironmentKind.CUSTOM;
        }
        World world = location.getWorld();
        return world == null ? EnvironmentKind.CUSTOM : EnvironmentKind.of(world.getEnvironment().name());
    }

    /**
     * Whether this player is exempt before their progression is consulted at all.
     *
     * <p>Both store reads are in-memory or PDC reads on the player's own region, so this is safe on
     * the event thread; neither touches a file.
     */
    private boolean waived(Player player, DimensionUnlock dimension) {
        return DimensionGateRules.waived(
                player.hasPermission(BYPASS_PERMISSION),
                plugin.bypasses().hasBypass(player, System.currentTimeMillis()),
                plugin.dimensionUnlocks().isUnlocked(dimension));
    }

    /**
     * The gate's verdict for one player, through the progression cache.
     *
     * <p>Deliberately not a direct advancement query: {@code ProgressionCache} exists so a portal
     * event costs a map lookup rather than a {@code getAdvancementProgress} per required key, and a
     * boat full of players walking into a portal is precisely the burst it was built for.
     */
    private EligibilityResult evaluate(Player player, PluginConfig config, DimensionUnlock dimension) {
        return plugin.progression().evaluate(
                player, config, DimensionGateRules.requirement(dimension, config));
    }

    /**
     * Tells the player why, at most once every {@link #FEEDBACK_COOLDOWN_MILLIS}.
     *
     * <p>The configured {@code rejection-message} is the operator's own MiniMessage and is
     * deserialised as markup. The fail-open hint is not: it interpolates advancement keys read from
     * {@code config.yml}, and {@code AntiSpeedrunCommand} established that anything arriving from
     * outside has its tags neutralised first.
     */
    private void reject(Player player, PluginConfig config, DimensionUnlock dimension,
                        EligibilityResult result) {
        long now = System.currentTimeMillis();
        long last = lastFeedback.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < FEEDBACK_COOLDOWN_MILLIS) {
            return;
        }
        lastFeedback.put(player.getUniqueId(), now);

        MiniMessage mini = MiniMessage.miniMessage();
        player.sendMessage(mini.deserialize(
                DimensionGateRules.gate(dimension, config).rejectionMessage()));
        DimensionGateRules.fallbackHint(result).ifPresent(hint ->
                player.sendMessage(mini.deserialize("<gray>" + mini.escapeTags(hint))));
    }

    /**
     * A nudge back out of the portal the player was just refused.
     *
     * <p>Same region as the player by definition, so applying velocity inline is safe — audit
     * finding R-09 draws the line at repositioning that could cross a region boundary, and this
     * does not reposition anything. Without it a cancelled portal leaves the player standing in the
     * block that produced the event, and the server offers the transit again as soon as the portal
     * cooldown lapses.
     */
    private void pushBack(Player player) {
        Vector heading = player.getLocation().getDirection();
        SafeRetreat.Offset offset =
                SafeRetreat.backwards(heading.getX(), heading.getZ(), PUSHBACK_STRENGTH);
        player.setVelocity(new Vector(offset.x(), PUSHBACK_LIFT, offset.z()));
    }

    // -------------------------------------------------------------------------------------------
    // Ejection - the delicate half of #35
    // -------------------------------------------------------------------------------------------

    /** Every player riding {@code vehicle}, however deep in the passenger stack. */
    private static List<Player> ridersOf(Entity vehicle) {
        List<Player> riders = new ArrayList<>(2);
        collectRiders(vehicle, riders);
        return riders;
    }

    private static void collectRiders(Entity vehicle, List<Player> into) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player player) {
                into.add(player);
            }
            collectRiders(passenger, into);
        }
    }

    /**
     * Takes the blocked riders off the vehicle and puts them down two blocks behind it.
     *
     * <p>Scheduled on the <strong>vehicle's</strong> {@code EntityScheduler}, which runs it next
     * tick on the region that owns the vehicle. Finding R-09: calling {@code removePassenger}
     * inline, while the portal transfer this event belongs to is still resolving, is a known source
     * of ghost entities — a vehicle that arrives without its rider, or a rider that arrives twice.
     * By next tick the transfer has settled one way or the other and the passenger list is a normal
     * thing to mutate.
     *
     * <p>The heading is read at schedule time, not inside the task, because by next tick a
     * cancelled vehicle's velocity has already been zeroed by the cancellation and there would be
     * no "backward" left to compute.
     */
    private void ejectAndReposition(Entity vehicle, List<Player> blocked) {
        Vector velocity = vehicle.getVelocity();
        double headingX = velocity.getX();
        double headingZ = velocity.getZ();

        vehicle.getScheduler().run(plugin, task -> {
            for (Player rider : blocked) {
                if (!rider.isOnline()) {
                    continue;
                }
                vehicle.removePassenger(rider);
                reposition(rider, headingX, headingZ);
            }
        }, null);
    }

    /**
     * Puts one ejected rider on solid ground behind the portal.
     *
     * <p>{@code teleportAsync} rather than {@code teleport}: two blocks is enough to leave the
     * current region, and {@code Entity#teleport} throws on Folia when it does. The returned future
     * is where the outcome is handled — a teleport that the server declines is logged rather than
     * dropped, because a rider who was ejected but not moved is standing in the portal being
     * offered the transit again.
     *
     * <p>A rider whose retreat lands nowhere usable is left where they are, minus the vehicle. That
     * is worse than a good landing and better than a guess: this code has no way to know that some
     * arbitrary nearby column is not lava.
     */
    private void reposition(Player rider, double headingX, double headingZ) {
        Location origin = rider.getLocation();
        SafeRetreat.Offset offset =
                SafeRetreat.backwards(headingX, headingZ, SafeRetreat.EJECT_DISTANCE_BLOCKS);
        if (offset.isZero()) {
            // The vehicle was not moving in any particular direction, so there is no backward. Fall
            // back to the direction the rider is facing, which is where they were heading anyway.
            Vector look = origin.getDirection();
            offset = SafeRetreat.backwards(look.getX(), look.getZ(), SafeRetreat.EJECT_DISTANCE_BLOCKS);
        }
        if (offset.isZero()) {
            return;
        }

        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        int targetX = (int) Math.floor(origin.getX() + offset.x());
        int targetZ = (int) Math.floor(origin.getZ() + offset.z());
        OptionalInt groundY = SafeRetreat.groundY(terrainOf(world), targetX,
                (int) Math.floor(origin.getY()), targetZ, world.getMinHeight(), world.getMaxHeight());
        if (groundY.isEmpty()) {
            plugin.getLogger().fine(() -> "No safe landing behind the portal for "
                    + rider.getName() + "; left them dismounted in place.");
            return;
        }

        Location target = new Location(world, targetX + 0.5D, groundY.getAsInt(), targetZ + 0.5D,
                origin.getYaw(), origin.getPitch());
        rider.teleportAsync(target).whenComplete((moved, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to reposition " + rider.getName() + " after a gated portal transit.",
                        failure);
            } else if (!Boolean.TRUE.equals(moved)) {
                plugin.getLogger().fine(() -> "The server declined to reposition " + rider.getName()
                        + " after a gated portal transit.");
            }
        });
    }

    /**
     * The {@link SafeRetreat.Terrain} probe over a live world.
     *
     * <p>Called from the vehicle's own region task, and only for a column two blocks from the
     * vehicle, so the chunks it reads are ones that region is already ticking.
     */
    private static SafeRetreat.Terrain terrainOf(World world) {
        return new SafeRetreat.Terrain() {
            @Override
            public boolean isPassable(int x, int y, int z) {
                return world.getBlockAt(x, y, z).isPassable();
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                Block block = world.getBlockAt(x, y, z);
                return block.getType().isSolid() && !block.isLiquid();
            }
        };
    }
}
