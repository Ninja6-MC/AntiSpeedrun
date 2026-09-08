package com.ninja6.antispeedrun.listeners;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * <p>Three channels in, one rule behind all of them, exactly as {@link ProgressionGateListener} is
 * built:
 *
 * <ul>
 *   <li><strong>{@link PlayerAttemptPickupItemEvent}</strong> (#12) — an item on the ground.</li>
 *   <li><strong>{@link InventoryClickEvent}</strong> (#9, and #54's second criterion) — taking a
 *       gated stack out of any container, by any of the six gestures that can do it.</li>
 *   <li><strong>{@link TradeSelectEvent}</strong> (#54) — a villager or wandering trader offering
 *       a gated result.</li>
 * </ul>
 *
 * <p>Plus the three handlers that maintain §4 drop recall, which decides nothing and gates nothing;
 * see {@link DropRecall}.
 *
 * <h2>There is deliberately no {@code InventoryDragEvent} handler</h2>
 *
 * #9 asks that inventory dragging be "100% blocked", and this class does not do it, so the reason
 * is recorded here rather than left as an omission.
 *
 * <p>A drag only ever moves items <em>from</em> the cursor <em>into</em> slots, so it cannot take
 * anything out of a container. For it to matter, the cursor would have to be holding a gated stack
 * taken from the container, and every <em>click</em> that could load it that way is refused in
 * {@link #onInventoryClick}: {@code DIRECT}, {@code QUICK_MOVE} and {@code HOTBAR_SWAP} on a top
 * slot all name the clicked slot, and {@code COLLECT_TO_CURSOR} names the cursor wherever it was
 * clicked. So in ordinary play a cursor reaching a drag with a gated stack on it was loaded from
 * the player's own inventory, and every drag a handler could cancel would be a deposit or an
 * own-inventory move.
 *
 * <p>"Ordinary play" rather than "always", because there is one exception and stating the argument
 * without it would be overclaiming: {@code PICKUP_FROM_BUNDLE} takes a gated stack out of a bundle
 * sitting in a container without ever being tested, for the reason
 * {@link InventoryGestures} sets out. It does not change the conclusion — a handler that refused
 * only drags spanning both halves would let that player scatter the stack within their own
 * inventory anyway, so keeping one would have stopped nothing — but the gap is real until #15
 * closes it, and this paragraph is the sole justification for dropping a handler #9 named.
 *
 * <p>An earlier revision had one anyway, and it went wrong in both directions before it was
 * removed: first refusing a player tidying their own inventory, then — narrowed to drags spanning
 * both halves — exempting the one shape that could in principle scatter a container-loaded cursor
 * while still refusing an ordinary stack split. It could not do better, because
 * {@code InventoryDragEvent} carries no provenance for the cursor and no condition over it can tell
 * a sorted stack from a siphoned one.
 *
 * <p>This is the argument {@code docs/provenance-model.md} already used to close #10: under
 * material-only gating, moving a diamond between containers leaves it a diamond, and an exploit
 * described against the provenance model does not survive the switch. #9's drag criterion is the
 * same criterion viewed from the same angle.
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
     * Inventory views whose top half hands a player's own item straight back rather than keeping
     * it.
     *
     * <p>Excluded because the top-versus-bottom rule {@link #onInventoryClick} applies reads any
     * click on the top half as a withdrawal from a container, and in these views there is no
     * container. The player puts their own item into an input slot — a deposit, allowed — and the
     * only way to get it out again is a click on that same slot, which the rule would refuse.
     * Refusing it gates nothing: the item was already theirs, and every view listed here ejects its
     * contents to the floor when the window closes, so the gate would achieve no more than making
     * them close it.
     *
     * <p>{@code CRAFTING} is the player's own inventory screen, whose "top inventory" is their 2x2
     * grid, and {@code WORKBENCH} is the same argument one block larger. The rest are the
     * single-block workstations. An anvil repairs, an enchanting table enchants, a grindstone and a
     * stonecutter reduce, a loom and a cartography table decorate, and a smithing table upgrades
     * gear from parts that had to come through a gated channel first.
     *
     * <p>Some of these do produce a <em>different</em> material from their inputs — a crafting table
     * and a stonecutter plainly do — so the safety here is not a property of the views. It is a
     * property of the shipped tiers: no tier in the shipped {@code gated-items} is reachable this
     * way, because every gated output has a same-or-higher-tier gated ingredient, and the netherite
     * path is closed at {@code ANCIENT_DEBRIS} on the pickup gate. An operator who adds a craftable
     * item to a tier above its own ingredients opens a hole here and gets no warning, which is the
     * same class of configuration hazard {@code docs/provenance-model.md} flags when it says to
     * re-verify the tier table if prerequisites are retuned.
     *
     * <p>The line is drawn at whether the block <em>keeps</em> what is put into it. Furnaces, blast
     * furnaces, smokers, brewing stands, Crafters and every storage type are deliberately absent:
     * they hold their contents, a hopper can pull those contents into somebody else's inventory,
     * and #9 names them as containers. A furnace input slot is a chest slot that happens to smelt.
     */
    private static final Set<InventoryType> PASS_THROUGH_VIEWS = EnumSet.of(
            InventoryType.CRAFTING,
            InventoryType.WORKBENCH,
            InventoryType.ANVIL,
            InventoryType.SMITHING,
            InventoryType.GRINDSTONE,
            InventoryType.ENCHANTING,
            InventoryType.CARTOGRAPHY,
            InventoryType.LOOM,
            InventoryType.STONECUTTER);

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
        if (tier == null || waived(player)) {
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
        if (PASS_THROUGH_VIEWS.contains(event.getView().getTopInventory().getType())) {
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        boolean clickedTop = rawSlot >= 0 && rawSlot < topSize;

        ItemStack moving = switch (ItemGateRules.withdrawn(
                InventoryGestures.of(event.getAction(), event.getClick()), clickedTop)) {
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
     * {@link DropRecall#hasPendingDeaths(long)}, which is false except in the second after somebody
     * dies. Only past both does this touch {@code getLocation()}, which allocates.
     *
     * <p>An entity that already carries a stamp is left with the one it has. {@code ServerPlayer}
     * raises {@code PlayerDropItemEvent} before the entity joins the world, so a player throwing a
     * gated item down beside a fresh corpse is stamped by {@link #onPlayerDropItem} first and would
     * otherwise be re-stamped for the dead player here — handing recall on their own item to
     * somebody else. {@link DropRecall#isStamped} is asked only past both guards above and the
     * material check, so the extra PDC read is confined to a path that is already rare.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        PluginConfig config = plugin.configuration();
        if (!config.itemProgression().dropRecallEnabled()) {
            return;
        }
        // Read after the config check, not before it, so a server with recall switched off pays
        // nothing at all here -- and so the order matches the one the javadoc above describes.
        long now = System.currentTimeMillis();
        if (!recall.hasPendingDeaths(now)) {
            return;
        }
        Item item = event.getEntity();
        if (gatedTier(item.getItemStack().getType(), config) == null || recall.isStamped(item)) {
            return;
        }
        recall.claim(item.getLocation(), now).ifPresent(owner -> recall.stamp(item, owner));
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
        if (tier == null || waived(player)) {
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
     *
     * <p>The master switch is not among them. Every caller has already been through
     * {@link #gatedTier}, which reports nothing as gated while {@code item-progression.enabled} is
     * false, so asking again here would be a second off switch that could never be the one that
     * fired.
     */
    private boolean waived(Player player) {
        return ItemGateRules.waived(
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
