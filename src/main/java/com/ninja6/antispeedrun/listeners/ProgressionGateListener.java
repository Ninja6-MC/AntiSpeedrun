package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.Material;
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

    /**
     * Blocks {@link #terrainOf} reports as unfit to be put down in, beyond the fluids
     * {@code Block#isLiquid} already covers. Not exhaustive and not trying to be — it is the
     * handful an ejection two blocks from a portal could plausibly land in, and every one of them
     * would otherwise satisfy the collision check.
     *
     * <p>Two kinds of thing, and the second is the reason this is not simply called "hazards".
     * Most entries hurt: fire, powder snow, a cactus, a magma block one would stand <em>on</em>.
     * The three portal blocks do not hurt at all — they are here because a rider set down inside
     * the portal they were just refused is immediately offered the same transit again, and while
     * {@link #onPlayerPortal} does catch them on foot, the right answer is not to aim there in the
     * first place. Cheap to exclude, and it removes the churn.
     */
    private static final Set<Material> UNFIT_LANDINGS = Set.of(
            Material.LAVA,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.MAGMA_BLOCK,
            Material.POWDER_SNOW,
            Material.CACTUS,
            Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE,
            Material.NETHER_PORTAL,
            Material.END_PORTAL,
            Material.END_GATEWAY);

    private final AntiSpeedrunPlugin plugin;

    /**
     * When each player was last told they are gated, <em>per gate</em>. Registered with
     * {@link com.ninja6.antispeedrun.progression.PlayerStateRegistry} rather than held as a bare
     * map, so quit cleanup happens without this class remembering to do it — finding R-08.
     *
     * <p>Keyed on the dimension as well as the player because the two refusals are different news:
     * a player turned back from the Nether and then, seconds later, from the End would otherwise be
     * told nothing the second time, and the second refusal is the more surprising one. The inner
     * map is concurrent because a player's two gates can be evaluated from different region threads
     * over their session.
     */
    private final PlayerStateMap<Map<DimensionUnlock, Long>> lastFeedback;

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
     *
     * <h2>A mounted player, and why {@code willDismountPlayer()} is not consulted — #94</h2>
     *
     * The open question #93 left was whether this event fires for a player who is <em>riding</em>
     * something, and therefore whether this handler and {@link #onEntityPortal} are two
     * interception points for one transit or one of them is a hole. Both halves are now settled,
     * against the API and against upstream rather than against the method name.
     *
     * <p><strong>{@code willDismountPlayer()} cannot answer it, on this API or any later one.</strong>
     * On the pinned {@code paper-api 1.21.4} the backing {@code dismounted} field on
     * {@code PlayerTeleportEvent} is assigned {@code true} by every one of its constructors and has
     * no setter and no constructor parameter, so the method is a compile-time constant dressed as a
     * question — every {@code PlayerPortalEvent} answers {@code true} regardless of what the server
     * is about to do. Upstream agrees: it is deprecated for removal, with the note that
     * <em>dismounting on teleport is no longer controlled by the server</em>. Reading it would add a
     * branch that can never be taken.
     *
     * <p><strong>The transit itself is covered twice, not once.</strong> A passenger cannot start a
     * portal transit of its own — vanilla's {@code Entity#canUsePortal} refuses an entity that is
     * riding — so a mounted player only ever crosses because the <em>vehicle</em> crossed and
     * carried them. On Paper that produces both events: {@link EntityPortalEvent} for the vehicle
     * and this one for the passenger being taken along with it. So a mounted {@code
     * PlayerPortalEvent} always has an {@code EntityPortalEvent} beside it, and there is no mounted
     * case that reaches neither handler.
     *
     * <p>That is what makes {@link #pushBack}'s mounted early-return correct rather than merely
     * harmless: the vehicle path is what actually separates a mounted rider from the portal, and a
     * velocity nudge on a passenger would be inert even if it tried.
     *
     * <p>One upstream caveat, recorded so it is not rediscovered as a defect here: Folia's
     * asynchronous portal path is reported not to fire <em>either</em> event for a vehicle carrying
     * a passenger (PaperMC/Folia#453). That is a gap in the server, not in this listener — nothing
     * this class could do differently would see a transit it is never told about — and it closes
     * when upstream closes it.
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
        ejectAndReposition(vehicle, plan);
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
        Map<DimensionUnlock, Long> perGate = lastFeedback.computeIfAbsent(
                player.getUniqueId(), id -> new ConcurrentHashMap<>(2));
        long last = perGate.getOrDefault(dimension, 0L);
        if (now - last < FEEDBACK_COOLDOWN_MILLIS) {
            return;
        }
        perGate.put(dimension, now);

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
     *
     * <p>The heading is the player's <em>velocity</em>, not their look direction. Those differ
     * exactly when it matters: a player who walks backwards into a portal, or who turns to look
     * sideways as the event fires, would be nudged in a direction unrelated to the portal, and in
     * the backwards-walking case further into it. Look direction remains the fallback for a player
     * who is not moving at all, where there is nothing better to go on.
     */
    private void pushBack(Player player) {
        if (player.getVehicle() != null) {
            // Velocity applied to a passenger does nothing; the vehicle owns the movement. A
            // mounted player refused here has already been told why, and the vehicle path in
            // onEntityPortal is what actually separates them from the portal -- which it always
            // gets the chance to do, because a passenger never starts a portal transit by itself.
            // See onPlayerPortal's javadoc for #94's answer and the evidence behind it.
            return;
        }
        Vector travel = player.getVelocity();
        SafeRetreat.Offset offset =
                SafeRetreat.backwards(travel.getX(), travel.getZ(), PUSHBACK_STRENGTH);
        if (offset.isZero()) {
            Vector look = player.getLocation().getDirection();
            offset = SafeRetreat.backwards(look.getX(), look.getZ(), PUSHBACK_STRENGTH);
        }
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
     * Takes the blocked riders off the vehicle and puts them back where they were.
     *
     * <h2>Why the return position is computed here and not in the task</h2>
     *
     * The dismount cannot happen inline. Audit finding R-09 is explicit that mutating a passenger
     * list while the portal transfer is resolving produces ghost entities — a vehicle that arrives
     * without its rider, or a rider that arrives twice — so it is deferred by a tick.
     *
     * <p>But a mixed crew's transit is deliberately <em>not</em> cancelled, so by the time that
     * deferred work runs the blocked rider may already be standing in the dimension the gate just
     * refused them. A task that asked "where is this rider?" at that point would be told "the
     * Nether", and would set them down two blocks behind that — a chauffeur service into a sealed
     * dimension. {@link VehicleTransit#orders} exists to fix the answer <em>now</em>, on the event
     * thread, while it is still the Overworld; the task below is handed a position and computes
     * none.
     *
     * <p>The same reasoning covers the block reads. {@link #terrainOf} probes the source world, and
     * on Folia a region thread may only read the world it owns — after a transfer the rider's own
     * region is in the destination world and could not legally answer. Reading here, before
     * anything has moved, is both correct and the only legal moment.
     *
     * <p>The heading is likewise read now: by next tick a cancelled vehicle's velocity has been
     * zeroed and there would be no "backward" left to compute.
     *
     * <h2>Why the rider, not the vehicle, is the anchor</h2>
     *
     * The R-09 amendment says to schedule the dismount on the vehicle's region, and an earlier
     * revision did exactly that. The vehicle is the wrong anchor precisely when the vehicle is the
     * thing that leaves: a cross-dimensional transfer removes the entity in the source dimension,
     * and {@code EntityScheduler} answers a retired entity by running the <em>retired</em> callback
     * instead of the task. With that callback {@code null}, the whole ejection was dropped in
     * silence. Anchoring on the rider — who is the subject of the gate, and who survives — keeps
     * the work attached to something that will still be there, and the retired callback below is
     * non-{@code null} so that even the remaining case (the player logs out mid-transit) leaves a
     * line in the log rather than nothing.
     *
     * <h2>The ordering here is mirrored by a test, and the two must move together</h2>
     *
     * {@code Player} cannot be constructed off a server, so nothing can drive this method directly.
     * {@code VehicleTransitTest.Outcome#runTransit} therefore <em>re-enacts</em> the four steps
     * below — triage, capture, transit, deferred ejection — over a rider double, and asserts which
     * dimension each rider finishes in. That is a real test of the ordering, but it is a test of
     * the ordering as the harness spells it out, not as this method spells it out: if the capture
     * moved back inside the deferred work here, the harness would be untouched and would stay
     * green. A drifted harness that still passes is the failure mode, so any change to the sequence
     * below belongs in {@code runTransit} in the same commit. {@code
     * VehicleTransitTest.Outcome#returnPointIsCapturedEagerly} is the part that does bear on real
     * code, pinning {@link VehicleTransit#orders}' eagerness against the actual API.
     */
    private void ejectAndReposition(Entity vehicle, VehicleTransit.Plan<Player> plan) {
        Vector velocity = vehicle.getVelocity();
        double headingX = velocity.getX();
        double headingZ = velocity.getZ();

        for (VehicleTransit.Ejection<Player, Location> order :
                VehicleTransit.orders(plan, rider -> returnPointFor(rider, headingX, headingZ))) {
            scheduleEjection(order.rider(), order.returnTo());
        }
    }

    /**
     * Where a rider goes back to, decided against the world they are still in.
     *
     * <p>{@link SafeRetreat#landing} never answers "nowhere": with no usable heading, or no safe
     * ground behind the portal, it hands back the origin — the one spot known to be survivable,
     * because the rider was in it a moment ago. That matters here rather than being a nicety: with
     * the transit going ahead, declining to reposition is the same thing as carrying the rider
     * through the gate.
     */
    private Location returnPointFor(Player rider, double headingX, double headingZ) {
        Location origin = rider.getLocation();
        SafeRetreat.Offset offset =
                SafeRetreat.backwards(headingX, headingZ, SafeRetreat.EJECT_DISTANCE_BLOCKS);
        if (offset.isZero()) {
            // The vehicle was not moving in any particular direction, so there is no backward. Fall
            // back to the direction the rider is facing, which is where they were heading anyway.
            Vector look = origin.getDirection();
            offset = SafeRetreat.backwards(look.getX(), look.getZ(), SafeRetreat.EJECT_DISTANCE_BLOCKS);
        }

        World world = origin.getWorld();
        if (world == null) {
            return origin;
        }
        SafeRetreat.Landing landing = SafeRetreat.landing(origin.getX(), origin.getY(), origin.getZ(),
                offset, terrainOf(world), world.getMinHeight(), world.getMaxHeight());
        if (!landing.retreated()) {
            plugin.getLogger().fine(() -> "No safe landing behind the portal for " + rider.getName()
                    + "; returning them to where they boarded instead.");
        }
        return new Location(world, landing.x(), landing.y(), landing.z(),
                origin.getYaw(), origin.getPitch());
    }

    /**
     * Dismounts one rider and returns them to the position captured before the transit.
     *
     * <p>Runs next tick on the <strong>rider's</strong> region — see
     * {@link #ejectAndReposition} for why not the vehicle's — with a retired callback that logs,
     * so an ejection can no longer be dropped without trace.
     *
     * <p>{@code teleportAsync} rather than {@code teleport}: the destination is in the world the
     * rider started in, which after an uncancelled transit is a different world entirely, and
     * {@code Entity#teleport} throws on Folia the moment the destination leaves the current region.
     * The returned future is where the outcome is handled — a teleport the server declines is
     * logged rather than dropped, because a rider who was ejected but not moved is a rider the gate
     * did not actually stop.
     */
    private void scheduleEjection(Player rider, Location returnTo) {
        rider.getScheduler().run(plugin, task -> {
            if (!rider.isOnline()) {
                return;
            }
            if (rider.getVehicle() != null) {
                // Ordinarily redundant -- teleportAsync dismounts a passenger unless RETAIN_VEHICLE
                // is asked for -- but stated rather than relied upon, since a rider left aboard a
                // vehicle that did transit is the exact failure this method exists to prevent.
                rider.leaveVehicle();
            }
            rider.teleportAsync(returnTo).whenComplete((moved, failure) -> {
                if (failure != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to reposition " + rider.getName()
                            + " after a gated portal transit; they may have been carried through.",
                            failure);
                } else if (!Boolean.TRUE.equals(moved)) {
                    plugin.getLogger().warning("The server declined to reposition " + rider.getName()
                            + " after a gated portal transit; they may have been carried through.");
                }
            });
        }, () -> plugin.getLogger().warning("Could not eject " + rider.getName()
                + " at a gated portal: they left the server before the dismount ran."));
    }

    /**
     * The {@link SafeRetreat.Terrain} probe over a live world.
     *
     * <p>Called on the event thread, before any transfer resolves, and only for a column two blocks
     * from the vehicle — so the world it reads is the one the calling region owns and the chunks
     * are ones that region is already ticking.
     *
     * <p>Each method answers one plain question about one block. In particular {@code isPassable}
     * is Bukkit's collision question and nothing more: lava and water are passable, and it is
     * {@code isHazard} that says they are not somewhere to stand — along with the rest of
     * {@link #UNFIT_LANDINGS}, including the portal blocks. Composing the three is
     * {@link SafeRetreat}'s job, where a test can reach it.
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

            @Override
            public boolean isHazard(int x, int y, int z) {
                Block block = world.getBlockAt(x, y, z);
                return block.isLiquid() || UNFIT_LANDINGS.contains(block.getType());
            }
        };
    }

}
