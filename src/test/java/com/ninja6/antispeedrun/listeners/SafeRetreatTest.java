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
 * over {@code World#getBlockAt}, and the fixtures below implement it over a couple of integer
 * comparisons. Neither implementation knows about the other.
 */
class SafeRetreatTest {

    /** A world that is solid below {@code groundY} and open above it. */
    private static SafeRetreat.Terrain flatGround(int groundY) {
        return new SafeRetreat.Terrain() {
            @Override
            public boolean isPassable(int x, int y, int z) {
                return y >= groundY;
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                return y < groundY;
            }
        };
    }

    /** Nothing anywhere. The void, or a column of air with no floor. */
    private static final SafeRetreat.Terrain VOID = new SafeRetreat.Terrain() {
        @Override
        public boolean isPassable(int x, int y, int z) {
            return true;
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return false;
        }
    };

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
            // Ground at 64; standing at 66 is two above and nothing is below, so 64 is the answer
            // and not some further-away spot.
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
        @DisplayName("nothing solid anywhere means no landing, so the caller leaves them be")
        void theVoid() {
            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(VOID, 10, 64, 10, -64, 320));
        }

        /** A one-block gap is a suffocation, not a landing. A player is two blocks tall. */
        @Test
        @DisplayName("a one-block gap is rejected - there is no headroom")
        void needsTwoBlocksOfHeadroom() {
            SafeRetreat.Terrain oneBlockGap = new SafeRetreat.Terrain() {
                @Override
                public boolean isPassable(int x, int y, int z) {
                    return y == 64;
                }

                @Override
                public boolean isSolid(int x, int y, int z) {
                    return y != 64;
                }
            };
            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(oneBlockGap, 10, 64, 10, -64, 320));
        }

        @Test
        @DisplayName("the build limits are respected at both ends")
        void respectsBuildLimits() {
            // Ground exactly at the world floor: standing at minY would need a solid block below
            // it, which is outside the world.
            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(flatGround(-64), 10, -64, 10, -64, 320));
            // And nothing is placed with its head outside the roof.
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
}
