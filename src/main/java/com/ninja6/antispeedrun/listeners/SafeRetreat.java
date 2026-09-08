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
     * @param retreated whether this is a retreat spot behind the portal ({@code true}) or the
     *                  origin handed back unchanged because no retreat spot was usable
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
     * over a real world must answer for whatever column it is asked about — the listener's
     * implementation is called on the region that owns the source world's blocks, before any
     * transfer has resolved, so the chunks it asks about are ones that region is already ticking.
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
     * landing — with something solid and harmless at {@code y - 1} to stand on.
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
        Objects.requireNonNull(terrain, "terrain");
        for (int delta = 0; delta <= SEARCH_RADIUS; delta++) {
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

    private static boolean standable(Terrain terrain, int x, int y, int z, int minY, int maxY) {
        if (y - 1 < minY || y + 1 >= maxY) {
            // No floor below the build limit, and no headroom above it. Both are outside the world
            // rather than merely unsuitable, so neither can become suitable by looking harder.
            return false;
        }
        return terrain.isSolid(x, y - 1, z)
                && !terrain.isHazard(x, y - 1, z)
                && terrain.isPassable(x, y, z)
                && !terrain.isHazard(x, y, z)
                && terrain.isPassable(x, y + 1, z)
                && !terrain.isHazard(x, y + 1, z);
    }
}
