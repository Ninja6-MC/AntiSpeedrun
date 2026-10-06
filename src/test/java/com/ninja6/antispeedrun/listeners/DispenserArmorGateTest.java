package com.ninja6.antispeedrun.listeners;

import java.lang.reflect.Method;
import java.util.List;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dispenser armour gate's registration (#14, Task 4.3.1).
 *
 * <p>The decision itself is {@code ItemProgressionListener.refuse}, shared with every other item
 * path and covered by {@link ItemGateRulesTest}; what is left to pin here is that the dispenser
 * event is subscribed at all, at the priority the other item gates use, and skipping events another
 * plugin already cancelled.
 */
class DispenserArmorGateTest {

    @Test
    @DisplayName("the dispenser armour event is handled at HIGH and ignores cancelled events")
    void subscribed() throws NoSuchMethodException {
        Method handler = ItemProgressionListener.class
                .getMethod("onDispenseArmor", BlockDispenseArmorEvent.class);
        EventHandler annotation = handler.getAnnotation(EventHandler.class);
        assertNotNull(annotation, "the handler must carry @EventHandler");
        assertEquals(EventPriority.HIGH, annotation.priority());
        assertTrue(annotation.ignoreCancelled());
    }

    @Test
    @DisplayName("the shipped configuration gates dispensers")
    void onByDefault() {
        assertTrue(ItemGateRules.gatesDispensers(PluginConfig.defaults()));
    }

    @Test
    @DisplayName("gate-dispensers: false turns the dispenser gate off")
    void switchedOff() {
        PluginConfig base = PluginConfig.defaults();
        PluginConfig.ItemProgression items = base.itemProgression();
        PluginConfig off = new PluginConfig(base.profile(), base.dimensionGates(),
                new PluginConfig.ItemProgression(items.enabled(), items.dropRecallEnabled(), false,
                        items.gateNestedBundles(), items.feedbackCooldownSeconds(),
                        items.rejectionMessage(), items.gatedItems(), items.requirePersonalCredit(),
                        items.countStructureLoot()),
                base.trimProgression(), base.idleReminder(), base.progressCard(),
                base.journeyBook(), base.bossScaling(), base.antiCheese(),
                base.villagerProgression(), List.of());
        assertFalse(ItemGateRules.gatesDispensers(off));
    }
}
