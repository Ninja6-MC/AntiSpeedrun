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
import org.bukkit.event.player.PlayerChangedWorldEvent;
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
 * <p>Three routes into the Nether or the End, a backstop behind them, and one rule behind all four.
 *
 * <ul>
 *   <li><strong>{@link PlayerPortalEvent}</strong> (#34) — a player walking into a portal. Cancelled
 *       outright, with a nudge back out so the next tick does not simply re-fire it.</li>
 *   <li><strong>{@link EntityPortalEvent}</strong> (#35) — a boat, minecart or camel carrying
 *       riders. Triaged rather than cancelled: see {@link VehicleTransit}.</li>
 *   <li><strong>{@link PlayerTeleportEvent}</strong> (#6) — an Ender pearl thrown through a portal
 *       and pulled from a stasis chamber, and the other player-driven teleport causes that can
 *       cross a dimension boundary.</li>
 *   <li><strong>{@link PlayerChangedWorldEvent}</strong> (#100) — the backstop. Not a route in at
 *       all but the arrival itself, checked after the fact because Folia has a route in that fires
 *       none of the three above. See {@link #onPlayerChangedWorld}.</li>
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
 *   <li>All four events are single-entity events, so Folia calls them on the region owning that
 *       entity. Evaluating progression for a player, reading their bypass grant from their PDC and
 *       nudging their velocity are therefore all legal inline.</li>
 *   <li>A vehicle's passengers are in the vehicle's region by construction, so
 *       {@link #onEntityPortal} may evaluate them without hopping.</li>
 *   <li><strong>The dismount is not inline.</strong> Audit finding R-09: mutating the passenger
 *       list while the portal transfer is still resolving is a known source of ghost entities, so
 *       {@link #scheduleEjection} defers it a tick. It runs on the <strong>rider's</strong>
 *       {@code EntityScheduler}, not the vehicle's — #93 moved the anchor, because a vehicle that
 *       crosses a dimension boundary is retired in the source dimension and an {@code
 *       EntityScheduler} answers a retired entity by silently dropping the task. The reasoning is
 *       set out in full on {@link #ejectAndReposition}; this list is where a reader checks which
 *       entity owns the task, so it must not say otherwise.</li>
 *   <li><strong>Repositioning uses {@code teleportAsync}</strong> and continues in the returned
 *       future. {@code Entity#teleport} throws on Folia the moment the destination leaves the
 *       current region, and both a two-block retreat and a return to another world can cross a
 *       region boundary.</li>
 *   <li>Nothing here opens a file or touches a store on a region thread. The dimension-unlock
 *       override is an in-memory read, the bypass grant is a PDC read on the owning region, and the
 *       decision ledger behind the backstop is an in-memory {@link PlayerStateMap}.</li>
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
     * handful an ejection two blocks from a portal could plausibly land in. Most of them would
     * otherwise satisfy the collision check; {@code END_PORTAL} is the exception, being solid
     * enough already that {@code isPassable} refuses it without help, and it is listed for
     * completeness beside the other two portal blocks rather than because it is load-bearing.
     *
     * <p>Two kinds of thing, and the second is the reason this is not simply called "hazards".
     * Most entries hurt: fire, powder snow, a cactus, a magma block one would stand <em>on</em>.
     * The three portal blocks do not hurt at all — they are here because a rider set down inside
     * the portal they were just refused is immediately offered the same transit again, and while
     * {@link #onPlayerPortal} does catch them on foot, the right answer is not to aim there in the
     * first place. What this buys is narrow and worth stating plainly: wherever the search has
     * another standable column to offer, it will no longer choose one occupied by a portal. It
     * does not remove the churn in general, because {@link SafeRetreat#landing} falls back to the
     * origin when it finds nowhere at all, and the origin is the portal mouth.
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

    /**
     * When a handler last decided to let a player into a gated dimension they have <em>not</em>
     * earned and are <em>not</em> waived for, per gate.
     *
     * <p>This is the memory {@link #onPlayerChangedWorld} needs and nothing else reads. A world
     * change carries no cause and no history, so on its own it cannot tell the three deliberate
     * exemptions — an operator's {@code /tp}, #92's unresolved-destination fail-open, and a rider
     * the vehicle path is already repositioning — apart from the Folia transit that reports
     * nothing. Each of those three leaves a note here on its way past; the backstop consumes it.
     *
     * <p>Notes are written only where they are actually needed, which keeps the map near-empty in
     * ordinary play: an eligible or waived player never gets one, because the backstop re-reads
     * both rather than trusting a note. Registered with
     * {@link com.ninja6.antispeedrun.progression.PlayerStateRegistry} for quit cleanup — finding
     * R-08 — and every note also expires on its own after
     * {@link DimensionGateRules#DECISION_WINDOW_MILLIS}, so one that is never consumed (the transit
     * was cancelled downstream after all) cannot sit there covering a later arrival.
     */
    private final PlayerStateMap<Map<DimensionUnlock, Long>> decisions;

    public ProgressionGateListener(AntiSpeedrunPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lastFeedback = plugin.playerState().register("dimension-gate-feedback");
        this.decisions = plugin.playerState().register("dimension-gate-decisions");
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
     * <p>One upstream caveat, which is what {@link #onPlayerChangedWorld} exists for: Folia's
     * asynchronous portal path fires <em>neither</em> event for a vehicle carrying a passenger
     * (PaperMC/Folia#453). Nothing this handler could do differently would see a transit it is never
     * told about, so #100 stopped trying to intercept that case and checks the arrival instead.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Location to = event.getTo();
        if (to == null) {
            // Paper allows a null destination, which means the server has not resolved where this
            // portal goes. There is nothing to attribute to a gate, so the transit is left alone.
            // A deliberate fail-open, and a known silent-waiver path -- see #92. It is noted rather
            // than merely returned from, so that the backstop honours the same fail-open when the
            // player turns up somewhere gated a tick later instead of overturning it.
            noteDecisionForEveryGate(event.getPlayer());
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
        Entity vehicle = event.getEntity();
        List<Player> riders = ridersOf(vehicle);
        if (riders.isEmpty()) {
            return;
        }
        if (to == null) {
            // As in onPlayerPortal: no resolved destination, so no gate to apply. See #92, and see
            // noteDecisionForEveryGate for why the fail-open has to be written down.
            riders.forEach(this::noteDecisionForEveryGate);
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
        ejectAndReposition(vehicle, plan, dimension);
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
     *
     * <p>A cause <em>outside</em> {@link #GATED_CAUSES} is not simply returned from any more. The
     * exemption is deliberate and has to survive {@link #onPlayerChangedWorld}, which sees the
     * resulting arrival with no idea what caused it, so an admin's {@code /tp} into the Nether is
     * written down on the way past. Only cross-dimension teleports are noted, so the ordinary
     * intra-world plugin teleport still costs nothing but the {@link #kindOf} pair.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Player player = event.getPlayer();
        EnvironmentKind from = kindOf(event.getFrom());
        EnvironmentKind arriving = kindOf(to);
        PluginConfig config = plugin.configuration();
        if (!GATED_CAUSES.contains(event.getCause())) {
            if (from != arriving) {
                DimensionGateRules.gatedDestination(from, arriving, config)
                        .ifPresent(gate -> noteDecision(player, gate));
            }
            return;
        }
        Optional<DimensionUnlock> destination =
                DimensionGateRules.gatedDestination(from, arriving, config);
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
    // #100 - the backstop, for the transit Folia never reports
    // -------------------------------------------------------------------------------------------

    /**
     * A player who is already standing in another dimension — the gate's last line, and the only
     * one that runs after the fact.
     *
     * <h2>Why a fourth handler exists at all</h2>
     *
     * On Folia a player riding a boat, minecart or camel through a portal is carried across by an
     * asynchronous transit that fires <em>neither</em> {@link EntityPortalEvent} nor
     * {@link PlayerPortalEvent} (PaperMC/Folia#453). There is no event to cancel, no vehicle to
     * triage and no destination to inspect: the first and only thing this plugin is told is that the
     * player's world changed. That is a live bypass of a P0 gate on one of the two supported
     * platforms, so #100 closes it here rather than waiting on the server.
     *
     * <p>Paper does not have the hole — a passenger cannot start a portal transit of its own, so the
     * vehicle's transit produces both events and {@link #onEntityPortal} handles it — but this
     * handler is registered on both platforms deliberately. A backstop that only armed itself on
     * Folia would need to detect Folia, and the detection would be the thing that broke.
     *
     * <h2>Not double-handling what Paper already caught</h2>
     *
     * The requirement that makes this delicate is the second acceptance criterion on #100: a player
     * the gate legitimately let through must not then be bounced by the gate. Four kinds of arrival
     * are legitimate, and the verdict in {@link DimensionGateRules#arrival} turns on telling them
     * from the fifth:
     *
     * <ul>
     *   <li>The player <strong>meets the requirement</strong>. Re-evaluated here, not remembered, so
     *       a transit approved a tick ago is approved again for the same reason.</li>
     *   <li>The player is <strong>waived</strong> — permission, {@code /asr bypass},
     *       {@code /asr unlock}. Also re-read.</li>
     *   <li>An <strong>exemption was recorded</strong> in {@link #decisions}: an ungated teleport
     *       cause, #92's unresolved destination, or a rider {@link #onEntityPortal} has already
     *       ejected and is repositioning. That last one is the Paper double-handling case exactly —
     *       a mixed crew's transit is not cancelled, so a blocked rider really does arrive in the
     *       Nether for a tick before the deferred ejection puts them back, and without the note this
     *       handler would teleport them somewhere else first.</li>
     *   <li>The arrival is <strong>not gated</strong> — leaving the Nether, an Overworld-to-Overworld
     *       multiverse hop, a datapack dimension. {@link DimensionGateRules#gatedDestination}
     *       answers that, on kinds rather than worlds, as everywhere else in this class.</li>
     * </ul>
     *
     * <p>Anything else is a player standing somewhere nothing ever cleared them for, which is the
     * bypass. They are told why, on the same per-gate cooldown as every other refusal, and returned.
     *
     * <h2>Folia region threading</h2>
     *
     * {@code PlayerChangedWorldEvent} is a single-entity event, so Folia calls it on the region that
     * now owns the player, in the destination world. Legal inline, and all of it done inline: the
     * progression evaluation (a cache read), the bypass grant (this player's own PDC), the
     * dimension-unlock override (in memory), the ledger (in memory), and the message. Illegal, and
     * therefore not done: reading a block in the world they came from — the source world belongs to
     * another region and this thread may not touch it, which is why the return point is the source
     * world's spawn and not a {@link SafeRetreat} probe behind the portal.
     *
     * <p>The return itself is deferred to the player's own {@code EntityScheduler} and performed
     * with {@code teleportAsync}, by way of {@link #scheduleEjection}. Deferred because moving a
     * player from inside the notification that they have just been moved is the same hazard R-09
     * describes; {@code teleportAsync} because the destination is in another world, and
     * {@code Entity#teleport} throws on Folia the moment it leaves the region.
     *
     * <p>{@code HIGHEST} rather than {@code MONITOR}: this handler acts, and {@code MONITOR} is for
     * observers. It is nonetheless the last priority that acts, so a hub or spawn plugin with its
     * own world-change handling has already had its say. The event is not cancellable, so the
     * priority buys ordering and nothing else.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World arrivedIn = player.getWorld();
        World cameFrom = event.getFrom();
        PluginConfig config = plugin.configuration();
        Optional<DimensionUnlock> destination = DimensionGateRules.gatedDestination(
                kindOf(cameFrom), kindOf(arrivedIn), config);
        if (destination.isEmpty()) {
            return;
        }
        DimensionUnlock dimension = destination.get();

        boolean decided = consumeDecision(player, dimension);
        boolean waived = waived(player, dimension);
        // Evaluated even when the answer is already settled, so that the verdict is composed in one
        // place rather than short-circuited here in a second, silently divergent order. It is a
        // ProgressionCache lookup, and only for arrivals in a gated dimension.
        EligibilityResult result = evaluate(player, config, dimension);
        if (DimensionGateRules.arrival(decided, waived, result.eligible())
                == DimensionGateRules.Arrival.ALLOWED) {
            return;
        }

        reject(player, config, dimension, result);
        plugin.getLogger().fine(() -> "Returning " + player.getName() + " from " + arrivedIn.getName()
                + ": they arrived without passing the " + dimension + " gate, which on Folia means a"
                + " vehicle carried them through a portal the server reported no event for.");
        scheduleEjection(player, cameFrom.getSpawnLocation());
    }

    /**
     * Records that this player has been let into {@code dimension} without earning it, so that
     * {@link #onPlayerChangedWorld} does not overturn the decision a moment later.
     */
    private void noteDecision(Player player, DimensionUnlock dimension) {
        decisions.computeIfAbsent(player.getUniqueId(), id -> new ConcurrentHashMap<>(2))
                .put(dimension, System.currentTimeMillis());
    }

    /**
     * As {@link #noteDecision}, for both gates at once.
     *
     * <p>Used only where the destination is genuinely unknown: a portal event whose {@code getTo()}
     * is {@code null} has not been resolved by the server yet, so the fail-open #92 documents cannot
     * name which dimension it is failing open into. Noting both is what makes the backstop agree
     * with that fail-open instead of quietly closing it, which would be a behaviour change smuggled
     * in behind a bug fix. The note still expires on its own.
     */
    private void noteDecisionForEveryGate(Player player) {
        for (DimensionUnlock dimension : DimensionUnlock.values()) {
            noteDecision(player, dimension);
        }
    }

    /**
     * Takes any live note for this player and gate, and removes it.
     *
     * <p>Consumed rather than merely read: a note covers <em>one</em> arrival. Leaving it in place
     * would have a single admin {@code /tp} clear every unreported transit for the next ten seconds.
     */
    private boolean consumeDecision(Player player, DimensionUnlock dimension) {
        Map<DimensionUnlock, Long> notes = decisions.get(player.getUniqueId()).orElse(null);
        if (notes == null) {
            return false;
        }
        Long recordedAt = notes.remove(dimension);
        return recordedAt != null
                && DimensionGateRules.decisionHolds(recordedAt, System.currentTimeMillis());
    }

    // -------------------------------------------------------------------------------------------
    // Shared decision plumbing
    // -------------------------------------------------------------------------------------------

    private static EnvironmentKind kindOf(World world) {
        return world == null ? EnvironmentKind.CUSTOM : EnvironmentKind.of(world.getEnvironment().name());
    }

    private static EnvironmentKind kindOf(Location location) {
        return location == null ? EnvironmentKind.CUSTOM : kindOf(location.getWorld());
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
    private void ejectAndReposition(Entity vehicle, VehicleTransit.Plan<Player> plan,
                                    DimensionUnlock dimension) {
        Vector velocity = vehicle.getVelocity();
        double headingX = velocity.getX();
        double headingZ = velocity.getZ();

        for (VehicleTransit.Ejection<Player, Location> order :
                VehicleTransit.orders(plan, rider -> returnPointFor(rider, headingX, headingZ))) {
            if (!plan.cancelTransit()) {
                // The transit is going ahead, so this rider will arrive in the gated dimension for a
                // tick before the ejection below puts them back. The backstop must let that stand;
                // the ejection is already handling them and a second return would fight it. Noted
                // only when the vehicle really moves -- a cancelled transit produces no arrival, so
                // a note there would be a live exemption nothing ever consumes.
                noteDecision(order.rider(), dimension);
            }
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
     * Dismounts one player and returns them to a position decided by the caller.
     *
     * <p>Two callers, one shape of problem. {@link #ejectAndReposition} hands over a point captured
     * before the transit resolved; {@link #onPlayerChangedWorld} hands over the spawn of the world
     * the player came from, having no captured point to offer. Neither computes anything here.
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
