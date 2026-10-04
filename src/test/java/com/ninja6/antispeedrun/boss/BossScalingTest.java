package com.ninja6.antispeedrun.boss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.listeners.DragonReinforcementRules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity of the dragon count with {@code max(1, min(max-dragons, round(players * multiplier)))}
 * across parties of one to ten (#30, Task 8.2.3). {@code DragonReinforcementTest} pins three
 * multipliers by hand; this checks the formula against an integer-arithmetic reference over a wider
 * grid of multipliers, caps and modes, plus the invariants the specification table implies.
 */
class BossScalingTest {

    /** Multipliers in whole hundredths, so the reference needs no floating point. */
    private static final int[] HUNDREDTHS = {1, 10, 15, 25, 30, 33, 35, 45, 50, 55, 65, 70, 75, 99, 100, 125, 150, 200, 250};

    private static final int[] CAPS = {1, 2, 3, 5, 10};

    private static PluginConfig.MultiDragon config(int hundredths, PluginConfig.RoundingMode mode, int max) {
        return new PluginConfig.MultiDragon(true, hundredths / 100.0D, mode, max, true);
    }

    /** {@code round(n * hundredths / 100)} in integers; the product is always a whole number of hundredths. */
    private static int reference(int n, int hundredths, PluginConfig.RoundingMode mode, int max) {
        int product = n * hundredths;
        int rounded = switch (mode) {
            case FLOOR -> product / 100;
            case CEIL -> (product + 99) / 100;
            case HALF_UP -> (product + 50) / 100;
        };
        return n <= 1 ? 1 : Math.max(1, Math.min(max, rounded));
    }

    @Test
    @DisplayName("every mode matches the integer reference for N=1..10, across multipliers and caps")
    void matchesReference() {
        for (PluginConfig.RoundingMode mode : PluginConfig.RoundingMode.values()) {
            for (int hundredths : HUNDREDTHS) {
                for (int max : CAPS) {
                    PluginConfig.MultiDragon multiDragon = config(hundredths, mode, max);
                    for (int n = 1; n <= 10; n++) {
                        assertEquals(reference(n, hundredths, mode, max),
                                DragonReinforcementRules.dragonCount(n, multiDragon),
                                mode + " x" + hundredths / 100.0D + " cap " + max + " party " + n);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("FLOOR <= HALF_UP <= CEIL, and CEIL exceeds FLOOR by at most one, for every party")
    void modesAreOrdered() {
        for (int hundredths : HUNDREDTHS) {
            for (int n = 1; n <= 10; n++) {
                int floor = DragonReinforcementRules.dragonCount(n, config(hundredths, PluginConfig.RoundingMode.FLOOR, 10));
                int half = DragonReinforcementRules.dragonCount(n, config(hundredths, PluginConfig.RoundingMode.HALF_UP, 10));
                int ceil = DragonReinforcementRules.dragonCount(n, config(hundredths, PluginConfig.RoundingMode.CEIL, 10));
                String at = "x" + hundredths / 100.0D + " party " + n;
                assertTrue(floor <= half && half <= ceil, at);
                assertTrue(ceil - floor <= 1, at);
            }
        }
    }

    @Test
    @DisplayName("a larger party never earns fewer dragons, and the count never leaves 1..max-dragons")
    void monotonicAndBounded() {
        for (PluginConfig.RoundingMode mode : PluginConfig.RoundingMode.values()) {
            for (int hundredths : HUNDREDTHS) {
                for (int max : CAPS) {
                    PluginConfig.MultiDragon multiDragon = config(hundredths, mode, max);
                    int previous = 0;
                    for (int n = 1; n <= 10; n++) {
                        int count = DragonReinforcementRules.dragonCount(n, multiDragon);
                        assertTrue(count >= previous, mode + " x" + hundredths / 100.0D + " party " + n);
                        assertTrue(count >= 1 && count <= max, mode + " x" + hundredths / 100.0D + " party " + n);
                        previous = count;
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("exact halves tie upward under HALF_UP: 0.25 and 0.75 at every party that lands on a half")
    void halfTies() {
        PluginConfig.MultiDragon quarter = config(25, PluginConfig.RoundingMode.HALF_UP, 10);
        // 2 * 0.25 = 0.5 -> 1 (also the floor of one), 6 * 0.25 = 1.5 -> 2, 10 * 0.25 = 2.5 -> 3
        assertEquals(1, DragonReinforcementRules.dragonCount(2, quarter));
        assertEquals(2, DragonReinforcementRules.dragonCount(6, quarter));
        assertEquals(3, DragonReinforcementRules.dragonCount(10, quarter));
        PluginConfig.MultiDragon threeQuarters = config(75, PluginConfig.RoundingMode.HALF_UP, 10);
        // 2 * 0.75 = 1.5 -> 2, 6 * 0.75 = 4.5 -> 5
        assertEquals(2, DragonReinforcementRules.dragonCount(2, threeQuarters));
        assertEquals(5, DragonReinforcementRules.dragonCount(6, threeQuarters));
    }

    @Test
    @DisplayName("a multiplier of 1.0 gives one dragon per player up to the cap, under every mode")
    void unitMultiplier() {
        for (PluginConfig.RoundingMode mode : PluginConfig.RoundingMode.values()) {
            for (int n = 1; n <= 10; n++) {
                assertEquals(Math.min(n, 6), DragonReinforcementRules.dragonCount(n, config(100, mode, 6)),
                        mode + " party " + n);
            }
        }
    }

    @Test
    @DisplayName("max-dragons of one pins every party of every size to a single dragon")
    void capOfOne() {
        for (PluginConfig.RoundingMode mode : PluginConfig.RoundingMode.values()) {
            for (int n = 0; n <= 10; n++) {
                assertEquals(1, DragonReinforcementRules.dragonCount(n, config(250, mode, 1)), mode + " party " + n);
                assertEquals(0, DragonReinforcementRules.secondaryCount(n, config(250, mode, 1)), mode + " party " + n);
            }
        }
    }

    @Test
    @DisplayName("the shipped defaults are HALF_UP, a multiplier of 0.5 and a cap of 5")
    void shippedDefaults() {
        PluginConfig.MultiDragon shipped = PluginConfig.defaults().bossScaling().multiDragon();
        assertEquals(PluginConfig.RoundingMode.HALF_UP, shipped.roundingMode());
        assertEquals(0.5D, shipped.multiplier());
        assertEquals(5, shipped.maxDragons());
    }
}
