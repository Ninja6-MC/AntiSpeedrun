package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseLootEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;

import com.ninja6.antispeedrun.storage.CreditSource;
import com.ninja6.antispeedrun.storage.PersonalCredit;
import com.ninja6.antispeedrun.storage.PersonalCreditStore;
import com.ninja6.antispeedrun.storage.PlacedBlockRegistry;

/**
 * Records the mining, structure-loot, crafting and blaze-kill credits (#213) into
 * {@link PersonalCreditStore}. The rules are {@link PersonalCreditRules}; this class only reads the
 * events.
 *
 * <p>Always registered, whatever the toggle and {@code count-structure-loot} say: the lookup
 * decides what counts, and loot is stored with its source so that decision can change at once.
 *
 * <h2>Mining, and the placed-block registry</h2>
 *
 * {@code PlacedBlockListener} clears a broken block's registry entry at {@code MONITOR}. The
 * registry is therefore read here at {@code HIGHEST}, which every {@code MONITOR} handler follows,
 * and the answer is carried to this class's own {@code MONITOR} handler by {@link BreakVerdicts}.
 * Only that handler records, and only for a break that is not cancelled. Neither step depends on
 * the order of same-priority listeners.
 *
 * <h2>Folia</h2>
 *
 * A credit is recorded by UUID from whichever region thread fires the event; the store is safe
 * from any thread. A block break and a craft run on the region that owns the acting player, so
 * their {@code Player} is read inline. The looting player of a container or vault, and the killer
 * of a blaze, may be owned by another region: from those only the UUID and the server's plugin
 * metadata store (which has its own lock and no region state) are read.
 */
public final class PersonalCreditListener implements Listener {

    /** The metadata key NPC plugins such as Citizens set on their player entities. */
    static final String NPC_METADATA = "NPC";

    private final PersonalCreditStore credits;
    private final PlacedBlockRegistry placedBlocks;
    private final BreakVerdicts verdicts = new BreakVerdicts();

    public PersonalCreditListener(PersonalCreditStore credits, PlacedBlockRegistry placedBlocks) {
        this.credits = Objects.requireNonNull(credits, "credits");
        this.placedBlocks = Objects.requireNonNull(placedBlocks, "placedBlocks");
    }

    /**
     * Reads the registry before {@code PlacedBlockListener} clears it. Runs for cancelled events
     * too, since a later handler may still allow the break; {@link #onBreak} settles it.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void beforeBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (PersonalCreditRules.minable(block.getType()).isPresent()) {
            verdicts.put(event, placedBlocks.isPlaced(block));
        }
    }

    /** Records the mining credit of a break that went ahead. Not {@code ignoreCancelled}; see above. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        Boolean placed = verdicts.take(event);
        if (placed == null || event.isCancelled()) {
            return;
        }
        Player player = event.getPlayer();
        if (!creditable(player)) {
            return;
        }
        Block block = event.getBlock();
        PersonalCredit credit = PersonalCreditRules.minable(block.getType()).orElse(null);
        if (credit == null) {
            return;
        }
        boolean pickaxe = false;
        boolean drops = false;
        if (PersonalCreditRules.needsPickaxeDrop(credit)) {
            ItemStack tool = player.getInventory().getItemInMainHand();
            pickaxe = Tag.ITEMS_PICKAXES.isTagged(tool.getType());
            drops = pickaxe && event.isDropItems() && player.getGameMode() != GameMode.CREATIVE
                    && !block.getDrops(tool, player).isEmpty();
        }
        if (PersonalCreditRules.minedEarns(credit, placed, pickaxe, drops)) {
            credits.record(player.getUniqueId(), credit, CreditSource.ACTION);
        }
    }

    /** Structure containers and chest minecarts the player opens first. Sees the final loot. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLootGenerate(LootGenerateEvent event) {
        if (event.isPlugin()) {
            return;
        }
        Entity looter = event.getEntity();
        if (looter instanceof Player player && creditable(player)) {
            recordLoot(player.getUniqueId(), event.getLoot());
        }
    }

    /** A trial vault ejecting its reward for the player who unlocked it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVaultLoot(BlockDispenseLootEvent event) {
        Player player = event.getPlayer();
        if (player == null || !PersonalCreditRules.lootingBlock(event.getBlock().getType())
                || !creditable(player)) {
            return;
        }
        recordLoot(player.getUniqueId(), event.getDispensedLoot());
    }

    /** A pickaxe taken from a crafting grid. A Crafter block fires a different event entirely. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (event.getAction() == InventoryAction.NOTHING) {
            return;
        }
        HumanEntity crafter = event.getWhoClicked();
        if (!(crafter instanceof Player player) || !creditable(player)) {
            return;
        }
        Material result = event.getRecipe().getResult().getType();
        InventoryType grid = event.getInventory().getType();
        boolean craftingGrid = grid == InventoryType.WORKBENCH || grid == InventoryType.CRAFTING;
        PersonalCreditRules.crafted(result, craftingGrid)
                .ifPresent(credit -> credits.record(player.getUniqueId(), credit, CreditSource.ACTION));
    }

    /** A blaze the player killed. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!PersonalCreditRules.blazeKill(event.getEntityType())) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer != null && creditable(killer)) {
            credits.record(killer.getUniqueId(), PersonalCredit.OBTAIN_BLAZE_ROD, CreditSource.ACTION);
        }
    }

    private void recordLoot(UUID player, List<ItemStack> loot) {
        List<Material> items = new ArrayList<>(loot.size());
        for (ItemStack item : loot) {
            if (item != null) {
                items.add(item.getType());
            }
        }
        for (PersonalCredit credit : PersonalCreditRules.looted(items)) {
            credits.record(player, credit, CreditSource.LOOT);
        }
    }

    private static boolean creditable(Player player) {
        return PersonalCreditRules.creditable(player != null,
                player != null && player.hasMetadata(NPC_METADATA));
    }
}
