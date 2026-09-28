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

    /**
     * A world described per block rather than per column, for the cases {@link Column} cannot state.
     *
     * <p>Every fixture above answers from {@code y} alone, which makes each of them horizontally
     * uniform: a two-block gap in such a world is an infinite slab, and a player put down in it can
     * always walk out of it. Enclosure is the one shape that needs {@code x} and {@code z}.
     */
    private interface Block {
        char at(int x, int y, int z);
    }

    private static SafeRetreat.Terrain blocks(Block block) {
        return new SafeRetreat.Terrain() {
            @Override
            public boolean isPassable(int x, int y, int z) {
                return block.at(x, y, z) != '#';
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                return block.at(x, y, z) == '#';
            }

            @Override
            public boolean isHazard(int x, int y, int z) {
                return block.at(x, y, z) == 'L';
            }
        };
    }

    /**
     * Solid rock, with a single {@code 1 x 2 x 1} air pocket at {@code (px, py..py + 1, pz)}.
     *
     * <p>Floor below, headroom above, nothing harmful anywhere in it, and no way out: the shape an
     * ore pocket in the Nether has, and the shape a search written purely in terms of the column
     * accepts.
     */
    private static SafeRetreat.Terrain sealedPocket(int px, int py, int pz) {
        return blocks((x, y, z) ->
                (x == px && z == pz && (y == py || y == py + 1)) ? '.' : '#');
    }

    /**
     * Solid rock with a {@code 1 x 2} corridor running east from {@code (px, py, pz)} for
     * {@code length} blocks — a pocket with a genuine way out of it, which is what the assertions
     * about sealed ones need as a control.
     */
    private static SafeRetreat.Terrain corridorEast(int px, int py, int pz, int length) {
        return blocks((x, y, z) -> (z == pz && x >= px && x < px + length
                && (y == py || y == py + 1)) ? '.' : '#');
    }

    /**
     * Solid rock with a sealed room {@code width} blocks square and two high, its south-west corner
     * at {@code (px, py, pz)}. One block wide is the ore pocket; wider is the same trap, and the
     * shape a check that only asked about the four immediate neighbours accepted.
     */
    private static SafeRetreat.Terrain sealedRoom(int px, int py, int pz, int width) {
        return blocks((x, y, z) -> (x >= px && x < px + width && z >= pz && z < pz + width
                && (y == py || y == py + 1)) ? '.' : '#');
    }

    /**
     * {@code base}, with every block at {@code x >= fromX} one the probe will not answer for — a
     * chunk another Folia region owns, or one Paper would have to load to read. The search must treat
     * those as walls rather than reading them anyway.
     */
    private static SafeRetreat.Terrain unreadableEastOf(SafeRetreat.Terrain base, int fromX) {
        return new SafeRetreat.Terrain() {
            @Override
            public boolean isReadable(int x, int y, int z) {
                return x < fromX;
            }

            @Override
            public boolean isPassable(int x, int y, int z) {
                return readable(x, "isPassable") && base.isPassable(x, y, z);
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                return readable(x, "isSolid") && base.isSolid(x, y, z);
            }

            @Override
            public boolean isHazard(int x, int y, int z) {
                return readable(x, "isHazard") && base.isHazard(x, y, z);
            }

            private boolean readable(int x, String probe) {
                // Not a fussy assertion: reading a block the probe has refused is precisely the
                // threading defect the seam exists to prevent, and a fake that answered it anyway
                // would let the search regress in silence.
                assertTrue(x < fromX, probe + " read a block it was told it may not read, at x=" + x);
                return true;
            }
        };
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

    /**
     * A player the arrival backstop returns to the spawn of the world they came from — #114.
     *
     * <p>The worlds below are shaped like the case that motivated it: a Nether spawn buried in
     * netherrack, with the nearest cave well beyond {@link SafeRetreat#SEARCH_RADIUS}, and a
     * bedrock roof with open air above it that a search must not prefer.
     */
    @Nested
    @DisplayName("the landing for a return to spawn")
    class SpawnLanding {

        /** Solid everywhere except a two-block pocket at 30-31 and open air from the roof up. */
        private final SafeRetreat.Terrain buriedNetherSpawn =
                terrain(y -> (y == 30 || y == 31 || y >= 128) ? '.' : '#');

        @Test
        @DisplayName("an open spawn is used as it stands")
        void openSpawn() {
            SafeRetreat.Landing landing =
                    SafeRetreat.spawnLanding(0.0D, 64.0D, 0.0D, flatGround(64), -64, 320);

            assertTrue(landing.retreated());
            assertEquals(0.5D, landing.x(), 1.0E-9D);
            assertEquals(64.0D, landing.y(), 1.0E-9D);
            assertEquals(0.5D, landing.z(), 1.0E-9D);
        }

        @Test
        @DisplayName("a spawn inside rock is searched past the ejection radius to the nearest pocket")
        void buriedSpawnFindsThePocket() {
            SafeRetreat.Landing landing =
                    SafeRetreat.spawnLanding(8.0D, 70.0D, -3.0D, buriedNetherSpawn, 0, 128);

            assertTrue(landing.retreated(), "the pocket is 40 blocks down, well past SEARCH_RADIUS");
            assertEquals(30.0D, landing.y(), 1.0E-9D);
            assertEquals(8.5D, landing.x(), 1.0E-9D);
            assertEquals(-2.5D, landing.z(), 1.0E-9D);
        }

        @Test
        @DisplayName("capped at the logical height, the Nether roof is never the answer")
        void roofIsNotALanding() {
            SafeRetreat.Terrain sealed = terrain(y -> y >= 128 ? '.' : '#');

            SafeRetreat.Landing capped = SafeRetreat.spawnLanding(0.0D, 70.0D, 0.0D, sealed, 0, 128);
            assertFalse(capped.retreated(), "nothing standable below the roof");
            assertEquals(70.0D, capped.y(), 1.0E-9D, "so the spawn is handed back unchanged");

            SafeRetreat.Landing uncapped = SafeRetreat.spawnLanding(0.0D, 70.0D, 0.0D, sealed, 0, 256);
            assertEquals(128.0D, uncapped.y(), 1.0E-9D,
                    "which is why the caller passes the logical height rather than the build height");
        }

        @Test
        @DisplayName("never answers nowhere, even over the void")
        void neverAnswersNowhere() {
            SafeRetreat.Landing landing = SafeRetreat.spawnLanding(1.5D, 64.0D, 2.5D, VOID, -64, 320);

            assertFalse(landing.retreated());
            assertEquals(1.5D, landing.x(), 1.0E-9D);
            assertEquals(64.0D, landing.y(), 1.0E-9D);
            assertEquals(2.5D, landing.z(), 1.0E-9D);
        }

        @Test
        @DisplayName("a spawn Y outside the build range is clamped rather than searched from outside")
        void spawnOutsideTheWorldIsClamped() {
            SafeRetreat.Landing landing =
                    SafeRetreat.spawnLanding(0.0D, 400.0D, 0.0D, flatGround(64), -64, 320);

            assertTrue(landing.retreated());
            assertEquals(64.0D, landing.y(), 1.0E-9D);
        }

        /**
         * A buried Nether spawn resolved through {@link SafeRetreat#searchCeiling}, which is the only
         * combination the listener ever uses and the case the cap was written for. The cave is far
         * enough down that the roof is the <em>nearer</em> candidate, so the cap decides the answer
         * rather than merely agreeing with it.
         */
        @Test
        @DisplayName("a Nether spawn buried above a deep cave lands in the cave, not on the roof")
        void netherSpawnResolvesBelowTheRoof() {
            // Netherrack from 0 to 127 with a cave at 10-11, the roof at 127, open air above it.
            SafeRetreat.Terrain nether = terrain(y -> (y == 10 || y == 11 || y >= 128) ? '.' : '#');
            int minHeight = 0;
            int maxHeight = 256;
            int logicalHeight = 128;

            SafeRetreat.Landing landing = SafeRetreat.spawnLanding(0.0D, 70.0D, 0.0D, nether,
                    minHeight, SafeRetreat.searchCeiling(minHeight, maxHeight, logicalHeight));
            assertTrue(landing.retreated());
            assertEquals(10.0D, landing.y(), 1.0E-9D, "the cave, 60 blocks down");

            SafeRetreat.Landing uncapped =
                    SafeRetreat.spawnLanding(0.0D, 70.0D, 0.0D, nether, minHeight, maxHeight);
            assertEquals(128.0D, uncapped.y(), 1.0E-9D,
                    "without the cap the roof is 58 blocks up and wins on distance");
        }
    }

    /**
     * How high a spawn-column search is allowed to look, from the heights a world reports. Pure
     * arithmetic, extracted from the listener precisely so it can be stated here: the listener's own
     * copy needed a running server to reach.
     */
    @Nested
    @DisplayName("the ceiling a spawn search is given")
    class SearchCeiling {

        @Test
        @DisplayName("the Nether is capped at its logical height, half its build height")
        void nether() {
            assertEquals(128, SafeRetreat.searchCeiling(0, 256, 128));
        }

        @Test
        @DisplayName("the Overworld is not capped, because the two heights coincide")
        void overworld() {
            assertEquals(320, SafeRetreat.searchCeiling(-64, 320, 384));
        }

        @Test
        @DisplayName("the build height still wins, so no logical height can search past the world")
        void buildHeightIsTheUpperBound() {
            assertEquals(256, SafeRetreat.searchCeiling(0, 256, 4096));
        }

        @Test
        @DisplayName("the minimum height is the origin of the count, not zero")
        void countsFromTheWorldFloor() {
            // A world floor below zero is the Overworld's normal shape, and adding a logical height
            // to zero rather than to the floor would overshoot it by the depth of the negative part.
            assertEquals(64, SafeRetreat.searchCeiling(-64, 320, 128));
        }
    }

    /**
     * The hole the #123 review found. A solid floor under two blocks of harmless air is exactly what
     * an ore pocket in the middle of netherrack looks like, and {@link SafeRetreat#spawnLanding}
     * searches a whole world height looking for one.
     */
    @Nested
    @DisplayName("sealed pockets - standing room is not the same as a way out")
    class SealedPockets {

        @Test
        @DisplayName("a pocket walled in on all four sides is not a landing")
        void sealedPocketIsRefused() {
            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(sealedPocket(0, 30, 0), 0, 30, 0, 0, 128));
        }

        @Test
        @DisplayName("a spawn above a sealed pocket is handed back rather than walled into it")
        void spawnLandingRefusesIt() {
            SafeRetreat.Landing landing =
                    SafeRetreat.spawnLanding(0.0D, 70.0D, 0.0D, sealedPocket(0, 30, 0), 0, 128);

            assertFalse(landing.retreated(), "the only gap in the column is sealed");
            assertEquals(0.0D, landing.x(), 1.0E-9D, "so the spawn is handed back untouched");
            assertEquals(70.0D, landing.y(), 1.0E-9D);
            assertEquals(0.0D, landing.z(), 1.0E-9D);
        }

        /**
         * The positive control the assertions above need. Without it they are equally satisfied by a
         * rule that refuses everything a per-block fixture describes.
         *
         * <p>The way out has to be a real one. An earlier version of this test added a single block
         * east of the pocket and called it a doorway; that is a second sealed cell, and asserting it
         * was a landing certified the exact shape the change exists to refuse.
         */
        @Test
        @DisplayName("a pocket with a corridor leading out of it is an ordinary landing")
        void aWayOutIsEnough() {
            assertEquals(OptionalInt.of(30),
                    SafeRetreat.groundY(corridorEast(0, 30, 0, 12), 0, 30, 0, 0, 128));
        }

        /**
         * The case the #125 review found in the test above. Two blocks of standing room with rock on
         * every side is an ore pocket like the single block is, and a check that asked only whether a
         * body fitted in one of the four neighbours could not tell them apart.
         */
        @Test
        @DisplayName("a sealed pocket two blocks across is refused, not only a one-block one")
        void aWiderSealedPocketIsRefusedToo() {
            SafeRetreat.Terrain pairOfCells = blocks((x, y, z) ->
                    (z == 0 && (x == 0 || x == 1) && (y == 30 || y == 31)) ? '.' : '#');

            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(pairOfCells, 0, 30, 0, 0, 128));
        }

        @Test
        @DisplayName("a sealed room three blocks square is refused as well")
        void aSealedRoomIsRefused() {
            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(sealedRoom(-1, 30, -1, 3), 0, 30, 0, 0, 128));
        }

        /**
         * The far end of the bound, stated so that it is a decision rather than an accident. The walk
         * gives up once it has found more standing room than a pocket has, and the candidate is kept:
         * a space that large is somewhere a player can move, dig and light, and refusing it would
         * send them back to a spawn inside rock instead.
         */
        @Test
        @DisplayName("a space too large to be a pocket is kept once the walk spends its budget")
        void aLargeEnclosedSpaceIsKept() {
            assertEquals(OptionalInt.of(30),
                    SafeRetreat.groundY(sealedRoom(-3, 30, -3, 7), 0, 30, 0, 0, 128));
        }

        /**
         * The walk gets out of the candidate's own chunk within three blocks, and at a chunk border
         * that is a chunk the calling thread may not read. The probe says so; the search must not
         * quietly read it anyway, and must not accept the candidate on the strength of what is over
         * there.
         */
        @Test
        @DisplayName("a way out through blocks the probe will not answer for is not a way out")
        void anUnreadableNeighbourIsAWall() {
            assertEquals(OptionalInt.empty(), SafeRetreat.groundY(
                    unreadableEastOf(corridorEast(0, 30, 0, 12), 1), 0, 30, 0, 0, 128));
        }

        @Test
        @DisplayName("the same corridor is a way out as soon as the probe will answer for it")
        void aReadableNeighbourIsADoorway() {
            assertEquals(OptionalInt.of(30), SafeRetreat.groundY(
                    unreadableEastOf(corridorEast(0, 30, 0, 12), 40), 0, 30, 0, 0, 128));
        }

        /**
         * The asymmetry the #125 review asked about, settled rather than documented. Lava at body
         * height in the opening was already refused; lava under it was not, so a doorway a player
         * would step into and burn in counted as a way out.
         */
        @Test
        @DisplayName("a corridor floored with lava is a way to die, not a way out")
        void aLavaFlooredWayOutIsRefused() {
            SafeRetreat.Terrain lavaFloor = blocks((x, y, z) -> {
                if (z != 0 || x < 0 || x >= 12) {
                    return '#';
                }
                if (y == 30 || y == 31) {
                    return '.';
                }
                // The corridor's floor, everywhere except under the candidate itself.
                return (y == 29 && x > 0) ? 'L' : '#';
            });

            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(lavaFloor, 0, 30, 0, 0, 128));
        }

        @Test
        @DisplayName("a gap at foot height only is not a way out; a player cannot walk through it")
        void footHeightGapIsNotADoorway() {
            // As the doorway above, but the block east at head height is left solid.
            SafeRetreat.Terrain crawlspace = blocks((x, y, z) -> {
                if (z != 0 || y < 30 || y > 31) {
                    return '#';
                }
                if (x == 0) {
                    return '.';
                }
                return (x == 1 && y == 30) ? '.' : '#';
            });

            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(crawlspace, 0, 30, 0, 0, 128));
        }

        @Test
        @DisplayName("an opening filled with lava is a way to die, not a way out")
        void hazardousOpeningIsNotADoorway() {
            SafeRetreat.Terrain lavaDoorway = blocks((x, y, z) -> {
                if (z != 0 || y < 30 || y > 31) {
                    return '#';
                }
                if (x == 0) {
                    return '.';
                }
                return x == 1 ? 'L' : '#';
            });

            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(lavaDoorway, 0, 30, 0, 0, 128));
        }

        /**
         * A one-block shaft is a way out only for a player carrying blocks to pillar with, and a
         * return through a gate promises nothing about their inventory.
         */
        @Test
        @DisplayName("open air above the head does not on its own make a walled shaft escapable")
        void shaftUpwardsIsNotADoorway() {
            // The pocket, extended straight up to the top of the world. Walls on all four sides the
            // whole way, so there is standing room, headroom, and still nowhere to step.
            SafeRetreat.Terrain shaft =
                    blocks((x, y, z) -> (x == 0 && z == 0 && y >= 30) ? '.' : '#');

            assertEquals(OptionalInt.empty(),
                    SafeRetreat.groundY(shaft, 0, 30, 0, 0, 128));
        }

        @Test
        @DisplayName("a spawn over a pocket with a way out of it resolves onto that ground")
        void spawnLandingAcceptsAWayOut() {
            SafeRetreat.Landing landing = SafeRetreat.spawnLanding(
                    0.0D, 70.0D, 0.0D, corridorEast(0, 30, 0, 12), 0, 128);

            assertTrue(landing.retreated(), "the only gap in the column opens onto a corridor");
            assertEquals(30.0D, landing.y(), 1.0E-9D);
        }

        /**
         * The rule is shared with the ejection path on purpose. A retreat spot two blocks behind a
         * portal that is sealed is the same trap, and {@link SafeRetreat#landing} has somewhere
         * strictly better to fall back to: the spot the rider occupied a moment ago.
         */
        @Test
        @DisplayName("an ejection will not retreat into a sealed pocket either")
        void ejectionFallsBackToTheOrigin() {
            SafeRetreat.Landing landing = SafeRetreat.landing(
                    2.5D, 30.0D, 0.5D, new SafeRetreat.Offset(-2.0D, 0.0D),
                    sealedPocket(0, 30, 0), 0, 128);

            assertFalse(landing.retreated(), "the pocket behind the portal is sealed");
            assertEquals(2.5D, landing.x(), 1.0E-9D);
            assertEquals(30.0D, landing.y(), 1.0E-9D);
            assertEquals(0.5D, landing.z(), 1.0E-9D);
        }
    }
}
