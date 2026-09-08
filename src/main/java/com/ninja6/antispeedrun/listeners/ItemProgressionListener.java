package com.ninja6.antispeedrun.listeners;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.TradeSelectEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PluginConfig.ItemTier;
import com.ninja6.antispeedrun.gating.ItemGateTable;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.PlayerStateMap;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * The item gate: the table finally gets asked a question.
 *
 * <p>Until this class existed, {@link ItemGateTable} compiled on every reload, published itself
 * with one volatile write, answered in constant time — and the only caller in the plugin was the
 * {@code /antispeedrun} status line, printing how many materials were in it. Every transfer channel
 * Epic 4 enumerates was open.
 *
 * <p>Four channels in, one rule behind all of them, exactly as {@link ProgressionGateListener} is
 * built:
 *
 * <ul>
 *   <li><strong>{@link PlayerAttemptPickupItemEvent}</strong> (#12) — an item on the ground.</li>
 *   <li><strong>{@link InventoryClickEvent}</strong> (#9, and #54's second criterion) — taking a
 *       gated stack out of any container, by any of the six gestures that can do it.</li>
 *   <li><strong>{@link InventoryDragEvent}</strong> (#9) — the backstop described on
 *       {@link #onInventoryDrag}.</li>
 *   <li><strong>{@link TradeSelectEvent}</strong> (#54) — a villager or wandering trader offering
 *       a gated result.</li>
 * </ul>
 *
 * <p>Plus the three handlers that maintain §4 drop recall, which decides nothing and gates nothing;
 * see {@link DropRecall}.
 *
 * <h2>What the provenance record removed from this class</h2>
 *
 * The blueprint on #9 called for a holder check — {@code Container}, {@code BlockInventoryHolder},
 * {@code EntityInventoryHolder} — and a natural-versus-player container distinction on
 * {@code lootTable == null}. Both are gone. {@code docs/provenance-model.md} narrowed #9 to "a flat
 * material check on withdrawal", and that simplification is also what closes audit finding C-09
 * for free: a {@code MerchantInventory}'s holder is the merchant and none of those three types, so
 * a holder-based guard never evaluated the trade result slot. With no holder check to evade, the
 * ordinary top-inventory rule covers merchant result slots — shift-click and hotbar swap included —
 * and villagers and wandering traders alike, because both use a {@code MerchantInventory}.
 *
 * <h2>Gating is a pure function of (advancements, material)</h2>
 *
 * Nothing here inspects an item's history, and no {@code ItemStack} is ever read for a plugin tag,
 * because none carries one. The single exception is {@link DropRecall}, which is an entity-PDC
 * exemption rather than an entitlement written into an item.
 *
 * <h2>Folia</h2>
 *
 * Simpler than the dimension gate, and worth saying why rather than leaving the absence of
 * machinery to be inferred. Every handler here is a single-player or single-entity event, so Folia
 * calls it on the region owning that player or that item: the progression cache read, the PDC
 * bypass read, the entity-PDC read and the message send are all legal inline. Nothing repositions
 * anything and nothing crosses a region boundary, so none of R-09's deferred-dismount machinery
 * applies — there is no {@code EntityScheduler} hop and no {@code teleportAsync} in this file. No
 * handler opens a file or touches a store on a region thread.
 */
public final class ItemProgressionListener implements Listener {

    /**
     * The standing exemption, as {@code plugin.yml} declares it. A child of
     * {@code antispeedrun.bypass}, never of {@code antispeedrun.admin} — the same deliberate
     * arrangement {@link ProgressionGateListener#BYPASS_PERMISSION} documents.
     */
    public static final String BYPASS_PERMISSION = "antispeedrun.bypass.items";

    /**
     * Ticks of pickup delay imposed on an item whose pickup was refused — audit finding R-12.
     *
     * <p>Cancelling a pickup leaves the item on the ground and the player standing on it, so
     * without this the event re-fires about twenty times a second for as long as they stand there,
     * each time costing a table lookup, an eligibility check and a throttle-map read. The three
     * second feedback cooldown throttles the <em>message</em>; it does nothing about the work. Two
     * seconds of delay damps the loop to one attempt in forty at no gameplay cost — the item is
     * still there, and a player who has since qualified picks it up on the next attempt.
     *
     * <p>One consequence is worth stating rather than leaving to be discovered: pickup delay is a
     * property of the <em>item</em>, not of the player who was refused. A qualified player standing
     * beside a refused one therefore waits out the same two seconds. They are not locked out — when
     * the delay lapses their own pickup event fires too, and theirs is not cancelled — so the cost
     * is a delay rather than a denial, and it is the cost R-12 asked for when it prescribed this
     * fix. Scoping the delay to the refused player is not possible through this API.
     */
    private static final int REFUSED_PICKUP_DELAY_TICKS = 40;

    /**
     * Inventory views whose top half is a crafting grid rather than storage.
     *
     * <p>Excluded because they are not a transfer channel and no Epic 4 issue claims them.
     * {@code CRAFTING} is the player's own inventory screen, whose "top inventory" is their 2x2
     * grid — gating it would refuse a player their own items mid-craft, which is the one thing the
     * gate must never do. {@code WORKBENCH} is the same argument one block larger. Furnaces,
     * Crafters, brewing stands and every storage block remain gated: those are containers, and #9
     * names them.
     */
    private static final List<InventoryType> CRAFTING_VIEWS =
            List.of(InventoryType.CRAFTING, InventoryType.WORKBENCH);

    private final AntiSpeedrunPlugin plugin;

    private final DropRecall recall;

    /**
     * When each player was last told they are gated, <em>per tier</em>. Registered with
     * {@link com.ninja6.antispeedrun.progression.PlayerStateRegistry} rather than held as a bare
     * map, so quit cleanup happens without this class remembering to do it — finding R-08.
     *
     * <p>Keyed on the tier as well as the player for the same reason
     * {@link ProgressionGateListener} keys on the dimension: being refused iron and then, moments
     * later, diamond is two different pieces of news, and the second is the more surprising one.
     * The inner map is a {@code ConcurrentHashMap} per finding #51, because a player's pickups and
     * their container clicks can be evaluated from different region threads over one session.
     */
    private final PlayerStateMap<Map<String, Long>> lastFeedback;

    public ItemProgressionListener(AntiSpeedrunPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.recall = new DropRecall(plugin);
        this.lastFeedback = plugin.playerState().register("item-gate-feedback");
    }

    // -------------------------------------------------------------------------------------------
    // #12 - off the ground
    // -------------------------------------------------------------------------------------------

    /**
     * A player walking over a gated item.
     *
     * <h2>Why this event and not {@code EntityPickupItemEvent} — audit finding R-12</h2>
     *
     * R-12 requires that exactly one of the two pickup events handles players, and that the choice
     * be documented. This is that event, for two reasons. It is <em>player-only</em>, so it cannot
     * double-fire against {@code EntityPickupItemEvent}, which also fires for players and would
     * otherwise double every lookup and every throttle write on the busiest path in the plugin. And
     * mobs no longer need gating at all: the provenance record narrowed #17 to remove mob-pickup
     * cancellation, on the grounds that a zombie holding a diamond sword is harmless because the
     * unqualified player still cannot pick it up when the zombie dies. With mobs out of scope, the
     * event that only ever concerns players is the correct one.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttemptPickup(PlayerAttemptPickupItemEvent event) {
        Item entity = event.getItem();
        Player player = event.getPlayer();
        PluginConfig config = plugin.configuration();
        Material material = entity.getItemStack().getType();

        ItemTier tier = gatedTier(material, config);
        if (tier == null || waived(player, config)) {
            return;
        }
        // Before the eligibility check, because it is a definitive exemption: this is the player's
        // own drop or their own death pile, and §4 says they may always have it back.
        if (config.itemProgression().dropRecallEnabled()
                && recall.belongsTo(entity, player.getUniqueId())) {
            return;
        }
        EligibilityResult result = plugin.progression().evaluate(
                player, config, ItemGateRules.requirement(tier));
        if (result.eligible()) {
            return;
        }

        event.setCancelled(true);
        entity.setPickupDelay(REFUSED_PICKUP_DELAY_TICKS);
        reject(player, config, tier, result, material);
    }

    // -------------------------------------------------------------------------------------------
    // #9 - out of a container, and #54's result slot along with it
    // -------------------------------------------------------------------------------------------

    /**
     * Any click that could move a gated stack out of a container and toward the player.
     *
     * <p>{@code HIGH} with {@code ignoreCancelled}, matching the dimension gate: late enough that a
     * protection plugin with an opinion about this container has already had it, early enough that
     * {@code MONITOR} observers see the final answer.
     *
     * <p>All six extraction gestures #9 names route through {@link ItemGateRules#withdrawn}, which
     * decides <em>which</em> stack a gesture moves — the clicked slot's or the cursor's — so that a
     * deposit into the container and a withdrawal out of it are told apart without this method
     * reasoning about cursors. Everything it can return is tested against the same flat material
     * check.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (CRAFTING_VIEWS.contains(event.getView().getTopInventory().getType())) {
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        boolean clickedTop = rawSlot >= 0 && rawSlot < topSize;

        ItemStack moving = switch (ItemGateRules.withdrawn(gestureOf(event), clickedTop)) {
            case CLICKED_SLOT -> event.getCurrentItem();
            case CURSOR -> event.getCursor();
            case NONE -> null;
        };
        if (moving == null || moving.getType().isAir()) {
            return;
        }

        if (refuse(player, moving.getType())) {
            event.setCancelled(true);
        }
    }

    /**
     * Folds Bukkit's click taxonomy down to the distinctions the gate cares about.
     *
     * <p>{@code default} is {@link ItemGateRules.Gesture#DIRECT} rather than {@code INERT}, which
     * is the conservative direction: an unrecognised or future click type on a container slot
     * holding a gated stack is treated as a withdrawal and refused, rather than waved through. It
     * costs nothing, because a click on a slot the player may already take from is not gated
     * anyway, and clicks outside the window carry a raw slot of {@code -999} which is not in the
     * top inventory and never reaches this classification.
     */
    private static ItemGateRules.Gesture gestureOf(InventoryClickEvent event) {
        return switch (event.getClick()) {
            case NUMBER_KEY, SWAP_OFFHAND -> ItemGateRules.Gesture.HOTBAR_SWAP;
            case DOUBLE_CLICK -> ItemGateRules.Gesture.COLLECT_TO_CURSOR;
            case DROP, CONTROL_DROP -> ItemGateRules.Gesture.DROP;
            case SHIFT_LEFT, SHIFT_RIGHT -> ItemGateRules.Gesture.QUICK_MOVE;
            case WINDOW_BORDER_LEFT, WINDOW_BORDER_RIGHT, UNKNOWN -> ItemGateRules.Gesture.INERT;
            default -> ItemGateRules.Gesture.DIRECT;
        };
    }

    /**
     * A drag distributing a gated stack across slots.
     *
     * <h2>Why this handler exists even though a drag cannot withdraw</h2>
     *
     * Worth stating, because the honest answer is not the obvious one. A drag only ever moves items
     * <em>from</em> the cursor <em>into</em> slots, so unlike every gesture in
     * {@link #onInventoryClick} it cannot take anything out of a container. #9's criterion that
     * dragging be "100% blocked" is therefore not describing a siphon of its own; it is closing the
     * second half of one, where a double-click gathers a container's stacks onto the cursor and a
     * drag then scatters them into the player's inventory.
     *
     * <p>{@link ItemGateRules.Gesture#COLLECT_TO_CURSOR} already refuses that first half, so this
     * handler is the backstop for a cursor that should never have been loaded.
     *
     * <p>It is a backstop, though, and not a blanket refusal, because the two exemptions the click
     * path grants have to hold here too or the gate starts confiscating rather than gating. A drag
     * is only refused when it puts items into the player's own half of the view: dragging entirely
     * within the container is a deposit, which {@link ItemGateRules#withdrawn} is built to permit,
     * and a crafting view is excluded for the same reason it is in {@link #onInventoryClick}.
     * Without both, a player holding above-tier gear by administrative grant — precisely the
     * population §4 recall exists for — could not split their own stack or put it away.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (CRAFTING_VIEWS.contains(event.getView().getTopInventory().getType())) {
            return;
        }
        ItemStack dragged = event.getOldCursor();
        if (dragged.getType().isAir()) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        boolean intoPlayer = false;
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= topSize) {
                intoPlayer = true;
                break;
            }
        }
        if (!intoPlayer) {
            return;
        }

        if (refuse(player, dragged.getType())) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------------------------------------------
    // #54 - across a trading table
    // -------------------------------------------------------------------------------------------

    /**
     * A villager or wandering trader offering a gated result.
     *
     * <p>Audit finding C-09: specification §2.3 enumerates transfer channels exhaustively and
     * misses the most accessible one on any established server. Armorers sell diamond armour,
     * toolsmiths diamond tools, weaponsmiths diamond swords — a day-one player with emeralds walks
     * past the entire diamond tier.
     *
     * <p>This refuses the <em>selection</em>, so a gated trade never populates the result slot in
     * the first place. Taking a result that somehow did populate is refused separately by
     * {@link #onInventoryClick}, which covers shift-click and hotbar swap out of the result slot
     * without any merchant-specific code, because the holder check that used to miss merchant
     * inventories no longer exists.
     *
     * <p>Wandering traders need no separate path: both they and villagers present a
     * {@code MerchantInventory}, and this event is raised for both.
     *
     * <p>One coordination note for whoever writes Task 3.2.2. That task gates the Mending book on
     * trades, and #54 asks that the two gates not double-message. As of this listener there is no
     * Epic 3 trade handler in the tree to double-message with, so nothing is done about it here;
     * the constraint is recorded from this side so it is found rather than rediscovered.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTradeSelect(TradeSelectEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Merchant merchant = event.getMerchant();
        int index = event.getIndex();
        List<MerchantRecipe> recipes = merchant.getRecipes();
        if (index < 0 || index >= recipes.size()) {
            // A selection the server cannot resolve to an offer. Nothing to attribute to a gate, so
            // it is left alone rather than refused on a guess.
            return;
        }
        Material result = recipes.get(index).getResult().getType();
        if (refuse(player, result)) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------------------------------------------
    // §4 drop recall - bookkeeping only; nothing below gates anything
    // -------------------------------------------------------------------------------------------

    /**
     * A player throwing an item down, stamped so they can pick it back up.
     *
     * <p>{@code MONITOR} because this must record what actually happened rather than what was
     * proposed, and {@code ignoreCancelled} because a drop another plugin refused never produced an
     * entity. It writes to the item entity and not to the event, so it does not break the rule that
     * a {@code MONITOR} handler leaves the event alone.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        PluginConfig config = plugin.configuration();
        if (!config.itemProgression().dropRecallEnabled()) {
            return;
        }
        Item drop = event.getItemDrop();
        // Only gated materials, because only gated materials are ever asked about. The pickup path
        // reads the stamp strictly after gatedTier() has returned non-null, so a stamp on a stack
        // of cobblestone can never change a decision -- it is a PDC write, and then chunk NBT on
        // disk, bought for nothing.
        if (gatedTier(drop.getItemStack().getType(), config) == null) {
            return;
        }
        recall.stamp(drop, event.getPlayer().getUniqueId());
    }

    /**
     * A death, remembered just long enough for its drops to appear.
     *
     * <p>{@code PlayerDeathEvent#getDrops()} is a list of {@code ItemStack}s and the entities
     * carrying them do not exist yet, so there is nothing to stamp here — see
     * {@link DropRecall#recordDeath}. An empty drop list means {@code keepInventory} or a death
     * that dropped nothing, and recording it would only leave a mark for an unrelated item to match
     * against.
     *
     * <p>{@link EntityDeathEvent} is deliberately not used even though {@code PlayerDeathEvent}
     * extends it: only players are recalled, and the player event is the narrower subscription.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.configuration().itemProgression().dropRecallEnabled()
                || event.getDrops().isEmpty()) {
            return;
        }
        Player player = event.getEntity();
        recall.recordDeath(player.getUniqueId(), player.getLocation(), System.currentTimeMillis());
    }

    /**
     * Attributes a newly spawned item to a death recorded a moment ago, if there was one.
     *
     * <p>This fires for every item entity that appears anywhere — mob drops, block breaks, every
     * hopper-fed dispenser — so the cost of the common case is what matters, and the guards below
     * are ordered accordingly: a volatile config read, then
     * {@link DropRecall#hasPendingDeaths()}, which is false except in the second after somebody
     * dies. Only past both does this touch {@code getLocation()}, which allocates.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        PluginConfig config = plugin.configuration();
        if (!config.itemProgression().dropRecallEnabled() || !recall.hasPendingDeaths()) {
            return;
        }
        Item item = event.getEntity();
        if (gatedTier(item.getItemStack().getType(), config) == null) {
            return;
        }
        recall.claim(item.getLocation(), System.currentTimeMillis())
                .ifPresent(owner -> recall.stamp(item, owner));
    }

    // -------------------------------------------------------------------------------------------
    // The shared spine
    // -------------------------------------------------------------------------------------------

    /**
     * Whether this player must be refused this material, telling them why if so.
     *
     * <p>The whole gate for the three container-side channels, in the order that makes the common
     * case cheap: the table lookup is an array read that allocates nothing, and it comes before the
     * permission check, the PDC read and the cache read that follow it. On a server where nothing
     * is configured as gated, every click costs one {@code EnumSet} probe.
     *
     * <p>Drop recall is deliberately absent here. It is an exemption for an item <em>entity</em>
     * carrying a stamp, and none of these three channels has one — a stack in a chest is a stack,
     * and §4's rule is precisely that provenance does not survive being put down.
     *
     * @return {@code true} when the caller should cancel its event
     */
    private boolean refuse(Player player, Material material) {
        PluginConfig config = plugin.configuration();
        ItemTier tier = gatedTier(material, config);
        if (tier == null || waived(player, config)) {
            return false;
        }
        EligibilityResult result = plugin.progression().evaluate(
                player, config, ItemGateRules.requirement(tier));
        if (result.eligible()) {
            return false;
        }
        reject(player, config, tier, result, material);
        return true;
    }

    /**
     * The tier gating this material, or {@code null} when it is not gated.
     *
     * <p>{@code null} rather than {@code Optional} on purpose: this is called on the pickup and
     * click paths, where {@link ItemGateTable} goes to some length to avoid allocating, and
     * wrapping its answer here would spend exactly what it saved.
     */
    private ItemTier gatedTier(Material material, PluginConfig config) {
        if (!config.itemProgression().enabled()) {
            return null;
        }
        ItemGateTable<Material> table = plugin.itemGates();
        return table.isGated(material) ? table.tierFor(material) : null;
    }

    /**
     * Whether the gate is waived for this player before their progression is consulted.
     *
     * <p>Both reads are in-memory or PDC reads on the player's own region, so this is safe on the
     * event thread; neither touches a file.
     */
    private boolean waived(Player player, PluginConfig config) {
        return ItemGateRules.waived(
                config.itemProgression().enabled(),
                player.hasPermission(BYPASS_PERMISSION),
                plugin.bypasses().hasBypass(player, System.currentTimeMillis()));
    }

    /**
     * Tells the player why, at most once per tier per {@code feedback-cooldown-seconds}.
     *
     * <p>The rejection line goes to the action bar, which is what
     * {@code feedback-cooldown-seconds} describes in {@code config.yml} and the right place for
     * something a player may trigger by walking; the fail-open hint goes to chat, because it is
     * addressed as much to the operator reading over their shoulder as to the player, and it is
     * rare.
     *
     * <p>The configured {@code rejection-message} is the operator's own MiniMessage and is
     * deserialised as markup. What is interpolated into it is not: the material name and the tier's
     * configured hint both have their tags neutralised first, which is the rule
     * {@code AntiSpeedrunCommand} established and {@link ProgressionGateListener#reject} follows.
     */
    private void reject(Player player, PluginConfig config, ItemTier tier, EligibilityResult result,
                        Material material) {
        long now = System.currentTimeMillis();
        Map<String, Long> perTier = lastFeedback.computeIfAbsent(
                player.getUniqueId(), id -> new ConcurrentHashMap<>(4));
        long last = perTier.getOrDefault(tier.id(), 0L);
        long cooldownMillis = config.itemProgression().feedbackCooldownSeconds() * 1_000L;
        if (!ItemGateRules.shouldNotify(now, last, cooldownMillis)) {
            return;
        }
        perTier.put(tier.id(), now);

        MiniMessage mini = MiniMessage.miniMessage();
        String line = ItemGateRules.rejection(
                config.itemProgression().rejectionMessage(),
                mini.escapeTags(ItemGateRules.friendlyName(material.name())),
                mini.escapeTags(ItemGateRules.requirementText(tier, result)));
        player.sendActionBar(mini.deserialize(line));
        result.fallbackHint().ifPresent(hint ->
                player.sendMessage(mini.deserialize("<gray>" + mini.escapeTags(hint))));
    }
}
