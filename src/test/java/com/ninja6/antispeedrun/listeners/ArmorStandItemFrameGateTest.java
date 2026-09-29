package com.ninja6.antispeedrun.listeners;

import java.lang.reflect.Method;
import java.util.EnumSet;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent.ItemFrameChangeAction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The armor stand and item frame gates (#16, Task 4.3.3).
 *
 * <p>The per-stack decision is {@code ItemProgressionListener.refuse}, shared with every other item
 * path and covered by {@link ItemGateRulesTest}. The listener cannot be class-initialised off-server
 * (see {@link InventoryGestures}), so what is pinned here is that both events are subscribed at the
 * priority the other item gates use, skipping events another plugin already cancelled, and that the
 * frame actions the handler reasons about are the complete set.
 */
class ArmorStandItemFrameGateTest {

    @Test
    @DisplayName("the armor stand event is handled at HIGH and ignores cancelled events")
    void armorStandSubscribed() throws NoSuchMethodException {
        assertHighAndIgnoresCancelled(ItemProgressionListener.class
                .getMethod("onArmorStandManipulate", PlayerArmorStandManipulateEvent.class));
    }

    @Test
    @DisplayName("the item frame event is handled at HIGH and ignores cancelled events")
    void itemFrameSubscribed() throws NoSuchMethodException {
        assertHighAndIgnoresCancelled(ItemProgressionListener.class
                .getMethod("onItemFrameChange", PlayerItemFrameChangeEvent.class));
    }

    /**
     * The handler refuses {@code REMOVE} and lets everything else through. An action added to the
     * API would pass silently, so it has to fail here first and be classified on purpose.
     */
    @Test
    @DisplayName("place, remove and rotate are the only item frame actions")
    void frameActionsAreKnown() {
        assertEquals(EnumSet.of(ItemFrameChangeAction.PLACE, ItemFrameChangeAction.REMOVE,
                        ItemFrameChangeAction.ROTATE),
                EnumSet.allOf(ItemFrameChangeAction.class));
    }

    private static void assertHighAndIgnoresCancelled(Method handler) {
        EventHandler annotation = handler.getAnnotation(EventHandler.class);
        assertNotNull(annotation, "the handler must carry @EventHandler");
        assertEquals(EventPriority.HIGH, annotation.priority());
        assertTrue(annotation.ignoreCancelled());
    }
}
