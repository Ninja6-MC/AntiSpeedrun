package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.ConfigLoadException;
import com.ninja6.antispeedrun.config.MapConfigSection;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.listeners.TrimLockRules.EquipSource;
import com.ninja6.antispeedrun.listeners.TrimLockRules.Lock;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.TrimProgressionManager;

/** The smithing and wearing locks for armor trims (#19, Task 5.1.3). */
class TrimLockRulesTest {

    private static PluginConfig trims(Map<String, Object> section) throws ConfigLoadException {
        return PluginConfig.from(MapConfigSection.of(Map.of("trim-progression", section)));
    }

    @Nested
    @DisplayName("lock toggles")
    class Toggles {

        @Test
        @DisplayName("the shipped configuration turns both locks on")
        void onByDefault() {
            PluginConfig config = PluginConfig.defaults();
            assertTrue(TrimLockRules.smithingLocked(config));
            assertTrue(TrimLockRules.wearingLocked(config));
        }

        @Test
        @DisplayName("enabled: false turns both locks off")
        void masterSwitch() throws ConfigLoadException {
            PluginConfig config = trims(Map.of("enabled", false));
            assertFalse(TrimLockRules.smithingLocked(config));
            assertFalse(TrimLockRules.wearingLocked(config));
        }

        @Test
        @DisplayName("each lock follows its own key")
        void independent() throws ConfigLoadException {
            PluginConfig smithingOnly = trims(Map.of(
                    "enabled", true,
                    "block-unearned-smithing", true,
                    "block-wearing-unearned-trims", false));
            assertTrue(TrimLockRules.smithingLocked(smithingOnly));
            assertFalse(TrimLockRules.wearingLocked(smithingOnly));

            PluginConfig wearingOnly = trims(Map.of(
                    "enabled", true,
                    "block-unearned-smithing", false,
                    "block-wearing-unearned-trims", true));
            assertFalse(TrimLockRules.smithingLocked(wearingOnly));
            assertTrue(TrimLockRules.wearingLocked(wearingOnly));
        }
    }

    @Nested
    @DisplayName("smithing gate")
    class Smithing {

        @Test
        @DisplayName("a Silence template needs the Ancient City")
        void silence() {
            assertEquals(Optional.of(TrimProgressionManager.ANCIENT_CITY),
                    TrimLockRules.smithingGate(Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE, null));
        }

        @Test
        @DisplayName("the netherite upgrade template needs a Bastion Remnant")
        void netheriteUpgrade() {
            assertEquals(Optional.of(TrimProgressionManager.BASTION),
                    TrimLockRules.smithingGate(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, null));
        }

        @Test
        @DisplayName("an ungated template with an ungated result is free")
        void ungated() {
            assertEquals(Optional.empty(), TrimLockRules.smithingGate(
                    Material.COAST_ARMOR_TRIM_SMITHING_TEMPLATE, NamespacedKey.minecraft("coast")));
        }

        @Test
        @DisplayName("the result's trim is the fallback when the template says nothing")
        void resultFallback() {
            assertEquals(Optional.of(TrimProgressionManager.END_CITY),
                    TrimLockRules.smithingGate(null, NamespacedKey.minecraft("spire")));
            assertEquals(Optional.of(TrimProgressionManager.ANCIENT_CITY),
                    TrimLockRules.smithingGate(Material.DIAMOND, NamespacedKey.minecraft("ward")));
        }

        @Test
        @DisplayName("an empty template slot and an untrimmed result are free")
        void nothing() {
            assertEquals(Optional.empty(), TrimLockRules.smithingGate(null, null));
        }
    }

    @Nested
    @DisplayName("equip source")
    class Equip {

        @Test
        @DisplayName("placing or swapping the cursor onto an armor slot equips the cursor")
        void cursor() {
            for (InventoryAction action : new InventoryAction[] {InventoryAction.PLACE_ALL,
                    InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME, InventoryAction.SWAP_WITH_CURSOR}) {
                assertEquals(EquipSource.CURSOR, TrimLockRules.equipSource(
                        SlotType.ARMOR, action, ClickType.LEFT, true, true, false), action.name());
            }
        }

        @Test
        @DisplayName("a number key over an armor slot equips the hotbar stack; F equips the off hand")
        @SuppressWarnings("removal")
        void hotbar() {
            assertEquals(EquipSource.HOTBAR, TrimLockRules.equipSource(
                    SlotType.ARMOR, InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY, true, true, false));
            assertEquals(EquipSource.HOTBAR, TrimLockRules.equipSource(
                    SlotType.ARMOR, InventoryAction.HOTBAR_MOVE_AND_READD, ClickType.NUMBER_KEY,
                    true, true, false));
            assertEquals(EquipSource.OFF_HAND, TrimLockRules.equipSource(
                    SlotType.ARMOR, InventoryAction.HOTBAR_SWAP, ClickType.SWAP_OFFHAND, true, true, false));
        }

