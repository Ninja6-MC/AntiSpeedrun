package com.ninja6.antispeedrun.listeners;

import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where an ejected rider ends up, decided against a fabricated world.
 *
 * <p>{@link SafeRetreat.Terrain} is the seam that makes this possible: the listener implements it
 * over {@code World#getBlockAt}, and the fixtures below implement it over a literal column of
 * {@code char}. Neither implementation knows about the other.
 *
 * <p>The fake models each block as one of three characters and answers the three probe methods from
 * it <em>independently</em>, the way Bukkit does — in particular {@code L} (lava) is passable,
 * because {@code Block#isPassable()} is a collision question and lava has no collision box. An
 * earlier fake had no notion of a liquid at all, which is exactly why it did not catch a search
 * that would set a player down in one.
 */
class SafeRetreatTest {

    /**
     * A world described one column at a time. {@code #} solid, {@code .} air, {@code L} lava.
     *
     * @param column answers the character at a given y; every (x, z) column is the same
     */
    private interface Column {
        char at(int y);
    }

    private static SafeRetreat.Terrain terrain(Column column) {
        return new SafeRetreat.Terrain() {
            @Override
            public boolean isPassable(int x, int y, int z) {
                // Collision only, as Bukkit answers it. Lava is passable.
                return column.at(y) != '#';
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                return column.at(y) == '#';
            }

            @Override
            public boolean isHazard(int x, int y, int z) {
                return column.at(y) == 'L';
            }
        };
    }

    /** Solid below {@code groundY}, open above it. */
    private static SafeRetreat.Terrain flatGround(int groundY) {
        return terrain(y -> y < groundY ? '#' : '.');
    }

    /** Nothing anywhere. The void, or a column of air with no floor. */
    private static final SafeRetreat.Terrain VOID = terrain(y -> '.');

    @Nested
    @DisplayName("which way is backward")
    class Backwards {

        @Test
        @DisplayName("is the opposite of the heading, at the requested distance")
        void opposesTheHeading() {
            SafeRetreat.Offset offset = SafeRetreat.backwards(1.0D, 0.0D, 2.0D);
            assertEquals(-2.0D, offset.x(), 1.0E-9D);
            assertEquals(0.0D, offset.z(), 1.0E-9D);
        }

        @Test
        @DisplayName("normalises first, so a fast boat retreats no further than a slow one")
        void magnitudeIsIgnored() {
            SafeRetreat.Offset slow = SafeRetreat.backwards(0.01D, 0.01D, 2.0D);
            SafeRetreat.Offset fast = SafeRetreat.backwards(40.0D, 40.0D, 2.0D);
            assertEquals(slow.x(), fast.x(), 1.0E-9D);
            assertEquals(slow.z(), fast.z(), 1.0E-9D);
            assertEquals(2.0D, Math.hypot(fast.x(), fast.z()), 1.0E-9D);
        }

        @Test
        @DisplayName("a diagonal heading retreats diagonally, still two blocks")
        void diagonal() {
            SafeRetreat.Offset offset =
                    SafeRetreat.backwards(1.0D, 1.0D, SafeRetreat.EJECT_DISTANCE_BLOCKS);
            assertEquals(SafeRetreat.EJECT_DISTANCE_BLOCKS,
                    Math.hypot(offset.x(), offset.z()), 1.0E-9D);
            assertTrue(offset.x() < 0.0D && offset.z() < 0.0D);
        }

        @Test
        @DisplayName("a stationary vehicle has no backward, and none is invented")
        void noHeading() {
            assertTrue(SafeRetreat.backwards(0.0D, 0.0D, 2.0D).isZero());
            assertTrue(SafeRetreat.backwards(1.0E-9D, 0.0D, 2.0D).isZero());
        }

        @Test
        @DisplayName("a non-finite heading is refused rather than propagated into a teleport")
        void nonFiniteHeading() {
            assertTrue(SafeRetreat.backwards(Double.NaN, 0.0D, 2.0D).isZero());
            assertTrue(SafeRetreat.backwards(Double.POSITIVE_INFINITY, 0.0D, 2.0D).isZero());
        }

        @Test
        @DisplayName("a non-positive distance moves nobody")
        void noDistance() {
            assertTrue(SafeRetreat.backwards(1.0D, 0.0D, 0.0D).isZero());
            assertTrue(SafeRetreat.backwards(1.0D, 0.0D, -2.0D).isZero());
        }

        @Test
        @DisplayName("two blocks is what #35 asks for")
        void ejectDistance() {
            assertEquals(2.0D, SafeRetreat.EJECT_DISTANCE_BLOCKS);
        }
    }

    @Nested
    @DisplayName("where the ground is")
    class GroundSearch {

        @Test
        @DisplayName("standing on flat ground finds the spot you are already on")
        void alreadyStanding() {
            assertEquals(OptionalInt.of(64),
                    SafeRetreat.groundY(flatGround(64), 10, 64, 10, -64, 320));
        }

        @Test
        @DisplayName("a rider above the ground is put down on it")
        void fallsToTheGround() {
            assertEquals(OptionalInt.of(64),
                    SafeRetreat.groundY(flatGround(64), 10, 68, 10, -64, 320));
        }

        @Test
        @DisplayName("a rider inside the ground is lifted out of it")
        void climbsOutOfTheGround() {
            assertEquals(OptionalInt.of(64),
                    SafeRetreat.groundY(flatGround(64), 10, 61, 10, -64, 320));
        }

        @Test
        @DisplayName("the nearest candidate wins, whichever direction it is in")
        void nearestWins() {
            assertEquals(OptionalInt.of(64),
                    SafeRetreat.groundY(flatGround(64), 0, 66, 0, -64, 320));
        }

        @Test
        @DisplayName("ground too far away is not reached for")
        void outOfRange() {
            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(flatGround(0), 10, 200, 10, -64, 320));
        }

        @Test
        @DisplayName("nothing solid anywhere means no landing, so the caller falls back")
        void theVoid() {
            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(VOID, 10, 64, 10, -64, 320));
        }

        /** A one-block gap is a suffocation, not a landing. A player is two blocks tall. */
        @Test
        @DisplayName("a one-block gap is rejected - there is no headroom")
        void needsTwoBlocksOfHeadroom() {
            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(terrain(y -> y == 64 ? '.' : '#'), 10, 64, 10, -64, 320));
        }

        @Test
        @DisplayName("the build limits are respected at both ends")
        void respectsBuildLimits() {
            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(flatGround(-64), 10, -64, 10, -64, 320));
            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(flatGround(319), 10, 319, 10, -64, 320));
        }

        @Test
        @DisplayName("the search radius is small enough not to relocate somebody across the map")
        void searchIsBounded() {
            assertTrue(SafeRetreat.SEARCH_RADIUS > 0 && SafeRetreat.SEARCH_RADIUS <= 16);
            assertEquals(OptionalInt.empty(), SafeRetreat.groundY(
                    flatGround(64), 10, 64 + SafeRetreat.SEARCH_RADIUS + 2, 10, -64, 320));
        }

        @Test
        @DisplayName("an offset that moves nothing is reported as such")
        void zeroOffset() {
            assertTrue(SafeRetreat.Offset.NONE.isZero());
            assertFalse(new SafeRetreat.Offset(2.0D, 0.0D).isZero());
        }
    }

    /**
     * The hole the review found: a lava lake two blocks behind a Nether portal has a solid floor
     * and, because lava has no collision box, satisfies every clause of a search written purely in
     * terms of "passable".
     */
    @Nested
    @DisplayName("hazards - a passable block is not the same as a survivable one")
    class Hazards {

        /** Stone floor at 63, then lava filling 64 and 65. Collision-passable, lethal. */
        private static final SafeRetreat.Terrain LAVA_LAKE =
                terrain(y -> y <= 63 ? '#' : (y <= 65 ? 'L' : '.'));

        @Test
        @DisplayName("stone under two blocks of lava is not somewhere to stand")
        void stoneUnderLava() {
            // 64 is the only y whose floor is solid and whose two body blocks are collision-free.
            // It is lava, so it is refused; 65 has lava for a floor and 66 has lava under its feet,
            // so nothing else in range qualifies either. The answer is determinate, not a range.
            assertEquals(OptionalInt.empty(), SafeRetreat.groundY(LAVA_LAKE, 10, 64, 10, -64, 320));
        }

        @Test
        @DisplayName("standing above a lava lake with no shore in reach finds nowhere at all")
        void nothingAboveTheLavaEither() {
            // From y=68 there is still nothing: 65 is lava (and no solid floor anyway), 66 has lava
            // under its feet, and above 66 the column is open air with no floor. Searching from
            // clear of the lava does not conjure a landing that searching from inside it lacked.
            assertEquals(OptionalInt.empty(), SafeRetreat.groundY(LAVA_LAKE, 10, 68, 10, -64, 320));
        }

        /**
         * The other half of the pair above: the lava is only disqualifying where it is. Cooled
         * lava is stone, and a stone shore over the same lake is an ordinary landing — otherwise
         * the two emptiness assertions would be equally satisfied by a search that refused
         * everything near a hazard.
         */
        @Test
        @DisplayName("a solid shore over the same lake is a landing like any other")
        void aboveTheLavaOnSolidGround() {
            // Lava at 64-65 as before, then a stone crust at 66 and open air above it.
            SafeRetreat.Terrain crustedLake = terrain(y -> {
                if (y <= 63 || y == 66) {
                    return '#';
                }
                return y <= 65 ? 'L' : '.';
            });
            assertEquals(OptionalInt.of(67),
                    SafeRetreat.groundY(crustedLake, 10, 68, 10, -64, 320));
        }

        @Test
        @DisplayName("a hazard as the floor is refused even when the body blocks are clear")
        void hazardFloor() {
            // A magma block: solid enough to walk on, and it hurts. Solid and hazardous at once is
            // the one combination the three-character grid cannot express, so it is built directly.
            SafeRetreat.Terrain hotFloor = new SafeRetreat.Terrain() {
                @Override
                public boolean isPassable(int x, int y, int z) {
                    return y > 63;
                }

                @Override
                public boolean isSolid(int x, int y, int z) {
                    return y <= 63;
                }

                @Override
                public boolean isHazard(int x, int y, int z) {
                    return y == 63;
                }
            };
            assertEquals(OptionalInt.empty(), SafeRetreat.groundY(hotFloor, 10, 64, 10, -64, 320));
        }

        /**
         * The clause the lake fixtures cannot reach on their own: two blocks of lava are caught by
         * the head-height check before the feet check is consulted, so removing
         * {@code !isHazard(x, y, z)} from {@code standable} left every hazard test green. A
         * one-block puddle is what separates them, and it is the commoner shape in the Nether.
         */
        @Test
        @DisplayName("a hazard at foot height is refused even with clear headroom")
        void hazardAtFootHeight() {
            SafeRetreat.Terrain lavaPuddle = terrain(y -> {
                if (y <= 63) {
                    return '#';
                }
                return y == 64 ? 'L' : '.';
            });
            // 64 has a solid floor and clear headroom at 65. Standing in it is still standing in
            // lava, and there is nothing else within the radius: 65 has a lava floor, and 66 and
            // up have no floor at all.
            assertEquals(OptionalInt.empty(), SafeRetreat.groundY(lavaPuddle, 10, 64, 10, -64, 320));
        }

        @Test
        @DisplayName("a hazard at head height is refused even with clear footing")
        void hazardAtHeadHeight() {
            // A single block of lava at head height. The grid has one hazard character and this is
            // it; fire, powder snow and a cactus are the same three answers to the probe, so
            // spelling them separately would only restate this case under another letter.
            SafeRetreat.Terrain lavaAbove = terrain(y -> {
                if (y <= 63) {
                    return '#';
                }
                return y == 65 ? 'L' : '.';
            });
            // Feet at 64 would put the player's head in the lava at 65.
            assertEquals(OptionalInt.empty(), SafeRetreat.groundY(lavaAbove, 10, 64, 10, -64, 320));
        }
    }

    /**
     * The composed answer, and the one the listener actually calls. Its contract is that it
     * <em>always</em> answers: see {@link SafeRetreat#landing}.
     */
    @Nested
    @DisplayName("the landing handed to the ejection")
    class LandingChoice {

        @Test
        @DisplayName("is the retreat spot when there is safe ground behind the portal")
        void retreatsWhenItCan() {
            SafeRetreat.Landing landing = SafeRetreat.landing(
                    100.5D, 64.0D, 200.5D, new SafeRetreat.Offset(-2.0D, 0.0D),
                    flatGround(64), -64, 320);

            assertTrue(landing.retreated());
            assertEquals(98.5D, landing.x(), 1.0E-9D);
            assertEquals(64.0D, landing.y(), 1.0E-9D);
            assertEquals(200.5D, landing.z(), 1.0E-9D);
        }

        /**
         * The property B1 turns on. With the transit going ahead, "no safe ground" must not become
         * "no teleport" — that is the same thing as carrying the rider through the gate.
         */
        @Test
        @DisplayName("falls back to the origin rather than to nothing when nowhere is safe")
        void neverAnswersNowhere() {
            SafeRetreat.Landing overTheVoid = SafeRetreat.landing(
                    100.5D, 64.0D, 200.5D, new SafeRetreat.Offset(-2.0D, 0.0D), VOID, -64, 320);

            assertFalse(overTheVoid.retreated(), "no retreat spot was found");
            assertEquals(100.5D, overTheVoid.x(), 1.0E-9D, "so the origin is handed back");
            assertEquals(64.0D, overTheVoid.y(), 1.0E-9D);
            assertEquals(200.5D, overTheVoid.z(), 1.0E-9D);
        }

        @Test
        @DisplayName("falls back to the origin when there was no heading to retreat along")
        void noHeading() {
            SafeRetreat.Landing landing = SafeRetreat.landing(
                    100.5D, 64.0D, 200.5D, SafeRetreat.Offset.NONE, flatGround(64), -64, 320);

            assertFalse(landing.retreated());
            assertEquals(100.5D, landing.x(), 1.0E-9D);
            assertEquals(200.5D, landing.z(), 1.0E-9D);
        }

        @Test
        @DisplayName("will not retreat into lava; it returns the rider to where they were instead")
        void refusesALavaLanding() {
            SafeRetreat.Terrain lavaBehind = terrain(y -> y <= 63 ? '#' : (y <= 65 ? 'L' : '.'));
            SafeRetreat.Landing landing = SafeRetreat.landing(
                    100.5D, 64.0D, 200.5D, new SafeRetreat.Offset(-2.0D, 0.0D),
                    lavaBehind, -64, 320);

            assertFalse(landing.retreated(), "the lava column is not a landing");
            assertEquals(100.5D, landing.x(), 1.0E-9D);
            assertEquals(200.5D, landing.z(), 1.0E-9D);
        }
    }
}
