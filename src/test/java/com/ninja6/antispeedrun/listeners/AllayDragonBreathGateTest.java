package com.ninja6.antispeedrun.listeners;

import java.lang.reflect.Method;
import java.util.List;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.listeners.AllayHandoffRules.Transfer;
import com.ninja6.antispeedrun.progression.MilestoneRequirement;
import com.ninja6.antispeedrun.storage.DimensionUnlock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Allay hand-off and Dragon's Breath bottling gates (#17, Task 4.3.4).
 *
 * <p>The per-stack decision for an Allay is {@code ItemProgressionListener.refuse}, covered by
 * {@link ItemGateRulesTest}; what is pinned here is which stack a click moves, the End requirement
 * bottling borrows, its waivers, and that both handlers are subscribed as intended.
 */
class AllayDragonBreathGateTest {

    @Nested
    @DisplayName("Allay hand-off")
    class Allay {

        @Test
        @DisplayName("an empty Allay offered an item takes the player's item")
        void given() {
            assertEquals(Transfer.GIVEN, AllayHandoffRules.transfer(true, false, true));
            assertEquals(Transfer.GIVEN, AllayHandoffRules.transfer(true, false, false));
        }

        @Test
        @DisplayName("an empty main hand takes the Allay's item back")
        void returned() {
            assertEquals(Transfer.RETURNED, AllayHandoffRules.transfer(false, true, true));
        }

        @Test
        @DisplayName("an empty off hand takes nothing back")
        void offHandReturnsNothing() {
            assertEquals(Transfer.NONE, AllayHandoffRules.transfer(false, true, false));
        }

        @Test
        @DisplayName("both hands full, or both empty, moves nothing")
        void nothingMoves() {
            assertEquals(Transfer.NONE, AllayHandoffRules.transfer(false, false, true));
            assertEquals(Transfer.NONE, AllayHandoffRules.transfer(true, true, true));
        }

        @Test
        @DisplayName("the Allay interaction is handled at HIGH and ignores cancelled events")
        void subscribed() throws NoSuchMethodException {
            EventHandler annotation = handler("onAllayInteract", PlayerInteractEntityEvent.class);
            assertEquals(EventPriority.HIGH, annotation.priority());
            assertTrue(annotation.ignoreCancelled());
        }
    }

    @Nested
    @DisplayName("Dragon's Breath bottling")
    class DragonBreath {

        @Test
        @DisplayName("the shipped configuration arms the gate")
        void armedByDefault() {
            assertTrue(DragonBreathRules.armed(PluginConfig.defaults()));
        }

        @Test
        @DisplayName("disabling the End gate disarms it")
        void followsEndGate() {
            PluginConfig base = PluginConfig.defaults();
            PluginConfig.DimensionGate end = base.dimensionGates().theEnd();
            PluginConfig off = new PluginConfig(base.profile(),
                    new PluginConfig.DimensionGates(base.dimensionGates().nether(),
                            new PluginConfig.DimensionGate(false, end.requirePlaytimeHours(),
                                    end.requireAccountAgeDays(), end.requireAdvancements(),
                                    end.rejectionMessage())),
                    base.itemProgression(), base.trimProgression(), base.idleReminder(),
                    base.progressCard(), base.journeyBook(), base.bossScaling(), base.antiCheese(),
                    base.villagerProgression(), List.of());
            assertFalse(DragonBreathRules.armed(off));
        }

        @Test
        @DisplayName("the requirement is exactly the End gate's")
        void endRequirement() {
            PluginConfig config = PluginConfig.defaults();
            MilestoneRequirement expected =
                    DimensionGateRules.requirement(DimensionUnlock.THE_END, config);
            assertEquals(expected, DragonBreathRules.requirement(config));
        }

        @Test
        @DisplayName("every End waiver and the item bypass each waive it on their own")
        void waivers() {
            assertFalse(DragonBreathRules.waived(false, false, false, false));
            assertTrue(DragonBreathRules.waived(true, false, false, false));
            assertTrue(DragonBreathRules.waived(false, true, false, false));
            assertTrue(DragonBreathRules.waived(false, false, true, false));
            assertTrue(DragonBreathRules.waived(false, false, false, true));
        }

        @Test
        @DisplayName("its feedback key cannot collide with a tier id")
        void feedbackKey() {
            assertTrue(DragonBreathRules.FEEDBACK_KEY.indexOf(':') >= 0);
        }

        @Test
        @DisplayName("bottling is handled at HIGH and sees already-cancelled air clicks")
        void subscribed() throws NoSuchMethodException {
            EventHandler annotation = handler("onBottleDragonBreath", PlayerInteractEvent.class);
            assertEquals(EventPriority.HIGH, annotation.priority());
            assertFalse(annotation.ignoreCancelled());
        }
    }

    private static EventHandler handler(String name, Class<?> event) throws NoSuchMethodException {
        Method method = ItemProgressionListener.class.getMethod(name, event);
        EventHandler annotation = method.getAnnotation(EventHandler.class);
        assertNotNull(annotation, "the handler must carry @EventHandler");
        return annotation;
    }
}
