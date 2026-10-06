package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import com.ninja6.antispeedrun.progression.CreditRefresh;
import com.ninja6.antispeedrun.storage.CreditSource;
import com.ninja6.antispeedrun.storage.PersonalCredit;
import com.ninja6.antispeedrun.storage.PersonalCreditStore;

/**
 * Keeps the furnace loader stamp and records the smelted-iron credit (#214). The arithmetic is
 * {@link FurnaceLoaderRules}; this class only reads the events and the furnace's container.
 *
 * <h2>The stamp</h2>
 *
 * Held in the furnace or blast furnace's {@code TileState} persistent data under
 * {@code antispeedrun:furnace-loader}, as the {@code LONG_ARRAY}
 * {@code [mostSigBits, leastSigBits, remaining]}. It is saved with the block entity and dies with
 * the block. A spent stamp is removed, not kept at 0.
 *
 * <p>Only a player's own click or drag into the input slot stamps a furnace. Hoppers, hopper
 * minecarts and droppers move items without either event, so their input never touches the stamp;
 * {@code InventoryMoveItemEvent} is not used, since it names no slot and Paper's
 * {@code hopper.disable-move-event} turns it off. The credit is recorded on
 * {@link FurnaceSmeltEvent}, not on taking the ingot out, so a hopper collecting the output still
 * credits the loader and a player taking someone else's ingot is credited nothing.
 *
 * <h2>Folia</h2>
 *
 * A click or drag runs on the region owning the player, which owns a furnace in reach. Its stamp is
 * still written through {@link #atFurnace}, inline when this thread owns the block and on the
 * block's region otherwise. A smelt runs on the furnace's own region. The loader is credited by UUID
 * through {@link PersonalCreditStore}, which is safe from any thread; the loader's {@code Player} is
 * never touched here. A new credit goes to {@link CreditRefresh}, which hands the announcement to the
 * loader's own scheduler when this region does not own them.
 */
public final class FurnaceLoaderListener implements Listener {

    /** The raw slot of a furnace view's input slot, the top inventory's first. */
    static final int INPUT_SLOT = 0;

    private final Plugin plugin;
    private final PersonalCreditStore credits;
    private final CreditRefresh refresh;

    /** The PDC key the stamp is stored under: {@code antispeedrun:furnace-loader}. */
    private final NamespacedKey loaderKey;

