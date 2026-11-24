package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * The outer-End boundary's decisions: the world rule (#26, Task 7.2.1) and the personal rule
 * (#278). {@link OuterEndBoundaryListener} needs a server, and stays untested for the reason
 * {@link ItemGateRulesTest} records; the scripted-player probes cover it on real servers.
 */
class OuterEndBoundaryRulesTest {

    private static PluginConfig with(boolean enabled, boolean gateway) {
        return with(enabled, gateway, false);
    }

    private static PluginConfig with(boolean enabled, boolean gateway, boolean personal) {
        PluginConfig base = PluginConfig.defaults();
        PluginConfig.AntiCheese a = base.antiCheese();
        return new PluginConfig(base.profile(), base.dimensionGates(), base.itemProgression(),
                base.trimProgression(), base.idleReminder(), base.progressCard(),
                base.journeyBook(), base.bossScaling(),
                new PluginConfig.AntiCheese(enabled, a.blockBedAnchorBossDamage(),
                        a.capSingleHitBossDamage(), a.maxSingleHitBossDamage(), a.blockEarlyEyeThrowing(),
                        a.earlyEyeRejectionMessage(), a.blockExitPortalCrystalPlace(), gateway,
                        personal, a.outerEndRadius(), a.outerEndPollSeconds()),
                base.villagerProgression(), List.of());
    }

    @Nested
    @DisplayName("the off switches")
    class Armed {

        @Test
        @DisplayName("the shipped configuration leaves the world rule off and the personal rule on")
        void defaults() {
            PluginConfig shipped = PluginConfig.defaults();
            assertFalse(shipped.antiCheese().blockGatewayPreDragon());
            assertTrue(shipped.antiCheese().outerEndRequiresDragonFight());
            assertFalse(OuterEndBoundaryRules.worldRuleArmed(shipped));
            assertTrue(OuterEndBoundaryRules.personalRuleArmed(shipped));
            assertTrue(OuterEndBoundaryRules.armed(shipped));
        }

        @Test
        @DisplayName("the world rule needs both its key and the anti-cheese master switch")
        void worldNeedsBoth() {
            assertTrue(OuterEndBoundaryRules.worldRuleArmed(with(true, true)));
            assertFalse(OuterEndBoundaryRules.worldRuleArmed(with(true, false)));
            assertFalse(OuterEndBoundaryRules.worldRuleArmed(with(false, true)));
        }

        @Test
        @DisplayName("the personal rule needs both its key and the anti-cheese master switch")
        void personalNeedsBoth() {
            assertTrue(OuterEndBoundaryRules.personalRuleArmed(with(true, false, true)));
            assertFalse(OuterEndBoundaryRules.personalRuleArmed(with(true, true, false)));
            assertFalse(OuterEndBoundaryRules.personalRuleArmed(with(false, false, true)));
        }

        @Test
        @DisplayName("the boundary is armed by either rule and by neither when both are off")
        void either() {
            assertTrue(OuterEndBoundaryRules.armed(with(true, true, false)));
            assertTrue(OuterEndBoundaryRules.armed(with(true, false, true)));
            assertFalse(OuterEndBoundaryRules.armed(with(true, false, false)));
            assertFalse(OuterEndBoundaryRules.armed(with(false, true, true)));
        }
    }

    @Nested
    @DisplayName("who is held, by which rule")
    class Holds {

        private final PluginConfig both = with(true, true, true);
        private final PluginConfig personalOnly = with(true, false, true);
        private final PluginConfig worldOnly = with(true, true, false);

        @Test
        @DisplayName("an unread world or one with no battle holds nobody")
        void unknownOrNoBattle() {
            assertEquals(OuterEndBoundaryRules.Hold.NONE,
                    OuterEndBoundaryRules.hold(both, false, false, true, false));
            assertEquals(OuterEndBoundaryRules.Hold.NONE,
                    OuterEndBoundaryRules.hold(both, true, true, false, false));
        }

        @Test
        @DisplayName("before the first kill the world rule holds everyone, qualified or not")
        void worldFirst() {
            assertEquals(OuterEndBoundaryRules.Hold.WORLD,
                    OuterEndBoundaryRules.hold(both, true, false, true, false));
            assertEquals(OuterEndBoundaryRules.Hold.WORLD,
                    OuterEndBoundaryRules.hold(worldOnly, true, false, true, true));
        }

        @Test
        @DisplayName("someone else's kill does not release an unqualified player")
        void anotherPlayersKill() {
            assertEquals(OuterEndBoundaryRules.Hold.PERSONAL,
                    OuterEndBoundaryRules.hold(both, true, true, true, false));
            assertEquals(OuterEndBoundaryRules.Hold.PERSONAL,
                    OuterEndBoundaryRules.hold(personalOnly, true, true, true, false));
        }

        @Test
        @DisplayName("the personal rule holds an unqualified player even before the first kill")
        void personalBeforeKill() {
            assertEquals(OuterEndBoundaryRules.Hold.PERSONAL,
                    OuterEndBoundaryRules.hold(personalOnly, true, false, true, false));
        }

        @Test
        @DisplayName("a qualified player is released once the world rule is satisfied or off")
        void qualified() {
            assertEquals(OuterEndBoundaryRules.Hold.NONE,
                    OuterEndBoundaryRules.hold(both, true, true, true, true));
            assertEquals(OuterEndBoundaryRules.Hold.NONE,
                    OuterEndBoundaryRules.hold(personalOnly, true, false, true, true));
        }

        @Test
        @DisplayName("turning the personal rule off leaves the world rule as configured")
        void independent() {
            assertEquals(OuterEndBoundaryRules.Hold.NONE,
                    OuterEndBoundaryRules.hold(worldOnly, true, true, true, false));
            assertEquals(OuterEndBoundaryRules.Hold.WORLD,
                    OuterEndBoundaryRules.hold(worldOnly, true, false, true, false));
            assertEquals(OuterEndBoundaryRules.Hold.NONE,
                    OuterEndBoundaryRules.hold(with(true, false, false), true, false, true, false));
        }

        @Test
        @DisplayName("the master switch turns both rules off")
        void master() {
            assertEquals(OuterEndBoundaryRules.Hold.NONE,
                    OuterEndBoundaryRules.hold(with(false, true, true), true, false, true, false));
        }

        @Test
        @DisplayName("only NONE lets a player out")
        void held() {
            assertFalse(OuterEndBoundaryRules.Hold.NONE.held());
            assertTrue(OuterEndBoundaryRules.Hold.WORLD.held());
            assertTrue(OuterEndBoundaryRules.Hold.PERSONAL.held());
        }
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
    }

    @Nested
    @DisplayName("who the rules apply to")
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
        @DisplayName("a landing beyond the radius is refused only while the player is held")
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
        @DisplayName("forgets everything once the player is not held")
        void unlocked() {
            assertEquals(OuterEndBoundaryRules.Poll.FORGET,
                    OuterEndBoundaryRules.poll(false, 900, 0, 500, true));
        }

        @Test
        @DisplayName("keeps only standing ground as the place to return to")
        void remembersStandingGround() {
            assertTrue(OuterEndBoundaryRules.remembers(true, false, false));
            assertFalse(OuterEndBoundaryRules.remembers(false, false, false));
            assertFalse(OuterEndBoundaryRules.remembers(true, true, false));
            assertFalse(OuterEndBoundaryRules.remembers(true, false, true));
        }

        @Test
        @DisplayName("remembers a position inside")
        void inside() {
            assertEquals(OuterEndBoundaryRules.Poll.REMEMBER,
                    OuterEndBoundaryRules.poll(true, 100, 100, 500, false));
        }

        @Test
        @DisplayName("sends a crosser back to the last inside position, or to the dragon area if none")
        void crossed() {
            assertEquals(OuterEndBoundaryRules.Poll.RETURN_TO_LAST,
                    OuterEndBoundaryRules.poll(true, 520, 0, 500, true));
            assertEquals(OuterEndBoundaryRules.Poll.RETURN_TO_DRAGON_AREA,
                    OuterEndBoundaryRules.poll(true, 520, 0, 500, false));
        }

        @Test
        @DisplayName("the safe column and the cells its search reads lie in chunk (0, 0), clear of the exit portal")
        void safeColumn() {
            int x = OuterEndBoundaryRules.SAFE_COLUMN_X;
            int z = OuterEndBoundaryRules.SAFE_COLUMN_Z;
            // The ground search looks three blocks round a candidate.
            assertEquals(0, (x - 3) >> 4);
            assertEquals(0, (x + 3) >> 4);
            assertEquals(0, (z - 3) >> 4);
            assertEquals(0, (z + 3) >> 4);
            // The exit portal and its rim lie within four blocks of the origin.
            assertTrue(Math.max(Math.abs(x), Math.abs(z)) - 3 > 4);
            // Inside the smallest radius any preset ships.
            assertFalse(OuterEndBoundaryRules.outside(x, z, 250));
            assertTrue(OuterEndBoundaryRules.outside(x, z, 11));
        }
    }
}
