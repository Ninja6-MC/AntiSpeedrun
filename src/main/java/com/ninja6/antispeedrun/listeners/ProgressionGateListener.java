package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.util.Vector;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
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
 *       cross a dimension boundary. Two handlers on the one event, and the split is deliberate:
 *       {@link #onPlayerTeleport} cancels at {@code HIGH}, {@link #onPlayerTeleportSettled} records
 *       an exemption at {@code MONITOR}, where the destination can no longer change.</li>
 *   <li><strong>{@link EntityAddToWorldEvent}</strong> (#100, #127) — the backstop. Not a route in
 *       at all but the arrival itself, checked after the fact because Folia has routes in that fire
 *       none of the three above. It was {@code PlayerChangedWorldEvent} until #127, which Folia
 *       never fires; see {@link #onPlayerAddedToWorld} for why this event is the one both
 *       platforms fire.</li>
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
 *       entity — for {@link EntityAddToWorldEvent}, the region that owns the position it is being
 *       added at. Evaluating progression for a player, reading their bypass grant from their PDC and
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
     * earned and are <em>not</em> waived for, per gate, and <em>which world</em> it decided that
     * about.
     *
     * <p>This is the memory {@link #onPlayerAddedToWorld} needs and nothing else reads. A world
     * change carries no cause and no history, so on its own it cannot tell the two deliberate
     * exemptions — an operator's {@code /tp} and a rider the vehicle path is already repositioning
     * — apart from the Folia transit that reports nothing. Each of those leaves a note here on its
     * way past; the backstop consumes it. On Folia the {@code /tp} note is written from the command
     * line or by a plugin through {@link #expectTeleport}, because no teleport event fires there
     * (#135); see {@link #noteCommandTeleport} for what bounds a note written before the teleport
     * has happened.
     *
     * <h2>A note belongs to one transit, and three rules keep it there</h2>
     *
     * A record that outlives the decision it records is a free pass, on a gate whose failure mode is
     * a bypass. So, as far as they reach — the list below is what bounds a note, not a proof that
     * none can outlive its transit; the known remainder follows it:
     *
     * <ul>
     *   <li><strong>It names the destination.</strong> {@link DimensionGateRules.Decision} carries
     *       the world the deciding handler saw, and {@link DimensionGateRules#decisionCovers} will
     *       not spend it on an arrival anywhere else. Without that, a note written by one transit
     *       covers any arrival at all through that gate — including the unreported vehicle transit
     *       this class exists to catch.</li>
     *   <li><strong>It is written where the outcome is settled</strong>, never on an intention. See
     *       {@link #onPlayerTeleportSettled}: a teleport a later handler cancels or redirects must
     *       leave nothing behind. The exception is the deliberate teleport on Folia, which has no
     *       settled point to write at: {@link #noteCommandTeleport} and {@link #expectTeleport}
     *       write before the teleport, and say what bounds that instead.</li>
     *   <li><strong>It expires and it is consumed.</strong> One arrival per note, and none at all
     *       after {@link DimensionGateRules#DECISION_WINDOW_MILLIS}.</li>
     * </ul>
     *
     * <p>What the three rules do not cover:
     *
     * <ul>
     *   <li><strong>A teleport that is settled but never completes.</strong> A teleport that reaches
     *       {@code MONITOR} uncancelled and then fails server-side — the destination chunk never
     *       loads, the player disconnects mid-transfer — leaves a note correctly bound to a world
     *       the player never reached. Spending it needs an unreported transit into that same world,
     *       through that same gate, inside the window. Far narrower than an unbound note, but not
     *       nothing.</li>
     *   <li><strong>A destination changed after the note was written.</strong> See
     *       {@link #onPlayerTeleportSettled} for the same-priority case on the teleport path, and
     *       {@link #ejectAndReposition} for the vehicle path, whose note is written at
     *       {@code HIGH}.</li>
     * </ul>
     *
     * <p>Only the vehicle path consults eligibility before writing: {@link #ejectAndReposition}
     * notes only the riders it is ejecting. {@link #onPlayerTeleportSettled} notes <em>every</em>
     * ungated-cause teleport across a gate, eligible, waived or neither, because deciding which
     * would cost a progression evaluation on a path that needs none. The spare notes are harmless:
     * the backstop re-reads eligibility and the waiver on arrival and lets either through whatever
     * the ledger says, and it consumes the note on the way past, so it does not linger. Registered
     * with
     * {@link com.ninja6.antispeedrun.progression.PlayerStateRegistry} for quit cleanup — finding
     * R-08.
     */
    private final PlayerStateMap<Map<DimensionUnlock, DimensionGateRules.Decision>> decisions;

    /**
     * The world each player was last added to, by UID — the "from" that
     * {@link EntityAddToWorldEvent} does not carry.
     *
     * <p>Written by {@link #onPlayerAddedToWorld} on every add, so it follows the player through
     * every dimension change, respawn and cross-region move. Only the player's own region ever
     * writes their entry, and it does so inside the handler, so the read and the write cannot
     * interleave with another add for the same player. Registered with
     * {@link com.ninja6.antispeedrun.progression.PlayerStateRegistry} for quit cleanup — finding
     * R-08 — which is also what makes a rejoin read as a join rather than a world change.
     */
    private final PlayerStateMap<UUID> lastWorld;

    public ProgressionGateListener(AntiSpeedrunPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lastFeedback = plugin.playerState().register("dimension-gate-feedback");
        this.decisions = plugin.playerState().register("dimension-gate-decisions");
        this.lastWorld = plugin.playerState().register("dimension-gate-last-world");
        seedLastWorld();
    }

    /**
     * Records where every player already online is standing, for an enable while the server is
     * running.
     *
     * <p>Those players were added to their world before this listener existed, so without this
     * their next dimension change would find nothing on record and read as a join, which the
     * backstop does not judge. Each read runs on the player's own {@code EntityScheduler}, the
     * only thread that may ask a Folia player which world they are in, and uses
     * {@code putIfAbsent} so an add that got there first is not overwritten with an older answer.
     */
    private void seedLastWorld() {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            online.getScheduler().run(plugin, task -> {
                if (online.isOnline()) {
                    lastWorld.putIfAbsent(online.getUniqueId(), online.getWorld().getUID());
                }
            }, null);
        }
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
     * <p>One upstream caveat, which is what {@link #onPlayerAddedToWorld} exists for: Folia's
     * asynchronous portal path fires <em>neither</em> event for a vehicle carrying a passenger
     * (PaperMC/Folia#453). Nothing this handler could do differently would see a transit it is never
     * told about, so #100 stopped trying to intercept that case and checks the arrival instead. On
     * Folia 1.21.4 the gap is wider than #453 describes; see <em>What Folia reports</em> on that
     * handler.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Location to = event.getTo();
        if (to == null) {
            // Paper allows a null destination, which means the server has not resolved where this
            // portal goes. There is nothing to attribute to a gate, so the transit is left alone:
            // the deliberate fail-open #92 documents, and it is untouched here.
            //
            // What is deliberately *not* done is writing a note. An unresolved destination cannot
            // name the world it is failing open into, so any note would have to cover every gate
            // and every world -- a pass spendable on a transit that decided nothing, which is the
            // unreported Folia vehicle transit onPlayerAddedToWorld exists to catch. #92's
            // fail-open is "this transit is not cancelled", and that survives the return below;
            // it was never "this player is cleared for wherever they turn up next".
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
        pushBack(player);
        reject(player, config, dimension, result);
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
            // As in onPlayerPortal: no resolved destination, so no gate to apply and no note to
            // write. See #92, and see that handler for why an unresolved destination cannot leave
            // an exemption behind.
            return;
        }

        PluginConfig config = plugin.configuration();
        Optional<DimensionUnlock> destination =
                DimensionGateRules.gatedDestination(kindOf(event.getFrom()), kindOf(to), config);
        if (destination.isEmpty()) {
            return;
        }
        DimensionUnlock dimension = destination.get();

        // Recorded in the triage pass rather than re-evaluated in a second loop, so the message and
        // the verdict cannot disagree about who was blocked. Sent only once the transit is
        // cancelled and the ejection scheduled, so a message that fails cannot let a rider through.
        Map<Player, EligibilityResult> refused = new LinkedHashMap<>(2);
        VehicleTransit.Plan<Player> plan = VehicleTransit.plan(riders, rider -> {
            if (waived(rider, dimension)) {
                return false;
            }
            EligibilityResult result = evaluate(rider, config, dimension);
            if (result.eligible()) {
                return false;
            }
            refused.put(rider, result);
            return true;
        });
        if (plan.isNoOp()) {
            return;
        }
        if (plan.cancelTransit()) {
            event.setCancelled(true);
        }
        ejectAndReposition(vehicle, plan, dimension, to.getWorld());
        refused.forEach((rider, result) -> reject(rider, config, dimension, result));
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
     * <p>A cause <em>outside</em> {@link #GATED_CAUSES} is exempt, and that exemption has to survive
     * {@link #onPlayerAddedToWorld}, which sees the resulting arrival with no idea what caused it.
     * Writing it down is not this handler's job, though — see {@link #onPlayerTeleportSettled}.
     * Nothing is recorded here, because nothing is settled here.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (!GATED_CAUSES.contains(event.getCause())) {
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

    /**
     * The same teleport, once the server has finished deciding what to do with it — the note for an
     * ungated cause is written here and nowhere else.
     *
     * <h2>Why not at {@code HIGH}, beside the cancellation</h2>
     *
     * Because at {@code HIGH} the teleport has not happened and may yet not. {@code ignoreCancelled}
     * only covers a cancellation that has <em>already</em> occurred, and both of the things that
     * come after are ordinary server behaviour:
     *
     * <ul>
     *   <li>A {@code HIGHEST} handler <strong>cancels</strong> the teleport — a protection or region
     *       plugin refusing a destination. A note written at {@code HIGH} is then an exemption
     *       nothing ever consumes, live for the rest of its window and spendable on any arrival
     *       through that gate, including the unreported Folia vehicle transit. That is the bypass
     *       #100 closes, re-opened by the mechanism that closes it.</li>
     *   <li>A {@code HIGHEST} handler <strong>redirects</strong> it with {@code setTo()} — a hub
     *       plugin. A note written at {@code HIGH} then names the wrong destination, so the arrival
     *       that really happens is bounced while the note sits waiting for one that never comes.</li>
     * </ul>
     *
     * <p>{@code MONITOR} with {@code ignoreCancelled} is the priority at which neither is possible
     * for a well-behaved plugin: no later <em>priority</em> exists, a cancelled teleport never
     * reaches it, and {@code getTo()} is ordinarily the destination the player will arrive in. The
     * handler observes and records; it decides nothing and cancels nothing, which is what
     * {@code MONITOR} is for.
     *
     * <p>That is a guarantee about priorities, not about handlers. Within one priority Bukkit runs
     * handlers in registration order, so a plugin registered after this one that mutates the event
     * at {@code MONITOR} — against the convention, but nothing enforces it — still lands after this
     * handler. A later {@code setTo()} into another world leaves a note naming a world the player
     * never reaches: the arrival that does happen finds no note, and an ineligible, unwaived player
     * the other plugin deliberately teleported is returned. That is the mirror image of the defect
     * the move to {@code MONITOR} fixed, and it fails closed. A later cancellation is the other
     * way round: an orphaned note, bounded by the rules on {@link #decisions}.
     *
     * <p>Only cross-dimension teleports into a gated dimension are noted, so the ordinary intra-world
     * plugin teleport costs nothing but the {@link #kindOf} pair. A gated cause is skipped outright,
     * because an ineligible player's gated-cause teleport was cancelled above. An ungated cause is
     * noted <em>without</em> consulting eligibility or the waiver: an eligible or waived player gets
     * a note they do not need, which the backstop consumes and ignores, since it re-reads both.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleportSettled(PlayerTeleportEvent event) {
        if (GATED_CAUSES.contains(event.getCause())) {
            return;
        }
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        EnvironmentKind from = kindOf(event.getFrom());
        EnvironmentKind arriving = kindOf(to);
        if (from == arriving) {
            return;
        }
        Player player = event.getPlayer();
        DimensionGateRules.gatedDestination(from, arriving, plugin.configuration())
                .ifPresent(gate -> noteDecision(player, gate, to.getWorld()));
    }

    // -------------------------------------------------------------------------------------------
    // #135 - deliberate teleports on Folia, which fire no PlayerTeleportEvent
    // -------------------------------------------------------------------------------------------

    /**
     * A player's command line, read for a vanilla {@code /tp} before it runs.
     *
     * <p>This is how an operator's cross-dimension {@code /tp} is told apart from an unreported
     * transit on Folia, where {@link #onPlayerTeleportSettled} never runs because
     * {@code teleportAsync} fires no {@code PlayerTeleportEvent}. {@link TeleportCommandLine} says
     * why the command line is the only place left to see the difference, and which forms it reads.
     *
     * <p>{@code MONITOR} with {@code ignoreCancelled}, for the reason {@link #onPlayerTeleportSettled}
     * gives: a command another plugin refuses must leave nothing behind. On Paper the same
     * {@code /tp} fires {@code PlayerTeleportEvent} as well and the settled handler writes the same
     * note again, which replaces this one; the command hook is registered on both platforms for the
     * reason the backstop is, not because Paper needs it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        noteCommandTeleport(event.getPlayer(), event.getMessage());
    }

    /** The same, for the console, RCON and command blocks. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        noteCommandTeleport(event.getSender(), event.getCommand());
    }

    /**
     * Notes every player a teleport command is about to move into a gated dimension.
     *
     * <h2>What bounds a note written here</h2>
     *
     * This is written on an intention — the command has not run yet — which {@link #decisions}
     * otherwise forbids. Three things keep it from being a pass:
     *
     * <ul>
     *   <li><strong>The sender must be able to teleport.</strong> Every permission
     *       {@link TeleportCommandLine.Teleport#permissions} names, which is what Paper's vanilla
     *       command wrapper checks. Without this a player could type a teleport they are refused
     *       and spend the note on a boat through the portal. With it, only someone who could have
     *       moved the player across the gate anyway can write one.</li>
     *   <li><strong>It is bound to the destination world and to the named players</strong>, as every
     *       note is, and it expires and is consumed like any other.</li>
     *   <li><strong>An unreadable line writes nothing.</strong> A selector, a destination or a
     *       dimension that does not resolve to exactly one answer is dropped, so the arrival is
     *       judged as before.</li>
     * </ul>
     *
     * <p>The line is read before the command runs, so a teleport the server refuses, or runs zero
     * times, still leaves a note. {@link #expectCommandTeleport} retracts a note nothing has
     * consumed within {@link DimensionGateRules#COMMAND_CONFIRM_TICKS}, whatever the parser made of
     * the line. What remains is that short interval, and the same remainder {@link #decisions}
     * documents for a teleport that is settled but never completes.
     *
     * <h2>Folia</h2>
     *
     * A player's command runs on their region, the console's on the global region. Neither owns the
     * players a command may name. What this reads of them — which world they are in — is the
     * entity's level reference, which Folia does not thread-check; a stale read names the wrong
     * world, and a note for the wrong world covers nothing. Resolving a selector goes through the
     * same vanilla code the command itself is about to run on this thread. The ledger is concurrent.
     */
    private void noteCommandTeleport(CommandSender sender, String commandLine) {
        Optional<TeleportCommandLine.Teleport> parsed = TeleportCommandLine.parse(commandLine);
        if (parsed.isEmpty()) {
            return;
        }
        TeleportCommandLine.Teleport teleport = parsed.get();
        for (String permission : teleport.permissions()) {
            if (!sender.hasPermission(permission)) {
                return;
            }
        }
        Optional<World> destination = destinationWorld(sender, teleport.destination());
        if (destination.isEmpty()) {
            return;
        }
        for (Entity target : entities(sender, teleport.targets())) {
            if (target instanceof Player player) {
                expectCommandTeleport(player, destination.get());
            }
        }
    }

    /**
     * Notes a teleport read from a command line, and takes the note back if nothing has spent it
     * after {@link DimensionGateRules#COMMAND_CONFIRM_TICKS}.
     *
     * <p>The line is read before the command runs, so the parser cannot know whether the server
     * refused it or ran it zero times (an {@code execute as} that selected nobody, a malformed
     * argument). Rather than reproduce Brigadier to guess, the note is confirmed by its effect: a
     * teleport that happened has put the player into the noted world, and that arrival consumed the
     * note. A note still present later belongs to a command that did nothing, and is retracted.
     * Only that exact note is retracted, so a note written meanwhile stays.
     */
    private void expectCommandTeleport(Player player, World destination) {
        Optional<DimensionUnlock> gate = DimensionGateRules.gatedDestination(
                kindOf(player.getWorld()), kindOf(destination), plugin.configuration());
        if (gate.isEmpty()) {
            return;
        }
        DimensionUnlock dimension = gate.get();
        UUID destinationId = destination.getUID();
        long recordedAt = System.currentTimeMillis();
        DimensionGateRules.note(
                decisions.computeIfAbsent(player.getUniqueId(), id -> new ConcurrentHashMap<>(2)),
                dimension, destinationId, recordedAt);
        // A player gone by then has no ledger left to clean, so there is no retired callback.
        player.getScheduler().runDelayed(plugin, task -> DimensionGateRules.retract(
                        decisions.get(player.getUniqueId()).orElse(null), dimension, destinationId,
                        recordedAt),
                null, DimensionGateRules.COMMAND_CONFIRM_TICKS);
    }

    /**
     * Tells the gate that {@code player} is about to be teleported into {@code destination} on
     * purpose, so that the arrival is not returned — #135.
     *
     * <p>For a plugin that moves players between dimensions. On Paper it is unnecessary: the
     * teleport fires {@code PlayerTeleportEvent}, and {@link #onPlayerTeleportSettled} notes any
     * cause this plugin does not regulate. On Folia {@code teleportAsync} fires nothing, so without
     * this call the arrival of a player who has not met the gate is indistinguishable from the
     * unreported vehicle transit, and is returned.
     *
     * <p>Call it immediately before the teleport. It covers one arrival, in that world, within
     * {@link DimensionGateRules#DECISION_WINDOW_MILLIS}; a teleport that does not happen leaves
     * the note to expire. It does nothing for a destination the player's current dimension does not
     * gate. Safe from any thread: it reads the world the player is in, and writes a concurrent map.
     *
     * @param player      who is being moved
     * @param destination the world they are being moved into
     */
    public void expectTeleport(Player player, World destination) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(destination, "destination");
        DimensionGateRules.gatedDestination(kindOf(player.getWorld()), kindOf(destination),
                        plugin.configuration())
                .ifPresent(gate -> noteDecision(player, gate, destination));
    }

    /** The world a command's destination is in, if it resolves to exactly one. */
    private Optional<World> destinationWorld(CommandSender sender,
                                             TeleportCommandLine.Destination destination) {
        if (destination instanceof TeleportCommandLine.ToEntity toEntity) {
            List<Entity> found = entities(sender, toEntity.entity());
            return found.size() == 1 ? Optional.of(found.get(0).getWorld()) : Optional.empty();
        }
        TeleportCommandLine.Place place = ((TeleportCommandLine.ToCoordinates) destination).place();
        if (place instanceof TeleportCommandLine.Dimension dimension) {
            NamespacedKey key = NamespacedKey.fromString(dimension.key());
            return key == null ? Optional.empty() : Optional.ofNullable(plugin.getServer().getWorld(key));
        }
        TeleportCommandLine.Ref of = ((TeleportCommandLine.WorldOf) place).entity();
        if (of instanceof TeleportCommandLine.Sender && !(sender instanceof Entity)) {
            // A command block runs in its own world; the console and RCON in the overworld.
            return Optional.of(sender instanceof BlockCommandSender block
                    ? block.getBlock().getWorld()
                    : plugin.getServer().getWorlds().get(0));
        }
        List<Entity> found = entities(sender, of);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        World world = found.get(0).getWorld();
        for (Entity entity : found) {
            if (!entity.getWorld().equals(world)) {
                return Optional.empty();
            }
        }
        return Optional.of(world);
    }

    /**
     * The entities a command token names, or none if it does not resolve.
     *
     * <p>A name or UUID is looked up among online players only. Vanilla would accept any entity
     * for a UUID, but only a player can be gated, and looking an arbitrary entity up by UUID is not
     * a read Folia lets any thread make.
     */
    private List<Entity> entities(CommandSender sender, TeleportCommandLine.Ref ref) {
        if (ref instanceof TeleportCommandLine.Sender) {
            return sender instanceof Entity self ? List.of(self) : List.of();
        }
        String text = ((TeleportCommandLine.Token) ref).text();
        if (text.startsWith("@")) {
            try {
                return plugin.getServer().selectEntities(sender, text);
            } catch (IllegalArgumentException | IllegalStateException unresolvable) {
                // A selector that does not parse, or one Folia will not evaluate on this thread.
                return List.of();
            }
        }
        Player player;
        try {
            player = plugin.getServer().getPlayer(UUID.fromString(text));
        } catch (IllegalArgumentException notUuid) {
            player = plugin.getServer().getPlayerExact(text);
        }
        return player == null ? List.of() : List.of(player);
    }

    // -------------------------------------------------------------------------------------------
    // #100, #127 - the backstop, for the transits Folia never reports
    // -------------------------------------------------------------------------------------------

    /**
     * A player who has just been put into a world — the gate's last line, and the only one that
     * runs after the fact.
     *
     * <h2>Why a fourth handler exists at all</h2>
     *
     * On Folia a player riding a boat, minecart or camel through a portal is carried across by an
     * asynchronous transit that fires <em>neither</em> {@link EntityPortalEvent} nor
     * {@link PlayerPortalEvent} (PaperMC/Folia#453). There is no event to cancel, no vehicle to
     * triage and no destination to inspect: the first and only thing this plugin can be told is that
     * the player is now in another world. That is a live bypass of a P0 gate on one of the two
     * supported platforms, so #100 closes it here rather than waiting on the server.
     *
     * <p>Paper does not have the hole — a passenger cannot start a portal transit of its own, so the
     * vehicle's transit produces both events and {@link #onEntityPortal} handles it — but this
     * handler is registered on both platforms deliberately. A backstop that only armed itself on
     * Folia would need to detect Folia, and the detection would be the thing that broke.
     *
     * <h2>Why this event and not {@code PlayerChangedWorldEvent} — #127</h2>
     *
     * #100 put the backstop on {@code PlayerChangedWorldEvent}, and #114 found that Folia never
     * fires it: Paper fires it only from {@code ServerPlayer#teleport(TeleportTransition)} and
     * {@code PlayerList#respawn}, Folia's region-threading patch makes the first throw and replaces
     * both with {@code Entity#placeInAsync}, {@code ServerPlayer#placeSingleSync} and a respawn of
     * its own, and none of those fires it. So until #127 the backstop acted on Paper only.
     *
     * <p>{@link EntityAddToWorldEvent} is fired from the one step every one of those paths shares:
     * putting the player into the destination world. Paper fires it from
     * {@code ServerLevel.EntityCallbacks#onTrackingStart}, which runs whenever an entity is added to
     * a level's entity lookup. On Paper the dimension change reaches that through
     * {@code ServerLevel#addDuringTeleport}, immediately before the {@code PlayerChangedWorldEvent}
     * this handler replaces, and a respawn through {@code addRespawnedPlayer}. On Folia
     * {@code placeSingleSync} calls {@code addDuringTeleport} too, for a portal transit and a
     * {@code teleportAsync} alike, and Folia's patch to {@code onTrackingStart} only prepends its own
     * bookkeeping. Read on Paper's and Folia's {@code ver/1.21.4}, the API this plugin compiles
     * against, and observed on a live Folia 1.21.4 server for a login, a respawn, and a Nether
     * portal crossed on foot and in a boat — #127 records how.
     *
     * <p>The event does not say where the player came from, which the gate needs twice over: to
     * tell a dimension change from a same-world move, and to know where to send them back.
     * {@link #lastWorld} answers that, and {@link DimensionGateRules#departure} turns it into the
     * source world, or into nothing when there was no change to judge. The event fires for every
     * entity added to every world, chunk loads included, so a non-player costs one
     * {@code instanceof}.
     *
     * <h2>What Folia reports, and what that makes of this handler there</h2>
     *
     * The same reading of Folia 1.21.4 says the gap is wider than #453. Folia's
     * {@code Entity#handlePortal} no longer calls {@code getPortalDestination}, which is where Paper
     * fires both portal events, and neither Folia patch set fires either event anywhere else; its
     * {@code Entity#teleportAsync} fires no {@code PlayerTeleportEvent} either. So on Folia the three
     * handlers above are not called for a portal, on foot or mounted, nor for a pearl, and this
     * handler is the gate. Two consequences follow, both fail-closed:
     *
     * <ul>
     *   <li>An ineligible player who walks into a portal on Folia is returned to the source world's
     *       spawn from the other side, rather than nudged back out of the portal.</li>
     *   <li>{@link #onPlayerTeleportSettled} never runs on Folia, so the {@link #GATED_CAUSES}
     *       allow-list cannot be honoured on a server that never says what caused a teleport. A
     *       cross-dimension teleport is exempt on Folia only when something else vouched for it
     *       first (#135): a vanilla {@code /tp} read off the command line by
     *       {@link #onPlayerCommand} or {@link #onServerCommand}, or a plugin calling
     *       {@link #expectTeleport}. Anything else — a {@code /tp} form the command reader does not
     *       follow, another plugin's teleport that does not call in, a pearl — is returned from an
     *       ineligible, unwaived player, because at the arrival it is indistinguishable from the
     *       unreported vehicle transit.</li>
     * </ul>
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
     *   <li>An <strong>exemption was recorded</strong> in {@link #decisions} <em>for this world</em>:
     *       an ungated teleport cause (on Folia, a {@code /tp} or a plugin that vouched for it —
     *       #135), or a rider {@link #onEntityPortal} has already
     *       ejected and is repositioning. The second is the Paper double-handling case exactly —
     *       a mixed crew's transit is not cancelled, so a blocked rider really does arrive in the
     *       Nether for a tick before the deferred ejection puts them back, and without the note this
     *       handler would teleport them somewhere else first. Both notes are written by events that
     *       fire before the player is added to the destination world, so moving from
     *       {@code PlayerChangedWorldEvent} to this event changes nothing about which arrivals find
     *       one.</li>
     *   <li>The arrival is <strong>not gated</strong> — leaving the Nether, an Overworld-to-Overworld
     *       multiverse hop, a datapack dimension. {@link DimensionGateRules#gatedDestination}
     *       answers that, on kinds rather than worlds, as everywhere else in this class.</li>
     * </ul>
     *
     * <p>Anything else is a player standing somewhere nothing ever cleared them for, which is the
     * bypass. They are told why, on the same per-gate cooldown as every other refusal, and returned.
     *
     * <h2>A fifth arrival that is not a transit, and is returned on purpose</h2>
     *
     * A respawn in another world adds the player to it like any transit, and that path fires no
     * {@code PlayerTeleportEvent} at all, so it leaves no note. A player who dies in the Overworld
     * and respawns at a Nether anchor therefore reaches the check with nothing recorded, and if they
     * are ineligible and unwaived they are returned to the Overworld. That is the answer this
     * handler intends, not a case it forgot: an anchor set while the player was waived is a standing
     * re-entry into a dimension the gate now closes to them, and the gate is not a one-time toll.
     * The anchor survives; only the arrival is undone, and they are told why like anyone else.
     *
     * <p>Logging in goes the other way. Joining adds the player to a world too, but there is no
     * earlier world on record for this session, so {@link DimensionGateRules#departure} reports no
     * change and a player who logs out in the Nether and back in is not judged. That is a hole by
     * omission rather than a decision, and closing it would mean checking on join — #100's scope is
     * the transit Folia does not report.
     *
     * <h2>Folia region threading</h2>
     *
     * The event is fired by the thread adding the player to the destination world, which on Folia
     * is the region that owns their new position, and on Paper is the main thread. Legal inline,
     * and all of it done inline: the progression evaluation (a cache read), the bypass grant (this
     * player's own PDC), the dimension-unlock override (in memory), the ledgers (in memory), and the
     * message. Illegal, and therefore not done inline: reading a block in the world they came from —
     * the source world belongs to another region and this thread may not touch it. There is no
     * captured origin behind the portal either, because nothing announced the transit, so the return
     * point is the source world's spawn.
     *
     * <p>{@code getSpawnLocation()} on the source world is not an exception to that rule: a world's
     * spawn is level data held on the {@code World} object, not a block read, so it neither loads a
     * chunk nor consults a region the caller does not own.
     *
     * <p>The spawn is not a landing, though. For a {@code NETHER -> THE_END} arrival it is the
     * Nether's spawn, which is routinely inside netherrack. {@link #returnToSpawn} therefore hands
     * the block reads to the region scheduler <em>for the spawn location</em> — the thread that owns
     * the spawn's own chunk — and lets {@link SafeRetreat#spawnLanding} find standable ground in
     * that column before anyone is moved.
     *
     * <p>That search is not confined to the one column. Establishing that a candidate is not a
     * sealed pocket means looking a few blocks around it, and at a chunk border those blocks are in
     * a chunk this thread was never handed — another region's on Folia, and on Paper a chunk that
     * reading would load, or generate, synchronously from inside a region task. So the probe answers
     * {@link SafeRetreat.Terrain#isReadable} for itself rather than leaving the invariant to a
     * comment: a block outside the chunks the calling thread owns is never read, and the search
     * treats it as a wall. The degradation is a candidate declined, and a declined candidate is
     * not a fallback: the search moves on to the next-nearest one in the spawn column, and the
     * spawn is handed back as it stands only when no candidate in range passes.
     *
     * <p>The return itself is deferred to the player's own {@code EntityScheduler} and performed
     * with {@code teleportAsync}, by way of {@link #scheduleEjection}. Deferred because this event
     * fires <em>during</em> the move — on Folia before the passenger tree is even restored — and
     * moving a player from inside that is the same hazard R-09 describes; {@code teleportAsync}
     * because the destination is in another world, and {@code Entity#teleport} throws on Folia the
     * moment it leaves the region.
     *
     * <p>{@code HIGHEST} rather than {@code MONITOR}: this handler acts, and {@code MONITOR} is for
     * observers. The event is not cancellable, so the priority buys ordering and nothing else.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerAddedToWorld(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        World arrivedIn = event.getWorld();
        UUID id = player.getUniqueId();
        UUID lastSeenIn = lastWorld.get(id).orElse(null);
        lastWorld.put(id, arrivedIn.getUID());
        Optional<UUID> departed = DimensionGateRules.departure(lastSeenIn, arrivedIn.getUID());
        if (departed.isEmpty()) {
            return;
        }
        // Null when the world they left has since been unloaded. Its kind is then unknown, which
        // kindOf reads as CUSTOM, so an arrival in the Nether or the End is still gated; the
        // return goes to the server's primary world instead.
        World cameFrom = plugin.getServer().getWorld(departed.get());

        PluginConfig config = plugin.configuration();
        Optional<DimensionUnlock> destination = DimensionGateRules.gatedDestination(
                kindOf(cameFrom), kindOf(arrivedIn), config);
        if (destination.isEmpty()) {
            return;
        }
        DimensionUnlock dimension = destination.get();

        boolean decided = consumeDecision(player, dimension, arrivedIn);
        boolean waived = waived(player, dimension);
        // Evaluated even when the answer is already settled, so that the verdict is composed in one
        // place rather than short-circuited here in a second, silently divergent order. It is a
        // ProgressionCache lookup, and only for arrivals in a gated dimension.
        EligibilityResult result = evaluate(player, config, dimension);
        if (DimensionGateRules.arrival(decided, waived, result.eligible())
                == DimensionGateRules.Arrival.ALLOWED) {
            return;
        }

        World returnTo = cameFrom != null ? cameFrom : plugin.getServer().getWorlds().get(0);
        // The return is scheduled before the player is told why, so a message that fails cannot
        // leave them standing in a dimension they were refused.
        plugin.getLogger().fine(() -> "Returning " + player.getName() + " from " + arrivedIn.getName()
                + " to " + returnTo.getName() + ": they arrived without passing the " + dimension
                + " gate, so a transit reached this world without any handler cancelling it.");
        returnToSpawn(player, returnTo);
        reject(player, config, dimension, result);
    }

    /**
     * Returns a player the backstop refused to the spawn of the world they came from, on ground
     * they fit on.
     *
     * <p>Two hops, each on the thread that owns what it touches. The landing is worked out on the
     * region scheduler for the spawn location, because that is the only thread allowed to read
     * those blocks; the move is then handed to the player's own {@code EntityScheduler} by
     * {@link #scheduleEjection}, as every other return in this class is. On Paper both schedulers
     * run on the main thread and the hops cost a tick of delay and nothing else.
     *
     * <p>{@code maxY} comes from {@link SafeRetreat#searchCeiling}, which caps the search at the
     * world's logical height so that a deep Nether spawn is not resolved onto the roof. The
     * arithmetic lives there rather than here because it is the one part of this method a test can
     * reach without a running server.
     */
    private void returnToSpawn(Player player, World world) {
        Location spawn = world.getSpawnLocation();
        plugin.getServer().getRegionScheduler().execute(plugin, spawn, () -> {
            int minY = world.getMinHeight();
            int maxY = SafeRetreat.searchCeiling(minY, world.getMaxHeight(),
                    world.getLogicalHeight());
            SafeRetreat.Landing landing = SafeRetreat.spawnLanding(spawn.getX(), spawn.getY(),
                    spawn.getZ(), terrainOf(world), minY, maxY);
            if (!landing.retreated()) {
                plugin.getLogger().fine(() -> "No standable ground in the spawn column of "
                        + world.getName() + "; returning " + player.getName() + " to the spawn as is.");
            }
            scheduleEjection(player, new Location(world, landing.x(), landing.y(), landing.z(),
                    spawn.getYaw(), spawn.getPitch()));
        });
    }

    /**
     * Records that this player has been let into {@code destination} without earning it, so that
     * {@link #onPlayerAddedToWorld} does not overturn the decision a moment later.
     *
     * <p>Both arguments are needed and neither is redundant: the gate says which requirement was
     * skipped, the world says which arrival the note is good for. {@link DimensionGateRules.Decision}
     * carries the reasoning for the second.
     *
     * <p>Called from a handler that has established the transit is actually going ahead — a note
     * written on an intention is the defect {@link #onPlayerTeleportSettled} was moved to
     * {@code MONITOR} to avoid — with one exception. {@link #expectTeleport}, and through it
     * {@link #noteCommandTeleport}, write before a deliberate teleport on Folia, because there is no
     * settled point after it to write at; {@link #noteCommandTeleport} sets out what bounds a note
     * written that way.
     */
    private void noteDecision(Player player, DimensionUnlock dimension, World destination) {
        DimensionGateRules.note(
                decisions.computeIfAbsent(player.getUniqueId(), id -> new ConcurrentHashMap<>(2)),
                dimension, destination.getUID(), System.currentTimeMillis());
    }

    /**
     * Takes this gate's note for this player out of the ledger, and says whether it covers an
     * arrival in {@code arrivedIn}.
     *
     * <p>The rule is {@link DimensionGateRules#consume}: a note is consumed rather than merely read,
     * and it only counts for the world it was written against.
     */
    private boolean consumeDecision(Player player, DimensionUnlock dimension, World arrivedIn) {
        return DimensionGateRules.consume(decisions.get(player.getUniqueId()).orElse(null),
                dimension, arrivedIn.getUID(), System.currentTimeMillis());
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
                                    DimensionUnlock dimension, World destination) {
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
                // a note there would be a live exemption nothing ever consumes. Noted against the
                // world the portal actually resolved to, so it cannot be spent on an arrival
                // somewhere else.
                //
                // Written at HIGH, beside the triage, so a HIGHEST handler that redirects this
                // EntityPortalEvent's setTo() into another world leaves the note naming a world the
                // rider never reaches. The backstop then returns them to spawn as well as this
                // ejection repositioning them, and whichever teleport runs last wins. A HIGHEST
                // cancellation leaves an orphaned note instead, bounded as on the decisions field.
                noteDecision(order.rider(), dimension, destination);
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
     * before the transit resolved; {@link #returnToSpawn} hands over standable ground in the spawn
     * column of the world the player came from, having no captured point to offer. Neither computes
     * anything here.
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
     * <p>Only ever read on the thread that owns the blocks asked about, and that is enforced here
     * rather than asserted: {@link SafeRetreat.Terrain#isReadable} answers {@code false} for any
     * block outside a chunk the calling thread owns, and {@link SafeRetreat} treats such a block as
     * a wall. Which thread that is depends on the caller — {@link #returnPointFor} probes on the
     * event thread, before any transfer resolves, starting from a column two blocks from the
     * vehicle; {@link #returnToSpawn} probes on the region scheduler for the spawn location. Neither
     * starting column is the whole of what the search asks about, because deciding that a spot is
     * not a sealed pocket means looking a few blocks around it.
     *
     * <p>Each method answers one plain question about one block. In particular {@code isPassable}
     * is Bukkit's collision question and nothing more: lava and water are passable, and it is
     * {@code isHazard} that says they are not somewhere to stand — along with the rest of
     * {@link #UNFIT_LANDINGS}, including the portal blocks. Composing the three is
     * {@link SafeRetreat}'s job, where a test can reach it.
     */
    private static SafeRetreat.Terrain terrainOf(World world) {
        return new SafeRetreat.Terrain() {
            /**
             * Both halves are load-bearing and neither implies the other.
             *
             * <p>{@code isOwnedByCurrentRegion} is the Folia question: on Folia it is false for a
             * chunk another region owns, and on Paper, which has one region, it is the main-thread
             * check. {@code isChunkLoaded} is the Paper question: an owned chunk that is not
             * resident is one {@code getBlockAt} would load, or generate, synchronously from inside
             * a region task — and for a Nether spawn the neighbours of the spawn chunk are not kept
             * alive. Asking before reading costs two lookups and no chunk work.
             */
            @Override
            public boolean isReadable(int x, int y, int z) {
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)
                        && world.isChunkLoaded(chunkX, chunkZ);
            }

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
