package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Where to put a rider who has just been ejected at a portal mouth, worked out without touching a
 * {@code World}.
 *
 * <p>Three questions, all pure, all awkward to answer on a live server and all cheap to get wrong:
 *
 * <ul>
 *   <li><strong>Which way is "backward"?</strong> {@link #backwards} turns the direction the
 *       vehicle or the player was heading into a retreat offset of a configured length.</li>
 *   <li><strong>Where is the ground there?</strong> {@link #groundY} walks a column through a
 *       {@link Terrain} probe looking for a place a player actually fits and will not be hurt.</li>
 *   <li><strong>So where do they go?</strong> {@link #landing} composes the two and, crucially,
 *       <em>always answers</em> — falling back to the origin it was given rather than to nothing.
 *       See the note on {@link Landing} for why the difference is a gate rather than a nicety.</li>
 *   <li><strong>And a player sent back to a world's spawn?</strong> {@link #spawnLanding} walks the
 *       spawn column over the world's whole height, because a spawn point is level data rather than
 *       a place anyone has stood — the Nether's is routinely inside netherrack.</li>
 * </ul>
 *
 * <p>The {@link Terrain} seam is the whole point. The listener implements it over
 * {@code World#getBlockAt}; the tests implement it over a literal block of {@code char}. Neither
 * needs the other.
 *
 * <h2>The probe answers block facts, not the safety rule</h2>
 *
 * An earlier revision asked {@link Terrain} whether a block was "passable", let the listener answer
 * that with Bukkit's {@code Block#isPassable()}, and built the safety rule out of it. That shipped a
 * hole: {@code Block#isPassable()} is a <em>collision</em> question, and lava has no collision box,
 * so a column of stone under two blocks of lava satisfied every clause and an ejected rider was
 * teleported into it. The safety rule now lives here, in {@link #standable}, where a test can reach
 * it; {@link Terrain} answers three separate, individually obvious questions about a block and
 * composes none of them.
 */
public final class SafeRetreat {

    /** How far back an ejected rider is placed, per #35: two blocks. */
    public static final double EJECT_DISTANCE_BLOCKS = 2.0D;

    /**
     * How far up and down {@link #groundY} looks. Deliberately small: a retreat is a nudge out of a
     * portal, and a search that ranged over the whole world height would happily relocate a player
     * from a nether-roof portal to bedrock.
     */
    public static final int SEARCH_RADIUS = 6;

    /** A direction shorter than this is treated as no direction at all. */
    private static final double EPSILON = 1.0E-4D;

    private SafeRetreat() {
    }

    /**
     * A horizontal offset, in blocks.
     *
     * @param x east/west component
     * @param z north/south component
     */
    public record Offset(double x, double z) {

        /** No movement, for a direction that carried none. */
        public static final Offset NONE = new Offset(0.0D, 0.0D);

        /** Whether this offset would move anything. */
        public boolean isZero() {
            return Math.abs(x) < EPSILON && Math.abs(z) < EPSILON;
        }
    }

    /**
     * A position to put an ejected rider, in the coordinate frame of the world they started in.
     *
     * @param x block-centre x
     * @param y the block their feet occupy
     * @param z block-centre z
     * @param retreated whether the search found a standable spot — behind the portal for
     *                  {@link #landing}, in the spawn column for {@link #spawnLanding} — ({@code true})
     *                  or handed the starting point back unchanged because nothing was usable
     *                  ({@code false}). Only of interest for logging; both are legitimate answers
     */
    public record Landing(double x, double y, double z, boolean retreated) {
    }

    /**
     * The offset that moves {@code distance} blocks <em>against</em> the given heading.
     *
     * <p>The heading is normalised first, so it can be a velocity (any magnitude), a look vector
     * (unit), or the difference between two positions. The Y component is deliberately not a
     * parameter: a rider pushed into a portal should come out beside it, not above or below it, and
     * the vertical placement is {@link #groundY}'s job.
     *
     * @param headingX the east/west component of where the entity was going
     * @param headingZ the north/south component
     * @param distance how far back to go; a non-positive distance yields {@link Offset#NONE}
     * @return the retreat offset, or {@link Offset#NONE} when there is no usable heading — a boat
     *         sitting still in a portal has no "backward", and inventing one would push the rider
     *         somewhere arbitrary
     */
    public static Offset backwards(double headingX, double headingZ, double distance) {
        if (distance <= 0.0D) {
            return Offset.NONE;
        }
        double length = Math.sqrt(headingX * headingX + headingZ * headingZ);
        if (!Double.isFinite(length) || length < EPSILON) {
            return Offset.NONE;
        }
        return new Offset(-headingX / length * distance, -headingZ / length * distance);
    }

    /**
     * What the ejection code needs to know about the blocks around a candidate landing spot.
     *
     * <p>Coordinates are block coordinates. Each method answers one plain fact about one block and
     * composes nothing; {@link #standable} is where those facts become a rule. An implementation
     * over a real world must answer for whatever column it is asked about, and may only be asked
     * on the thread that owns those blocks. The listener honours that in two ways: an ejection
     * probes the source world on the event thread, before any transfer has resolved; a return to
     * spawn probes on the region scheduler for the spawn location itself.
     */
    public interface Terrain {

        /**
         * Whether a player's body can be inside this block — the collision question, and
         * <em>only</em> the collision question. Lava, water, fire and a cactus are all passable.
         * Whether they are survivable is {@link #isHazard}.
         */
        boolean isPassable(int x, int y, int z);

        /** Whether this block would hold a player up. Air does not; nor does a fluid. */
        boolean isSolid(int x, int y, int z);

        /**
         * Whether this block disqualifies the space it occupies as somewhere to put a rider.
         * Chiefly harm — lava and water (drowning), fire, and the handful of blocks that damage on
         * contact — but an implementation may also refuse a block that is merely a bad place to
         * arrive. The listener's does: it counts a portal block, on the reasoning that setting a
         * refused rider down inside the portal hands them the transit again.
         *
         * <p>Separate from {@link #isPassable} because the two disagree exactly where it matters:
         * every hazard worth naming here is passable, which is what made an earlier revision of
         * this class set players down in lava.
         */
        boolean isHazard(int x, int y, int z);
    }

    /**
     * The Y a player should stand at in column {@code (x, z)}, searched outward from {@code startY}.
     *
     * <p>"Stand at" means the block their feet occupy: passable and harmless at {@code y} and
     * {@code y + 1} — a player is two blocks tall, and a one-block hole is a suffocation, not a
     * landing — with something solid and harmless at {@code y - 1} to stand on, and at least one
     * full-height opening beside it so the player can walk away rather than being sealed in.
     *
     * <p>Downward is searched before upward, and the two are interleaved by distance so the nearest
     * candidate wins. Falling a short way onto the ground is what an ejected rider expects;
     * teleporting them upward onto the portal frame's lip is not, and neither is either one when a
     * closer spot existed in the other direction.
     *
     * @param terrain the probe
     * @param x       block x of the column
     * @param startY  the Y to search from, normally the ejected player's own feet
     * @param z       block z of the column
     * @param minY    the world's minimum build height, inclusive
     * @param maxY    the world's maximum build height, exclusive
     * @return the standing Y, or empty when nothing within {@link #SEARCH_RADIUS} fits. Callers
     *         should prefer {@link #landing}, which turns that emptiness into the origin rather
     *         than into a dropped ejection
     */
    public static OptionalInt groundY(Terrain terrain, int x, int startY, int z, int minY, int maxY) {
        return groundY(terrain, x, startY, z, minY, maxY, SEARCH_RADIUS);
    }

    private static OptionalInt groundY(Terrain terrain, int x, int startY, int z, int minY, int maxY,
                                       int radius) {
        Objects.requireNonNull(terrain, "terrain");
        for (int delta = 0; delta <= radius; delta++) {
            int below = startY - delta;
            if (below >= minY && below < maxY && standable(terrain, x, below, z, minY, maxY)) {
                return OptionalInt.of(below);
            }
            if (delta == 0) {
                continue;
            }
            int above = startY + delta;
            if (above >= minY && above < maxY && standable(terrain, x, above, z, minY, maxY)) {
                return OptionalInt.of(above);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Where to put a rider ejected at {@code (originX, originY, originZ)}, given a retreat offset.
     *
     * <p><strong>This never returns empty, and that is the point.</strong> When there is no usable
     * heading, or no safe ground behind the portal, the answer is the origin itself — the spot the
     * rider demonstrably occupied a moment ago, and therefore the one place known to be survivable
     * without asking the world another question.
     *
     * <p>An earlier revision returned nothing in those cases and the caller skipped the teleport.
     * That is harmless when the transit was cancelled and the rider has not moved, and it is a hole
     * when the transit went ahead without them being repositioned: "no safe ground behind the
     * portal" would silently become "carried into the dimension the gate just refused them". A gate
     * may decline to find somewhere nicer; it may not decline to act.
     *
     * <p>All coordinates are in the frame of the world the rider started in. Nothing here reads a
     * world, so the caller is free — and, per #35, obliged — to compute this <em>before</em> a
     * portal transfer resolves rather than after.
     *
     * @param originX  the rider's pre-transit x
     * @param originY  the rider's pre-transit y
     * @param originZ  the rider's pre-transit z
     * @param offset   the retreat offset from {@link #backwards}
     * @param terrain  the probe, over the world the rider started in
     * @param minY     that world's minimum build height, inclusive
     * @param maxY     that world's maximum build height, exclusive
     * @return where to put them; never {@code null}
     */
    public static Landing landing(double originX, double originY, double originZ, Offset offset,
                                  Terrain terrain, int minY, int maxY) {
        Objects.requireNonNull(offset, "offset");
        Objects.requireNonNull(terrain, "terrain");

        Landing origin = new Landing(originX, originY, originZ, false);
        if (offset.isZero()) {
            return origin;
        }
        int targetX = (int) Math.floor(originX + offset.x());
        int targetZ = (int) Math.floor(originZ + offset.z());
        OptionalInt ground =
                groundY(terrain, targetX, (int) Math.floor(originY), targetZ, minY, maxY);
        if (ground.isEmpty()) {
            return origin;
        }
        return new Landing(targetX + 0.5D, ground.getAsInt(), targetZ + 0.5D, true);
    }

    /**
     * Where to put a player returned to a world's spawn point.
     *
     * <p>The spawn is level data, not a place anyone has stood, and nothing guarantees a player fits
     * there. For the Overworld it is usually open ground; for the Nether it is routinely inside
     * netherrack, and a {@code NETHER -> THE_END} arrival the gate refuses is returned to exactly
     * that world. So the spawn column is searched, nearest first as in {@link #groundY}, but over
     * the <em>whole</em> of {@code [minY, maxY)} rather than {@link #SEARCH_RADIUS}: the point of a
     * short radius is not to relocate a player who was somewhere survivable, and a spawn inside rock
     * is not that.
     *
     * <p>Like {@link #landing} this always answers. When the whole column is unusable the spawn is
     * handed back unchanged — the behaviour before this search existed, and still better than
     * declining to return a player the gate refused.
     *
     * <p>A caller over a world with a ceiling should pass {@link #searchCeiling} as {@code maxY}
     * rather than the world's build height, or the nearest open space above a deep spawn may be the
     * Nether roof.
     *
     * <p>Searching a whole world height is also what makes the escape clause in {@code standable}
     * load-bearing: over a column of netherrack the first two-block gap is far likelier to be a
     * sealed ore pocket than a cave, and a player walled into one is worse off than at the buried
     * spawn this search exists to move them off.
     *
     * @param spawnX  the spawn's x
     * @param spawnY  the spawn's y
     * @param spawnZ  the spawn's z
     * @param terrain the probe, over the world the spawn belongs to
     * @param minY    that world's minimum build height, inclusive
     * @param maxY    the highest Y a player should be put at, exclusive
     * @return where to put them; never {@code null}
     */
    public static Landing spawnLanding(double spawnX, double spawnY, double spawnZ, Terrain terrain,
                                       int minY, int maxY) {
        Objects.requireNonNull(terrain, "terrain");
        int blockX = (int) Math.floor(spawnX);
        int blockZ = (int) Math.floor(spawnZ);
        int startY = Math.max(minY, Math.min(maxY - 1, (int) Math.floor(spawnY)));
        int radius = Math.max(startY - minY, maxY - 1 - startY);
        OptionalInt ground = groundY(terrain, blockX, startY, blockZ, minY, maxY, radius);
        if (ground.isEmpty()) {
            return new Landing(spawnX, spawnY, spawnZ, false);
        }
        return new Landing(blockX + 0.5D, ground.getAsInt(), blockZ + 0.5D, true);
    }

    /**
     * Whether a player put down with their feet at {@code (x, y, z)} would stand there, unharmed,
     * and be able to walk away from it.
     *
     * <p>The last clause is not decoration. A solid floor under two blocks of harmless air is
     * satisfied by a sealed pocket in the middle of rock, and {@link #spawnLanding} searches a whole
     * world height looking for one: the Nether's spawn column is solid netherrack, and the first
     * two-block gap anywhere in it is far likelier to be an ore pocket than a cave. Setting a player
     * the gate just refused down inside rock with no way out is worse than the buried spawn the
     * search was meant to rescue them from, because the spawn is at least where they expected to be.
     */
    /**
     * The {@code maxY} a spawn-column search should be given for a world, from the three heights the
     * world reports.
     *
     * <p>A world's logical height is the part of it a player belongs in, and in the Nether it is half
     * the build height: {@code minHeight 0}, {@code maxHeight 256}, {@code logicalHeight 128}. Above
     * that is the roof and the open air over it, which is reachable, standable, harmless and exactly
     * where nobody should be put — so {@link #spawnLanding} is given the logical ceiling rather than
     * the build height, and a spawn buried in netherrack resolves to a cave below the roof or to
     * nothing at all. In the Overworld the two coincide and this changes nothing.
     *
     * <p>{@code maxHeight} is still honoured, so a server that reports a logical height larger than
     * the world cannot push the search past the top of it.
     *
     * @param minHeight     the world's minimum build height, inclusive
     * @param maxHeight     the world's maximum build height, exclusive
     * @param logicalHeight the world's logical height
     * @return the exclusive ceiling to search to
     */
    public static int searchCeiling(int minHeight, int maxHeight, int logicalHeight) {
        return Math.min(maxHeight, minHeight + logicalHeight);
    }

    private static boolean standable(Terrain terrain, int x, int y, int z, int minY, int maxY) {
        if (y - 1 < minY || y + 1 >= maxY) {
            // No floor below the build limit, and no headroom above it. Both are outside the world
            // rather than merely unsuitable, so neither can become suitable by looking harder.
            return false;
        }
        return terrain.isSolid(x, y - 1, z)
                && !terrain.isHazard(x, y - 1, z)
                && roomToStand(terrain, x, y, z)
                && escapable(terrain, x, y, z);
    }

    /**
     * Whether a player's body fits at {@code (x, y, z)} and is not in something that hurts.
     *
     * <p>Both blocks, because a player is two blocks tall and a one-block gap is a suffocation
     * rather than a landing. Passable and harmless are asked separately of each: they disagree
     * exactly where it matters, which is what once put an ejected rider in lava.
     */
    private static boolean roomToStand(Terrain terrain, int x, int y, int z) {
        return terrain.isPassable(x, y, z)
                && !terrain.isHazard(x, y, z)
                && terrain.isPassable(x, y + 1, z)
                && !terrain.isHazard(x, y + 1, z);
    }

    /**
     * Whether a player standing at {@code (x, y, z)} could walk out of it.
     *
     * <p>The test is one full-height opening in any of the four horizontal directions: somewhere a
     * body fits and is not harmed, so it can be stepped into. A gap at foot height under a solid
     * block is not one — a player cannot walk through it — and a hazard on the far side is a way to
     * die rather than a way out.
     *
     * <p>Open space above the head does not count. A one-block shaft through forty blocks of stone
     * is a way out only for a player who happens to be carrying blocks to pillar with, and a return
     * through a gate makes no promise about their inventory.
     *
     * <p>Only the immediate neighbours are probed. This is deliberately a check that the spot is not
     * <em>sealed</em>, not a pathfind to the surface: a cave a long way from anywhere is still
     * somewhere a player can move, dig and light, and refusing it would send them back to a spawn
     * inside rock instead.
     */
    private static boolean escapable(Terrain terrain, int x, int y, int z) {
        return roomToStand(terrain, x + 1, y, z)
                || roomToStand(terrain, x - 1, y, z)
                || roomToStand(terrain, x, y, z + 1)
                || roomToStand(terrain, x, y, z - 1);
    }
}
