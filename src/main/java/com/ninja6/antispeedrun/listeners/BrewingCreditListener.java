package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import com.ninja6.antispeedrun.storage.BrewedEffectStore;

/** Credits the player who hand-loads ingredients; bottle collection never earns credit. */
public final class BrewingCreditListener implements Listener {

    static final int INPUT_SLOT = 3;

    private final Plugin plugin;
    private final BrewedEffectStore credits;

    private final NamespacedKey loaderKey;

    public BrewingCreditListener(Plugin plugin, BrewedEffectStore credits) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.credits = Objects.requireNonNull(credits, "credits");
        this.loaderKey = new NamespacedKey(plugin, "brewing-loader");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        InventoryView view = event.getView();
        if (!(view.getTopInventory() instanceof BrewerInventory stand) || !stamping(stand)) {
            return;
        }
        Location location = stand.getLocation();
        if (location == null) {
            return;
        }
        int inserted = 0;
        ItemStack placing = placing(event, view);
        if (placing != null && (event.getAction() != InventoryAction.MOVE_TO_OTHER_INVENTORY
                || PotionEffects.ingredient(placing.getType())) && creditable(event.getWhoClicked())) {
            ItemStack existing = stand.getIngredient();
            inserted = BrewingLoaderRules.inserted(event.getAction(),
                    placing.getType(), placing.getAmount(),
                    type(existing), amount(existing),
                    Math.min(placing.getMaxStackSize(), stand.getMaxStackSize()));
        }
        settle(location, event.getWhoClicked().getUniqueId(), inserted);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory() instanceof BrewerInventory stand) || !stamping(stand)) {
            return;
        }
        Location location = stand.getLocation();
        ItemStack dragged = event.getNewItems().get(INPUT_SLOT);
        if (location == null || dragged == null) {
            return;
        }
        int inserted = 0;
        if (creditable(event.getWhoClicked())) {
            ItemStack existing = stand.getIngredient();
            inserted = Math.max(0, dragged.getAmount() - (type(existing) == dragged.getType() ? amount(existing) : 0));
        }
        settle(location, event.getWhoClicked().getUniqueId(), inserted);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        java.util.Set<String> introduced = new java.util.HashSet<>();
        for (int slot = 0; slot < Math.min(3, event.getResults().size()); slot++) {
            introduced.addAll(PotionGateRules.introduced(PotionEffects.ids(event.getContents().getItem(slot)),
                    PotionEffects.ids(event.getResults().get(slot))));
        }
        Optional<UUID> credited = update(event.getBlock(), (live, stamp) -> {
            BrewingLoaderRules.Brew used = BrewingLoaderRules.brew(stamp);
            return new Update(used.after(), used.credited());
        });
        credited.ifPresent(loader -> introduced.forEach(effect -> credits.record(loader, effect, true)));
    }

    private void settle(Location location, UUID player, int inserted) {
        if (inserted > 0) {
            atStand(location, () -> update(location.getBlock(), (live, stamp) ->
                    new Update(BrewingLoaderRules.load(stamp, player, inserted), Optional.empty())));
        }
        plugin.getServer().getRegionScheduler().run(plugin, location, task ->
                update(location.getBlock(), (live, stamp) -> {
                    ItemStack input = live.getInventory().getIngredient();
                    return new Update(stamp.filter(value -> amount(input) > 0)
                            .map(value -> new BrewingLoaderRules.Stamp(value.loader(),
                                    Math.min(value.remaining(), amount(input)))),
                            Optional.empty());
                }));
    }

    private void atStand(Location location, Runnable task) {
        if (plugin.getServer().isOwnedByCurrentRegion(location)) {
            task.run();
        } else {
            plugin.getServer().getRegionScheduler().execute(plugin, location, task);
        }
    }

    private record Update(Optional<BrewingLoaderRules.Stamp> stamp, Optional<UUID> credited) {
    }

    private Optional<UUID> update(Block block,
                                  BiFunction<BrewingStand, Optional<BrewingLoaderRules.Stamp>, Update> change) {
        if (!(block.getState(false) instanceof BrewingStand live)) {
            return Optional.empty();
        }
        PersistentDataContainer container = live.getPersistentDataContainer();
        Optional<BrewingLoaderRules.Stamp> before = BrewingLoaderRules.Stamp.decode(
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

    private static ItemStack placing(InventoryClickEvent event, InventoryView view) {
        InventoryAction action = event.getAction();
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return null;
        }
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            ItemStack item = event.getCurrentItem();
            if (clicked != view.getBottomInventory() || item == null) return null;
            if (item.getType() == Material.BLAZE_POWDER) return null;
            return item;
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

    private static boolean stamping(BrewerInventory stand) {
        return stand.getType() == InventoryType.BREWING;
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