        @Test
        @DisplayName("taking a piece off an armor slot is never refused")
        void takingOff() {
            assertEquals(EquipSource.NONE, TrimLockRules.equipSource(
                    SlotType.ARMOR, InventoryAction.PICKUP_ALL, ClickType.LEFT, true, true, false));
            assertEquals(EquipSource.NONE, TrimLockRules.equipSource(
                    SlotType.ARMOR, InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT,
                    true, true, false));
        }

        @Test
        @DisplayName("a shift-click equips only from the own inventory screen into an empty slot")
        void shiftClick() {
            assertEquals(EquipSource.CLICKED, TrimLockRules.equipSource(SlotType.CONTAINER,
                    InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT, true, true, true));
            assertEquals(EquipSource.NONE, TrimLockRules.equipSource(SlotType.CONTAINER,
                    InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT, false, true, true));
            assertEquals(EquipSource.NONE, TrimLockRules.equipSource(SlotType.CONTAINER,
                    InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT, true, true, false));
            assertEquals(EquipSource.NONE, TrimLockRules.equipSource(SlotType.CRAFTING,
                    InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT, true, false, true));
        }

        @Test
        @DisplayName("an ordinary click elsewhere equips nothing")
        void elsewhere() {
            assertEquals(EquipSource.NONE, TrimLockRules.equipSource(
                    SlotType.CONTAINER, InventoryAction.PLACE_ALL, ClickType.LEFT, true, true, true));
            assertEquals(EquipSource.NONE, TrimLockRules.equipSource(
                    SlotType.QUICKBAR, InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY, true, true, true));
        }
    }

    @Nested
    @DisplayName("feedback")
    class Feedback {

        @Test
        @DisplayName("the Ancient City line says how it is proven")
        void ancientCity() {
            assertEquals("Explore an Ancient City and sneak past a Sculk Sensor or Warden",
                    TrimLockRules.requirement(TrimProgressionManager.ANCIENT_CITY));
        }

        @Test
        @DisplayName("the other structures take the right article")
        void articles() {
            assertEquals("Explore a Bastion Remnant", TrimLockRules.requirement(TrimProgressionManager.BASTION));
            assertEquals("Explore an End City", TrimLockRules.requirement(TrimProgressionManager.END_CITY));
        }

        @Test
        @DisplayName("the rejection names what was refused")
        void rejection() {
            assertEquals("<red>Explore an End City first to wear this.",
                    TrimLockRules.rejection(Lock.WEARING, "Explore an End City"));
            assertEquals("<red>Explore a Bastion Remnant first to forge this.",
                    TrimLockRules.rejection(Lock.SMITHING, "Explore a Bastion Remnant"));
        }

        @Test
        @DisplayName("smithing and wearing refusals throttle separately, per structure")
        void keys() {
            Milestone city = TrimProgressionManager.END_CITY;
            assertFalse(TrimLockRules.feedbackKey(Lock.SMITHING, city)
                    .equals(TrimLockRules.feedbackKey(Lock.WEARING, city)));
            assertFalse(TrimLockRules.feedbackKey(Lock.WEARING, city)
                    .equals(TrimLockRules.feedbackKey(Lock.WEARING, TrimProgressionManager.BASTION)));
        }
    }

    @Nested
    @DisplayName("listener registration")
    class Registration {

        private void assertHandler(String name, Class<? extends Event> type, boolean ignoreCancelled)
                throws NoSuchMethodException {
            Method handler = TrimSmithingListener.class.getMethod(name, type);
            EventHandler annotation = handler.getAnnotation(EventHandler.class);
            assertNotNull(annotation, name + " must carry @EventHandler");
            assertEquals(EventPriority.HIGH, annotation.priority(), name);
            assertEquals(ignoreCancelled, annotation.ignoreCancelled(), name);
        }

        @Test
        @DisplayName("every channel is handled at HIGH")
        void handlers() throws NoSuchMethodException {
            assertHandler("onPrepareSmithing", PrepareSmithingEvent.class, false);
            assertHandler("onSmith", SmithItemEvent.class, true);
            assertHandler("onInventoryClick", InventoryClickEvent.class, true);
            assertHandler("onInventoryDrag", InventoryDragEvent.class, true);
            // A right-click in the air arrives cancelled, so this one must see cancelled events.
            assertHandler("onEquipFromHand", PlayerInteractEvent.class, false);
            assertHandler("onDispenseArmor", BlockDispenseArmorEvent.class, true);
            Method baseArmor = ItemProgressionListener.class
                    .getMethod("onEquipArmorFromHand", PlayerInteractEvent.class);
            EventHandler annotation = baseArmor.getAnnotation(EventHandler.class);
            assertNotNull(annotation);
            assertEquals(EventPriority.HIGH, annotation.priority());
            assertFalse(annotation.ignoreCancelled());
        }
    }
}
