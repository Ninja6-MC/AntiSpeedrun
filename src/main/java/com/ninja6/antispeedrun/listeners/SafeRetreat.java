package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Where to put a rider who has just been ejected at a portal mouth, worked out without touching a
 * {@code World}.
 *
 * <p>Two questions, both pure, both awkward to answer on a live server and both cheap to get
 * wrong:
 *
 * <ul>
 *   <li><strong>Which way is "backward"?</strong> {@link #backwards} turns the direction the
 *       vehicle or the player was heading into a retreat offset of a configured length.</li>
 *   <li><strong>Where is the ground there?</strong> {@link #groundY} walks a column through a
 *       {@link Terrain} probe looking for a place a player actually fits, so an ejection does not
 *       drop somebody into the void, into lava-adjacent bedrock, or inside a wall.</li>
 * </ul>
 *
 * <p>The {@link Terrain} seam is the whole point. The listener implements it over
 * {@code World#getBlockAt}; the tests implement it over a literal block of {@code char}. Neither
 * needs the other.
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
     * <p>Coordinates are block coordinates. An implementation over a real world must answer for
     * whatever column it is asked about, including one outside the loaded area — the listener's
     * implementation is called from the vehicle's own region, so the chunks it asks about are the
     * ones it is already ticking.
     */
    public interface Terrain {

        /** Whether a player can occupy this block: air, or something equally non-obstructing. */
        boolean isPassable(int x, int y, int z);

        /** Whether this block would hold a player up. Fluids are not solid; neither is air. */
        boolean isSolid(int x, int y, int z);
    }

    /**
     * The Y a player should stand at in column {@code (x, z)}, searched outward from {@code startY}.
     *
     * <p>"Stand at" means the block their feet occupy: passable at {@code y} and {@code y + 1} — a
     * player is two blocks tall, and a one-block hole is a suffocation, not a landing — with
     * something solid at {@code y - 1} to stand on.
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
     * @return the standing Y, or empty when nothing within {@link #SEARCH_RADIUS} fits — in which
     *         case the caller must leave the player where they are rather than guess
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

    private static boolean standable(Terrain terrain, int x, int y, int z, int minY, int maxY) {
        if (y - 1 < minY || y + 1 >= maxY) {
            // No floor below the build limit, and no headroom above it. Both are outside the world
            // rather than merely unsuitable, so neither can become suitable by looking harder.
            return false;
        }
        return terrain.isSolid(x, y - 1, z)
                && terrain.isPassable(x, y, z)
                && terrain.isPassable(x, y + 1, z);
    }
}
