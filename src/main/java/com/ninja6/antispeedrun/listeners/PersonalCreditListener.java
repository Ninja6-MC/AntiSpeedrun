package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.Collection;
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

import com.ninja6.antispeedrun.progression.CreditRefresh;
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
 *
 * <p>Every credit the store reports as new goes to {@link CreditRefresh}, which opens and announces
 * the gate inline when this thread owns the player and on the player's own scheduler otherwise.
 */
public final class PersonalCreditListener implements Listener {

    /** The metadata key NPC plugins such as Citizens set on their player entities. */
    static final String NPC_METADATA = "NPC";

    private final PersonalCreditStore credits;
    private final PlacedBlockRegistry placedBlocks;
    private final CreditRefresh refresh;
    private final BreakVerdicts verdicts = new BreakVerdicts();

    public PersonalCreditListener(PersonalCreditStore credits, PlacedBlockRegistry placedBlocks,
                                  CreditRefresh refresh) {
        this.credits = Objects.requireNonNull(credits, "credits");
        this.placedBlocks = Objects.requireNonNull(placedBlocks, "placedBlocks");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
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
        ItemStack tool = player.getInventory().getItemInMainHand();
        boolean pickaxe = Tag.ITEMS_PICKAXES.isTagged(tool.getType());
        // Reading the drops is the costly part: skip it when no drop could make the break earn.
        if (!PersonalCreditRules.minedEarns(credit, placed, pickaxe, true)) {
            return;
        }
        boolean drops = event.isDropItems() && player.getGameMode() != GameMode.CREATIVE
                && PersonalCreditRules.dropsEarn(credit, types(block.getDrops(tool, player)));
        if (PersonalCreditRules.minedEarns(credit, placed, pickaxe, drops)) {
            record(player.getUniqueId(), credit, CreditSource.ACTION);
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
                .ifPresent(credit -> record(player.getUniqueId(), credit, CreditSource.ACTION));
    }

    /** A blaze the player killed. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!PersonalCreditRules.blazeKill(event.getEntityType())) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer != null && creditable(killer)) {
            record(killer.getUniqueId(), PersonalCredit.OBTAIN_BLAZE_ROD, CreditSource.ACTION);
        }
    }

    private void recordLoot(UUID player, List<ItemStack> loot) {
        boolean changed = false;
        for (PersonalCredit credit : PersonalCreditRules.looted(types(loot))) {
            changed |= credits.record(player, credit, CreditSource.LOOT);
        }
        if (changed) {
            refresh.changed(player);
        }
    }

    /** The item types of {@code items}, skipping {@code null}s. */
    private static List<Material> types(Collection<ItemStack> items) {
        List<Material> types = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            if (item != null) {
                types.add(item.getType());
            }
        }
        return types;
    }

    /** Records one credit, refreshing the player's gates if it is new. */
    private void record(UUID player, PersonalCredit credit, CreditSource source) {
        if (credits.record(player, credit, source)) {
            refresh.changed(player);
        }
    }

    private static boolean creditable(Player player) {
        return PersonalCreditRules.creditable(player != null,
                player != null && player.hasMetadata(NPC_METADATA));
    }
}