    public FurnaceLoaderListener(Plugin plugin, PersonalCreditStore credits, CreditRefresh refresh) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.credits = Objects.requireNonNull(credits, "credits");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.loaderKey = new NamespacedKey(plugin, "furnace-loader");
    }

    /** A click that may put iron into, or take it out of, a furnace's input slot. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        InventoryView view = event.getView();
        if (!(view.getTopInventory() instanceof FurnaceInventory furnace) || !stamping(furnace)) {
            return;
        }
        Location location = furnace.getLocation();
        if (location == null) {
            return;
        }
        int inserted = 0;
        ItemStack placing = placing(event, view);
        if (placing != null && creditable(event.getWhoClicked())) {
            ItemStack existing = furnace.getSmelting();
            inserted = FurnaceLoaderRules.inserted(event.getAction(),
                    placing.getType(), placing.getAmount(),
                    type(existing), amount(existing),
                    Math.min(placing.getMaxStackSize(), furnace.getMaxStackSize()));
        }
        settle(location, event.getWhoClicked().getUniqueId(), inserted);
    }

    /** A drag that may spread iron into a furnace's input slot. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory() instanceof FurnaceInventory furnace) || !stamping(furnace)) {
            return;
        }
        Location location = furnace.getLocation();
        ItemStack dragged = event.getNewItems().get(INPUT_SLOT);
        if (location == null || dragged == null) {
            return;
        }
        int inserted = 0;
        if (creditable(event.getWhoClicked())) {
            ItemStack existing = furnace.getSmelting();
            inserted = FurnaceLoaderRules.dragged(dragged.getType(), dragged.getAmount(),
                    type(existing), amount(existing));
        }
        settle(location, event.getWhoClicked().getUniqueId(), inserted);
    }

    /** An iron ingot finished smelting: credit the stamped loader while the stamp has any left. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmelt(FurnaceSmeltEvent event) {
        if (!FurnaceLoaderRules.smeltsIron(event.getResult().getType())) {
            return;
        }
        Optional<UUID> credited = update(event.getBlock(), (live, stamp) -> {
            FurnaceLoaderRules.Smelt smelt = FurnaceLoaderRules.smelt(stamp);
            return new Update(smelt.after(), smelt.credited());
        });
        credited.ifPresent(loader -> {
            if (credits.record(loader, PersonalCredit.SMELTED_IRON, CreditSource.ACTION)) {
                refresh.changed(loader);
            }
        });
    }

    /**
     * Stamps the furnace for {@code inserted} items now, then caps the stamp at the iron left in the
     * input slot once the click has been applied, a tick later.
     */
    private void settle(Location location, UUID player, int inserted) {
        if (inserted > 0) {
            atFurnace(location, () -> update(location.getBlock(), (live, stamp) ->
                    new Update(FurnaceLoaderRules.load(stamp, player, inserted), Optional.empty())));
        }
        plugin.getServer().getRegionScheduler().run(plugin, location, task ->
                update(location.getBlock(), (live, stamp) -> {
                    ItemStack input = live.getInventory().getSmelting();
                    return new Update(FurnaceLoaderRules.clamp(stamp, type(input), amount(input)),
                            Optional.empty());
                }));
    }

    /** Runs {@code task} on the thread that owns {@code location}: inline if that is this one. */
    private void atFurnace(Location location, Runnable task) {
        if (plugin.getServer().isOwnedByCurrentRegion(location)) {
            task.run();
        } else {
            plugin.getServer().getRegionScheduler().execute(plugin, location, task);
        }
    }

    /** A stamp change, and the loader it credits. */
    private record Update(Optional<FurnaceLoaderRules.Stamp> stamp, Optional<UUID> credited) {
    }

    /**
     * Applies {@code change} to the stamp of the furnace at {@code block}, writing straight to the
     * live block entity's container. Must run on the thread that owns the block.
     *
     * @return the loader {@code change} credits; empty if the block is not a stamping furnace
     */
    private Optional<UUID> update(Block block,
                                  BiFunction<Furnace, Optional<FurnaceLoaderRules.Stamp>, Update> change) {
        if (!FurnaceLoaderRules.furnace(block.getType()) || !(block.getState(false) instanceof Furnace live)) {
            return Optional.empty();
        }
        PersistentDataContainer container = live.getPersistentDataContainer();
        Optional<FurnaceLoaderRules.Stamp> before = FurnaceLoaderRules.Stamp.decode(
                container.get(loaderKey, PersistentDataType.LONG_ARRAY));
        Update after = change.apply(live, before);
        if (!after.stamp().equals(before)) {
            if (after.stamp().isPresent()) {
                container.set(loaderKey, PersistentDataType.LONG_ARRAY, after.stamp().get().encode());
            } else {
                container.remove(loaderKey);
            }
        }
        return after.credited();
    }

    /**
     * The item a click puts into the input slot, if the click can put one there: the cursor for a
     * click on the slot, the hotbar or offhand item for a swap onto it, or the clicked item for a
     * shift-click from the player's inventory.
     */
    private static ItemStack placing(InventoryClickEvent event, InventoryView view) {
        InventoryAction action = event.getAction();
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return null;
        }
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return clicked == view.getBottomInventory() ? event.getCurrentItem() : null;
        }
        if (event.getRawSlot() != INPUT_SLOT) {
            return null;
        }
        return switch (action) {
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR -> event.getCursor();
            case HOTBAR_SWAP -> event.getClick() == ClickType.SWAP_OFFHAND
                    ? event.getWhoClicked().getInventory().getItemInOffHand()
                    : event.getHotbarButton() >= 0
                            ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton())
                            : null;
            default -> null;
        };
    }

    /** Whether the view is a furnace or blast furnace, the two that can smelt iron. */
    private static boolean stamping(FurnaceInventory furnace) {
        return furnace.getType() == InventoryType.FURNACE || furnace.getType() == InventoryType.BLAST_FURNACE;
    }

    private static boolean creditable(HumanEntity actor) {
        return PersonalCreditRules.creditable(actor instanceof Player,
                actor.hasMetadata(PersonalCreditListener.NPC_METADATA));
    }

    private static Material type(ItemStack item) {
        return item == null ? null : item.getType();
    }

    private static int amount(ItemStack item) {
        return item == null ? 0 : item.getAmount();
    }
}
