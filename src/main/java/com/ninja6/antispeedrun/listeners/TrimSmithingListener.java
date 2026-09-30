package com.ninja6.antispeedrun.listeners;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.listeners.TrimLockRules.Lock;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.PlayerStateMap;
import com.ninja6.antispeedrun.progression.TrimProgressionManager;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Smithing and wearing locks for armor trims (#19, Task 5.1.3).
 *
 * <p>Two locks from section 3 of {@code config.yml}, each resolved through
 * {@link TrimProgressionManager} to the structure a player must have explored:
 *
 * <ul>
 *   <li><strong>{@code block-unearned-smithing}</strong> — a Smithing Table shows no result for a
 *       gated template ({@link #onPrepareSmithing}), and taking a result that is somehow still
 *       there is cancelled ({@link #onSmith}). The netherite upgrade template counts: it is
 *       Bastion-gated in the manager.</li>
 *   <li><strong>{@code block-wearing-unearned-trims}</strong> — a piece whose trim pattern is gated
 *       cannot be put into an armor slot: by clicking or dragging it there
 *       ({@link #onInventoryClick}, {@link #onInventoryDrag}), by right-clicking it from the hand
 *       ({@link #onEquipFromHand}), or by a dispenser firing it onto the player
 *       ({@link #onDispenseArmor}).</li>
 * </ul>
 *
 * <p>The trim material is never gated, only the pattern. Untrimmed armor and armor carrying an
 * ungated trim pass without an eligibility check. Taking a piece off is never refused.
 *
 * <p>Waived by {@code antispeedrun.bypass.items} and by an {@code /asr bypass} grant, the same two
 * waivers the item gate honours: a trim is an item lock.
 *
 * <h2>Folia</h2>
 *
 * Every handler is a single-player event, or a dispenser firing at the player standing in front of
 * it, so it runs on the region owning that player and the progression read is legal inline. Nothing
 * here schedules or teleports.
 */
public final class TrimSmithingListener implements Listener {

    private final AntiSpeedrunPlugin plugin;

    private final TrimProgressionManager trims;

    /** When each player was last told about a refusal, per lock and structure. */
    private final PlayerStateMap<Map<String, Long>> lastFeedback;

    public TrimSmithingListener(AntiSpeedrunPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.trims = new TrimProgressionManager(plugin.progression());
        this.lastFeedback = plugin.playerState().register("trim-lock-feedback");
    }

    // -------------------------------------------------------------------------------------------
    // block-unearned-smithing
    // -------------------------------------------------------------------------------------------

    /**
     * Clears the result slot when the template or the resulting trim is gated for the viewer.
     *
     * <p>{@code HIGH} so a plugin that rewrites smithing results at {@code NORMAL} is checked on
     * what it produced. The event cannot be cancelled; an empty result is how a Smithing Table says
     * no.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }
        if (refusedSmithing(player, event.getInventory(), result)) {
            event.setResult(null);
        }
    }

    /**
     * Taking a smithing result, refused on the same test as {@link #onPrepareSmithing}.
     *
     * <p>Covers a result prepared before the lock was switched on by {@code /asr reload}, or by a
     * plugin that set it after this listener ran.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSmith(SmithItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getInventory().getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }
        if (refusedSmithing(player, event.getInventory(), result)) {
            event.setCancelled(true);
        }
    }

    private boolean refusedSmithing(Player player, SmithingInventory inventory, ItemStack result) {
        PluginConfig config = plugin.configuration();
        if (!TrimLockRules.smithingLocked(config)) {
            return false;
        }
        ItemStack template = inventory.getInputTemplate();
        Material templateType = template == null || template.getType().isAir() ? null : template.getType();
        Optional<Milestone> gate = TrimLockRules.smithingGate(templateType, patternKey(trimOf(result)));
        return gate.isPresent() && refused(player, config, gate.get(), Lock.SMITHING);
    }

    // -------------------------------------------------------------------------------------------
    // block-wearing-unearned-trims
    // -------------------------------------------------------------------------------------------

    /**
     * A click placing, swapping or shift-clicking a gated trimmed piece into an armor slot.
     *
     * <p>{@link TrimLockRules#equipSource} names the stack the click would equip. A shift-click only
     * equips in the player's own inventory screen, from their own inventory, into an empty slot.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        SlotType slotType = event.getSlotType();
        boolean shiftClick = slotType != SlotType.ARMOR;
        if (shiftClick && !event.isShiftClick()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        PlayerInventory inventory = player.getInventory();
        boolean ownView = event.getView().getTopInventory().getType() == InventoryType.CRAFTING;
        boolean fromOwn = event.getClickedInventory() == inventory;
        boolean slotEmpty = shiftClick && ownView && fromOwn && armorSlotEmpty(inventory, clicked);

        ItemStack equipped = switch (TrimLockRules.equipSource(slotType, event.getAction(),
                event.getClick(), ownView, fromOwn, slotEmpty)) {
            case CURSOR -> event.getCursor();
            case CLICKED -> clicked;
            case HOTBAR -> event.getHotbarButton() >= 0 ? inventory.getItem(event.getHotbarButton()) : null;
            case OFF_HAND -> inventory.getItemInOffHand();
            case NONE -> null;
        };
        if (equipped != null && refusedWearing(player, equipped)) {
            event.setCancelled(true);
        }
    }

    /** Dragging a gated trimmed piece across an armor slot. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (event.getView().getSlotType(raw) == SlotType.ARMOR) {
                if (refusedWearing(player, event.getOldCursor())) {
                    event.setCancelled(true);
                }
                return;
            }
        }
    }

    /**
     * Right-clicking a gated trimmed piece to put it on.
     *
     * <p>Not {@code ignoreCancelled}: a right-click in the air arrives already cancelled. Only the
     * use of the item is denied, so right-clicking a door or a chest with the piece in hand still
     * opens it.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEquipFromHand(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        ItemStack item = event.getItem();
        if (item != null && refusedWearing(event.getPlayer(), item)) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    /** A dispenser firing a gated trimmed piece onto a player. Other entities are not gated. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispenseArmor(BlockDispenseArmorEvent event) {
        if (event.getTargetEntity() instanceof Player player && refusedWearing(player, event.getItem())) {
            event.setCancelled(true);
        }
    }

    private boolean refusedWearing(Player player, ItemStack item) {
        ArmorTrim trim = trimOf(item);
        if (trim == null) {
            return false;
        }
        PluginConfig config = plugin.configuration();
        if (!TrimLockRules.wearingLocked(config)) {
            return false;
        }
        Optional<Milestone> gate = TrimProgressionManager.forTrim(trim);
        return gate.isPresent() && refused(player, config, gate.get(), Lock.WEARING);
    }

    private static boolean armorSlotEmpty(PlayerInventory inventory, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        EquipmentSlot slot = item.getType().getEquipmentSlot();
        if (!slot.isArmor()) {
            return false;
        }
        ItemStack worn = inventory.getItem(slot);
        return worn == null || worn.getType().isAir();
    }

    // -------------------------------------------------------------------------------------------
    // Shared
    // -------------------------------------------------------------------------------------------

    /** The trim on a stack, or {@code null}. Checks {@code hasItemMeta} first so plain stacks copy nothing. */
    private static ArmorTrim trimOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta() instanceof ArmorMeta meta && meta.hasTrim() ? meta.getTrim() : null;
    }

    private static NamespacedKey patternKey(ArmorTrim trim) {
        if (trim == null) {
            return null;
        }
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN).getKey(trim.getPattern());
    }

    /**
     * Whether {@code player} is refused {@code milestone}, telling them why when they are.
     *
     * <p>The waivers come before the evaluation, as on the item gate.
     */
    private boolean refused(Player player, PluginConfig config, Milestone milestone, Lock lock) {
        if (ItemGateRules.waived(
                player.hasPermission(ItemProgressionListener.BYPASS_PERMISSION),
                plugin.bypasses().hasBypass(player, System.currentTimeMillis()))) {
            return false;
        }
        EligibilityResult result = trims.evaluate(player, config, milestone);
        if (result.eligible()) {
            return false;
        }
        notify(player, config, milestone, lock, result);
        return true;
    }

    /**
     * The action-bar refusal, at most once per lock and structure per
     * {@code item-progression.feedback-cooldown-seconds}. Section 3 has no cooldown of its own.
     */
    private void notify(Player player, PluginConfig config, Milestone milestone, Lock lock,
                        EligibilityResult result) {
        long now = System.currentTimeMillis();
        Map<String, Long> perKey = lastFeedback.computeIfAbsent(
                player.getUniqueId(), id -> new ConcurrentHashMap<>(4));
        String key = TrimLockRules.feedbackKey(lock, milestone);
        long cooldownMillis = config.itemProgression().feedbackCooldownSeconds() * 1_000L;
        if (!ItemGateRules.shouldNotify(now, perKey.getOrDefault(key, 0L), cooldownMillis)) {
            return;
        }
        perKey.put(key, now);

        MiniMessage mini = MiniMessage.miniMessage();
        player.sendActionBar(mini.deserialize(
                TrimLockRules.rejection(lock, mini.escapeTags(TrimLockRules.requirement(milestone)))));
        result.fallbackHint().ifPresent(fallback ->
                player.sendMessage(mini.deserialize("<gray>" + mini.escapeTags(fallback))));
    }
}
