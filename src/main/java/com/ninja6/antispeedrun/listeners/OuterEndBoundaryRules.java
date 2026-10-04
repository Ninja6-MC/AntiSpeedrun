package com.ninja6.antispeedrun.listeners;

import java.util.Locale;

import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * The decisions behind the outer-End boundary (#26, Task 7.2.1), kept apart from the server types
 * they are made about so they can be tested without a server.
 *
 * <p>The rule is keyed on {@code anti-cheese.block-gateway-pre-dragon}, which ships off, and on the
 * {@code anti-cheese.enabled} master switch above it.
 */
public final class OuterEndBoundaryRules {

    private OuterEndBoundaryRules() {
    }

    /** Whether the boundary is enforced at all under this configuration. */
    public static boolean armed(PluginConfig config) {
        PluginConfig.AntiCheese rules = config.antiCheese();
        return rules.enabled() && rules.blockGatewayPreDragon();
    }

    /**
     * Whether a point is beyond the boundary. The radius is measured horizontally from the world
     * origin, so height is not part of it, and a point exactly on the radius is still inside.
     */
    public static boolean outside(double x, double z, int radius) {
        double r = radius;
        return x * x + z * z > r * r;
    }

    /**
     * Whether a player is held by the boundary: not waived, and in survival or adventure. Creative
     * and spectator players are staff tools, not a way past the fight.
     */
    public static boolean applies(String gameModeName, boolean waived) {
        if (waived) {
            return false;
        }
        String mode = gameModeName == null ? "" : gameModeName.toUpperCase(Locale.ROOT);
        return !mode.equals("CREATIVE") && !mode.equals("SPECTATOR");
    }

    /** Whether a teleport to ({@code toX}, {@code toZ}) is refused: the boundary is up and it lands beyond it. */
    public static boolean refuses(boolean locked, double toX, double toZ, int radius) {
        return locked && outside(toX, toZ, radius);
    }

    /**
     * Whether a teleport cause is one a player makes for themselves, and so is refused at the event
     * rather than corrected by the poll. Commands, plugins and portals are left to the poll, which
     * catches whatever lands outside.
     */
    public static boolean selfInflicted(String causeName) {
        return "ENDER_PEARL".equals(causeName) || "CHORUS_FRUIT".equals(causeName);
    }

    /** What the poll does for a player standing at ({@code x}, {@code z}). */
    public static Poll poll(boolean locked, double x, double z, int radius, boolean haveLastInside) {
        if (!locked) {
            return Poll.FORGET;
        }
        if (!outside(x, z, radius)) {
            return Poll.REMEMBER;
        }
        return haveLastInside ? Poll.RETURN_TO_LAST : Poll.RETURN_TO_EDGE;
    }

    /** The poll's verdict on one player. */
    public enum Poll {

        /** The boundary is down: nothing to track. */
        FORGET,

        /** Inside: remember this position as the place to return to. */
        REMEMBER,

        /** Outside, with a remembered inside position. */
        RETURN_TO_LAST,

        /** Outside, with none remembered: pull back along the line to the origin. */
        RETURN_TO_EDGE
    }

    /**
     * The point on the line from the origin through ({@code x}, {@code z}), just inside the radius.
     *
     * @return {@code {x, z}}
     */
    public static double[] edge(double x, double z, int radius) {
        double length = Math.sqrt(x * x + z * z);
        if (length == 0.0D) {
            return new double[] {0.0D, 0.0D};
        }
        double scale = Math.max(0.0D, radius - 2.0D) / length;
        return new double[] {x * scale, z * scale};
    }
}
