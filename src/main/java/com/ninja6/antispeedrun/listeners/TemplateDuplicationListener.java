package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.PlayerStateMap;
import com.ninja6.antispeedrun.progression.TrimProgressionManager;
import com.ninja6.antispeedrun.storage.ExploredStructureStore;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * The template duplication lock, {@code trim-progression.block-unearned-template-duplication}
 * (#18): a gated smithing template cannot be copied by a player, or by a Crafter, that has not
 * explored the structure it comes from.
 *
 * <h2>Crafting table and inventory grid</h2>
 *
 * {@link PrepareItemCraftEvent} clears the result slot, so the player never sees a craft they may
 * not take, and {@link CraftItemEvent} refuses the take as a backstop for anything that puts the
 * result back. Both evaluate the viewing player live, through {@link TrimProgressionManager}. The
 * item bypass ({@code antispeedrun.bypass.items} or a {@code /asr bypass} grant) waives it.
 *
 * <h2>Crafter</h2>
 *
 * {@link CrafterCraftEvent} carries no player, so a Crafter is given one: {@link #onCrafterPlaced}
 * stamps the placing player's UUID into the block's {@code TileState} persistent data, which is
 * saved with the block entity and survives chunk unload and restart. A Crafter with no stamp, or a
 * stamp that is not a UUID, refuses every gated template.
 *
 * <p>The owner is judged from {@link ExploredStructureStore}, never live. A Crafter fires on its
 * block's region whether or not its owner is online, and even an online owner is usually owned by a
 * different region thread, where their advancements cannot be read. The record is refreshed from the
 * owner's own context whenever their exploration is known to be current: on join, on earning one of
 * the structure advancements, on placing a Crafter, on every template craft they attempt by hand,
 * and when online players are primed after startup or reload. A player who explored a structure
 * before this lock existed is therefore recorded when online priming or their next join runs.
 *
 * <p>Bypasses do not carry over to a Crafter. A permission cannot be read for an offline player, so
 * a Crafter works for exactly the owners who have explored the structure.
 *
 * <h2>Folia</h2>
 *
 * Every player-facing handler runs on the region owning that player, where evaluation and the PDC
 * read are legal inline. The Crafter handler reads only its own block's state and one volatile
 * field of the store. File writes go to the store's I/O executor.
 */
public final class TemplateDuplicationListener implements Listener {

    private final AntiSpeedrunPlugin plugin;

    private final TrimProgressionManager trims;

    private final ExploredStructureStore explored;

    /** The PDC key a Crafter's owner is stamped under. */
    private final NamespacedKey ownerKey;

    /** When each player was last told, so a grid being rearranged does not spam the action bar. */
    private final PlayerStateMap<Long> lastFeedback;

    public TemplateDuplicationListener(AntiSpeedrunPlugin plugin, ExploredStructureStore explored) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.explored = Objects.requireNonNull(explored, "explored");
        this.trims = new TrimProgressionManager(plugin.progression());
        this.ownerKey = new NamespacedKey(plugin, "crafter-owner");
        this.lastFeedback = plugin.playerState().register("template-duplication-feedback");
    }

    // -------------------------------------------------------------------------------------------
    // Crafting table and inventory grid
    // -------------------------------------------------------------------------------------------

    /** Clears a gated template out of the result slot for a player who has not explored its structure. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null || !(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        Optional<Milestone> gate = refusal(player, result.getType());
        if (gate.isEmpty()) {
            return;
        }
        event.getInventory().setResult(null);
        notify(player, result.getType(), gate.get());
    }

    /** Refuses taking the result, for anything that put a gated template back after preparation. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        ItemStack result = event.getCurrentItem();
        HumanEntity who = event.getWhoClicked();
        if (result == null || !(who instanceof Player player)) {
            return;
        }
        Optional<Milestone> gate = refusal(player, result.getType());
        if (gate.isPresent()) {
            event.setCancelled(true);
            notify(player, result.getType(), gate.get());
        }
    }

    /**
     * The structure {@code player} has yet to explore before crafting {@code result}, or empty when
     * the craft may go ahead. Records what it learns.
     */
    private Optional<Milestone> refusal(Player player, Material result) {
        PluginConfig config = plugin.configuration();
        Optional<Milestone> gate = TemplateDuplicationRules.gate(result);
        if (gate.isEmpty() || !TemplateDuplicationRules.locked(config) || waived(player)) {
            return Optional.empty();
        }
        EligibilityResult outcome = trims.evaluate(player, config, gate.get());
        explored.record(player.getUniqueId(), gate.get().id(), outcome.eligible());
        return outcome.eligible() ? Optional.empty() : gate;
    }

    private boolean waived(Player player) {
        return ItemGateRules.waived(
                player.hasPermission(ItemProgressionListener.BYPASS_PERMISSION),
                plugin.bypasses().hasBypass(player, System.currentTimeMillis()));
    }

    private void notify(Player player, Material template, Milestone gate) {
        long now = System.currentTimeMillis();
        long cooldownMillis = plugin.configuration().itemProgression().feedbackCooldownSeconds() * 1_000L;
        long last = lastFeedback.getOrDefault(player.getUniqueId(), 0L);
        if (!ItemGateRules.shouldNotify(now, last, cooldownMillis)) {
            return;
        }
        lastFeedback.put(player.getUniqueId(), now);
        MiniMessage mini = MiniMessage.miniMessage();
        player.sendActionBar(mini.deserialize(TemplateDuplicationRules.rejection(
                mini.escapeTags(ItemGateRules.friendlyName(template.name())),
                mini.escapeTags(gate.displayName()))));
    }

    // -------------------------------------------------------------------------------------------
    // Crafter
    // -------------------------------------------------------------------------------------------

    /**
     * Stamps the placing player as the Crafter's owner. Always overwritten, so a Crafter item
     * carrying another player's block entity data cannot borrow their record.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrafterPlaced(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.CRAFTER) {
            return;
        }
        BlockState state = block.getState();
        if (!(state instanceof TileState tile)) {
            return;
        }
        Player player = event.getPlayer();
        tile.getPersistentDataContainer().set(
                ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        tile.update(true, false);
        refresh(player, plugin.configuration());
    }

    /** Refuses a Crafter's craft of a gated template unless its owner has explored the structure. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        PluginConfig config = plugin.configuration();
        Optional<Milestone> gate = TemplateDuplicationRules.gate(event.getResult().getType());
        if (gate.isEmpty() || !TemplateDuplicationRules.locked(config)) {
            return;
        }
        TemplateDuplicationRules.CrafterVerdict verdict = TemplateDuplicationRules.crafter(
                owner(event.getBlock()), gate.get(), explored::hasExplored);
        if (verdict != TemplateDuplicationRules.CrafterVerdict.ALLOW) {
            event.setCancelled(true);
        }
    }

    private Optional<UUID> owner(Block crafter) {
        if (!(crafter.getState(false) instanceof TileState tile)) {
            return Optional.empty();
        }
        return TemplateDuplicationRules.owner(
                tile.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING));
    }

    // -------------------------------------------------------------------------------------------
    // Keeping the record current
    // -------------------------------------------------------------------------------------------

    /** Records a joining player's exploration, including anything earned before this lock existed. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer(), plugin.configuration());
    }

    /** Records a structure advancement as soon as it is earned. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        String key = event.getAdvancement().getKey().toString();
        for (Milestone structure : TrimProgressionManager.STRUCTURES) {
            if (structure.requirement().advancements().contains(key)) {
                // ProgressionListener invalidates the snapshot at this priority too, but listener
                // order within a priority is registration order; invalidating here does not depend
                // on it.
                plugin.progression().invalidate(event.getPlayer().getUniqueId());
                refresh(event.getPlayer(), plugin.configuration());
                return;
            }
        }
    }

    /**
     * Evaluates every structure for {@code player} and records the answers. Called on the player's
     * region thread, including while already-online players are primed. Skipped while section 3
     * is inactive, because {@link TrimProgressionManager#evaluate} then passes everything and would
     * record every player as having explored every structure.
     */
    public void refresh(Player player, PluginConfig config) {
        if (!TrimProgressionManager.isActive(config)) {
            return;
        }
        for (Milestone structure : TrimProgressionManager.STRUCTURES) {
            explored.record(player.getUniqueId(), structure.id(),
                    trims.evaluate(player, config, structure).eligible());
        }
    }
}
