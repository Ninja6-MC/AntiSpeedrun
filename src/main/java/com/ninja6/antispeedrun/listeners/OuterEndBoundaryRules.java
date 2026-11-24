package com.ninja6.antispeedrun.listeners;

import java.util.Locale;

import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * The decisions behind the outer-End boundary, kept apart from the server types they are made about
 * so they can be tested without a server.
 *
 * <p>Two rules share one boundary, {@code anti-cheese.outer-end-radius} around the origin, and both
 * sit under the {@code anti-cheese.enabled} master switch:
 *
 * <ul>
 *   <li>The world rule (#26, Task 7.2.1), {@code block-gateway-pre-dragon}, ships off. It holds
 *       everyone in until that End's first dragon has been killed.</li>
 *   <li>The personal rule (#278), {@code outer-end-requires-dragon-fight}, ships on. It holds each
 *       player in until they hold the {@code dragon-fight} credit, whoever killed this world's
 *       dragon. The credit is per player, not per world, so one earned in any End opens every End.</li>
 * </ul>
 */
public final class OuterEndBoundaryRules {

    /**
     * The block column a player is returned to when no position inside has been remembered: beside
     * the exit portal, clear of its rim and of the obsidian pillars. Both coordinates and the few
     * blocks around them that the ground search reads lie in chunk {@code (0, 0)}.
     */
    public static final int SAFE_COLUMN_X = 8;

    /** See {@link #SAFE_COLUMN_X}. */
    public static final int SAFE_COLUMN_Z = 8;

    /** Where the ground search in the safe column starts: about the main island's surface. */
    public static final int SAFE_SEARCH_START_Y = 64;

    private OuterEndBoundaryRules() {
    }

    /** Whether either rule is enforced at all under this configuration. */
    public static boolean armed(PluginConfig config) {
        return worldRuleArmed(config) || personalRuleArmed(config);
    }

    /** Whether the world rule, {@code block-gateway-pre-dragon}, is enforced. */
    public static boolean worldRuleArmed(PluginConfig config) {
        PluginConfig.AntiCheese rules = config.antiCheese();
        return rules.enabled() && rules.blockGatewayPreDragon();
    }

    /** Whether the personal rule, {@code outer-end-requires-dragon-fight}, is enforced. */
    public static boolean personalRuleArmed(PluginConfig config) {
        PluginConfig.AntiCheese rules = config.antiCheese();
        return rules.enabled() && rules.outerEndRequiresDragonFight();
    }

    /**
     * Which rule, if either, holds a player inside the boundary of one End.
     *
     * <p>An unread world holds nobody: sending a player back on a guess could strand one in a world
     * whose dragon died before this run. A world with no dragon battle has no fight to win there, so
     * neither rule holds anyone in it. The world rule is reported first because its refusal says
     * more: nobody can be let out until that world's dragon is dead.
     *
     * @param known     whether the world's battle has been read
     * @param killed    whether its first dragon has been killed
     * @param hasBattle whether it has a dragon battle at all
     * @param qualified whether the player holds the {@code dragon-fight} credit
     */
    public static Hold hold(PluginConfig config, boolean known, boolean killed, boolean hasBattle,
                            boolean qualified) {
        if (!known || !hasBattle) {
            return Hold.NONE;
        }
        if (worldRuleArmed(config) && !killed) {
            return Hold.WORLD;
        }
        if (personalRuleArmed(config) && !qualified) {
            return Hold.PERSONAL;
        }
        return Hold.NONE;
    }

    /** Which rule holds a player in. */
    public enum Hold {

        /** Neither: the player may go anywhere in this End. */
        NONE,

        /** The world rule: this End's first dragon is still alive. */
        WORLD,

        /** The personal rule: the player has not yet helped win a dragon fight. */
        PERSONAL;

        /** Whether the player is held at all. */
        public boolean held() {
            return this != NONE;
        }
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

    /** Whether a teleport to ({@code toX}, {@code toZ}) is refused: the player is held and it lands beyond the boundary. */
    public static boolean refuses(boolean held, double toX, double toZ, int radius) {
        return held && outside(toX, toZ, radius);
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
    public static Poll poll(boolean held, double x, double z, int radius, boolean haveLastInside) {
        if (!held) {
            return Poll.FORGET;
        }
        if (!outside(x, z, radius)) {
            return Poll.REMEMBER;
        }
        return haveLastInside ? Poll.RETURN_TO_LAST : Poll.RETURN_TO_DRAGON_AREA;
    }

    /** The poll's verdict on one player. */
    public enum Poll {

        /** Not held: nothing to track. */
        FORGET,

        /** Inside: remember this position as the place to return to. */
        REMEMBER,

        /** Outside, with a remembered inside position. */
        RETURN_TO_LAST,

        /**
         * Outside, with none remembered: a login out there, a credit revoked while out there, or a
         * rule switched on while out there. The point on the boundary is no answer, as it is usually
         * over the void, so the player goes to standing ground in the safe column beside the exit
         * portal ({@link #SAFE_COLUMN_X}, {@link #SAFE_COLUMN_Z}).
         */
        RETURN_TO_DRAGON_AREA
    }
}
