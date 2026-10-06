package com.ninja6.antispeedrun.listeners;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;

import static com.ninja6.antispeedrun.listeners.ItemGateRules.Subject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundle gate (#15, Task 4.3.2, with audit amendment R-14).
 *
 * <p>The per-stack decision is {@code ItemProgressionListener.refuse}, shared with every other item
 * path. What is pinned here is which stack a click treats as the bundle being emptied, that the
 * answer is independent of where in the view the bundle sits, the configuration switch, and the
 * spray handler's registration.
 */
class BundleGateTest {

    @Test
    @DisplayName("pulling an item out of a clicked bundle names the clicked slot as the bundle")
    void pickupFromBundle() {
        assertEquals(Subject.CLICKED_SLOT,
                InventoryGestures.bundleSource(InventoryAction.PICKUP_FROM_BUNDLE));
    }

    @Test
    @DisplayName("placing an item out of a bundle on the cursor names the cursor as the bundle")
    void placeFromBundle() {
        assertEquals(Subject.CURSOR,
                InventoryGestures.bundleSource(InventoryAction.PLACE_FROM_BUNDLE));
    }

    /**
     * Exhaustive, because the failure mode is an extraction action added to the API or a filling
     * action mistaken for one. Filling a bundle takes nothing out of it, and every other action
     * moves no bundle contents at all.
     */
    @Test
    @DisplayName("no other action empties a bundle")
    void onlyTheTwoExtractionsEmptyABundle() {
        Set<InventoryAction> extractions =
                EnumSet.of(InventoryAction.PICKUP_FROM_BUNDLE, InventoryAction.PLACE_FROM_BUNDLE);
        for (InventoryAction action : InventoryAction.values()) {
            if (!extractions.contains(action)) {
                assertEquals(Subject.NONE, InventoryGestures.bundleSource(action),
                        action + " must not be read as emptying a bundle");
            }
        }
    }

    /**
     * R-14's point. The withdrawal rule answers {@code NONE} for any click on the player's own
     * half of the view, which is where a bundle in the hotbar, main inventory or off hand sits, so
     * an extraction there is only ever caught by {@link InventoryGestures#bundleSource} — whose
     * answer takes no view position at all.
     */
    @Test
    @DisplayName("an extraction in the player's own inventory is invisible to the withdrawal rule")
    void ownInventoryNeedsTheBundleRule() {
        assertEquals(Subject.NONE, ItemGateRules.withdrawn(
                InventoryGestures.of(InventoryAction.PICKUP_FROM_BUNDLE, ClickType.RIGHT), false));
        assertEquals(Subject.CLICKED_SLOT,
                InventoryGestures.bundleSource(InventoryAction.PICKUP_FROM_BUNDLE));
    }

    @Test
    @DisplayName("the shipped configuration gates bundle contents")
    void onByDefault() {
        assertTrue(ItemGateRules.gatesBundles(PluginConfig.defaults()));
    }

    @Test
    @DisplayName("gate-nested-bundles: false turns the bundle gate off")
    void switchedOff() {
        PluginConfig base = PluginConfig.defaults();
        PluginConfig.ItemProgression items = base.itemProgression();
        PluginConfig off = new PluginConfig(base.profile(), base.dimensionGates(),
                new PluginConfig.ItemProgression(items.enabled(), items.dropRecallEnabled(),
                        items.gateDispensers(), false, items.feedbackCooldownSeconds(),
                        items.rejectionMessage(), items.gatedItems(), items.requirePersonalCredit(),
                        items.countStructureLoot()),
                base.trimProgression(), base.idleReminder(), base.progressCard(),
                base.journeyBook(), base.bossScaling(), base.antiCheese(),
                base.villagerProgression(), List.of());
        assertFalse(ItemGateRules.gatesBundles(off));
    }

    /**
     * A right click on air reaches the listener already cancelled, so a spray handler that skipped
     * cancelled events would never see the case it exists for.
     */
    @Test
    @DisplayName("the bundle spray is handled at HIGH and does not skip cancelled events")
    void spraySubscribed() throws NoSuchMethodException {
        Method handler = ItemProgressionListener.class
                .getMethod("onBundleUse", PlayerInteractEvent.class);
        EventHandler annotation = handler.getAnnotation(EventHandler.class);
        assertNotNull(annotation, "the handler must carry @EventHandler");
        assertEquals(EventPriority.HIGH, annotation.priority());
        assertFalse(annotation.ignoreCancelled());
    }
}
