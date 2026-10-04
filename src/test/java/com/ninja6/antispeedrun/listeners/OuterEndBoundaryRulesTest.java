package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * The outer-End boundary's decisions (#26, Task 7.2.1). {@link OuterEndBoundaryListener} needs a
 * server, and stays untested for the reason {@link ItemGateRulesTest} records.
 */
class OuterEndBoundaryRulesTest {

    private static PluginConfig with(boolean enabled, boolean gateway) {
        PluginConfig base = PluginConfig.defaults();
        PluginConfig.AntiCheese a = base.antiCheese();
        return new PluginConfig(base.profile(), base.dimensionGates(), base.itemProgression(),
                base.trimProgression(), base.idleReminder(), base.progressCard(),
                base.journeyBook(), base.bossScaling(),
                new PluginConfig.AntiCheese(enabled, a.blockBedAnchorBossDamage(),
                        a.maxSingleHitBossDamage(), a.blockEarlyEyeThrowing(),
                        a.earlyEyeRejectionMessage(), a.blockExitPortalCrystalPlace(), gateway,
                        a.outerEndRadius(), a.outerEndPollSeconds()),
                base.villagerProgression(), List.of());
    }

    @Nested
    @DisplayName("the off switches")
    class Armed {

        @Test
        @DisplayName("the shipped configuration leaves the boundary off")
        void offByDefault() {
            assertFalse(PluginConfig.defaults().antiCheese().blockGatewayPreDragon());
            assertFalse(OuterEndBoundaryRules.armed(PluginConfig.defaults()));
        }

        @Test
        @DisplayName("it needs both the rule and the anti-cheese master switch")
        void needsBoth() {
            assertTrue(OuterEndBoundaryRules.armed(with(true, true)));
            assertFalse(OuterEndBoundaryRules.armed(with(true, false)));
            assertFalse(OuterEndBoundaryRules.armed(with(false, true)));
        }
    }

    @Test
    @DisplayName("the boundary is up only once the flag has been read and shows no kill")
    void lockedNeedsAKnownFlag() {
        assertFalse(OuterEndBoundaryRules.locked(false, false));
        assertFalse(OuterEndBoundaryRules.locked(false, true));
        assertFalse(OuterEndBoundaryRules.locked(true, true));
        assertTrue(OuterEndBoundaryRules.locked(true, false));
    }

    @Nested
    @DisplayName("the radius")
    class Radius {

        @Test
        @DisplayName("is horizontal, and a point on it is still inside")
        void edgeInclusive() {
            assertFalse(OuterEndBoundaryRules.outside(500, 0, 500));
            assertFalse(OuterEndBoundaryRules.outside(0, -500, 500));
            assertTrue(OuterEndBoundaryRules.outside(500.01, 0, 500));
            assertTrue(OuterEndBoundaryRules.outside(0, 501, 500));
        }

        @Test
        @DisplayName("is circular, not square")
        void circular() {
            assertFalse(OuterEndBoundaryRules.outside(350, 350, 500));
            assertTrue(OuterEndBoundaryRules.outside(360, 360, 500));
        }

        @Test
        @DisplayName("follows the configured value")
        void configured() {
            assertTrue(OuterEndBoundaryRules.outside(300, 0, 250));
            assertFalse(OuterEndBoundaryRules.outside(300, 0, 1000));
        }

        @Test
        @DisplayName("edge pulls a point back inside along the line to the origin")
        void edge() {
            double[] p = OuterEndBoundaryRules.edge(1000, 0, 500);
            assertArrayEquals(new double[] {498, 0}, p, 1e-9);
            double[] d = OuterEndBoundaryRules.edge(0, -2000, 500);
            assertArrayEquals(new double[] {0, -498}, d, 1e-9);
            assertFalse(OuterEndBoundaryRules.outside(p[0], p[1], 500));
            assertArrayEquals(new double[] {0, 0}, OuterEndBoundaryRules.edge(0, 0, 500), 0);
        }
    }

    @Nested
    @DisplayName("who is held")
    class Applies {

        @Test
        @DisplayName("survival and adventure players are, creative and spectator are not")
        void modes() {
            assertTrue(OuterEndBoundaryRules.applies("SURVIVAL", false));
            assertTrue(OuterEndBoundaryRules.applies("adventure", false));
            assertFalse(OuterEndBoundaryRules.applies("CREATIVE", false));
            assertFalse(OuterEndBoundaryRules.applies("SPECTATOR", false));
        }

        @Test
        @DisplayName("a bypass waives it")
        void waived() {
            assertFalse(OuterEndBoundaryRules.applies("SURVIVAL", true));
        }
    }

    @Nested
    @DisplayName("teleports")
    class Teleports {

        @Test
        @DisplayName("pearls and chorus fruit are refused at the event, nothing else is")
        void causes() {
            assertTrue(OuterEndBoundaryRules.selfInflicted("ENDER_PEARL"));
            assertTrue(OuterEndBoundaryRules.selfInflicted("CHORUS_FRUIT"));
            assertFalse(OuterEndBoundaryRules.selfInflicted("COMMAND"));
            assertFalse(OuterEndBoundaryRules.selfInflicted("END_PORTAL"));
            assertFalse(OuterEndBoundaryRules.selfInflicted("PLUGIN"));
        }

        @Test
        @DisplayName("a landing beyond the radius is refused only while locked")
        void refuses() {
            assertTrue(OuterEndBoundaryRules.refuses(true, 600, 0, 500));
            assertFalse(OuterEndBoundaryRules.refuses(true, 400, 0, 500));
            assertFalse(OuterEndBoundaryRules.refuses(false, 600, 0, 500));
        }
    }

    @Nested
    @DisplayName("the poll")
    class Poll {

        @Test
        @DisplayName("forgets everything once the world is unlocked")
        void unlocked() {
            assertEquals(OuterEndBoundaryRules.Poll.FORGET,
                    OuterEndBoundaryRules.poll(false, 900, 0, 500, true));
        }

        @Test
        @DisplayName("remembers a position inside")
        void inside() {
            assertEquals(OuterEndBoundaryRules.Poll.REMEMBER,
                    OuterEndBoundaryRules.poll(true, 100, 100, 500, false));
        }

        @Test
        @DisplayName("sends a crosser back to the last inside position, or the edge if none")
        void crossed() {
            assertEquals(OuterEndBoundaryRules.Poll.RETURN_TO_LAST,
                    OuterEndBoundaryRules.poll(true, 520, 0, 500, true));
            assertEquals(OuterEndBoundaryRules.Poll.RETURN_TO_EDGE,
                    OuterEndBoundaryRules.poll(true, 520, 0, 500, false));
        }
    }
}
