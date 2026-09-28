package com.ninja6.antispeedrun.listeners;

import java.util.List;
import java.util.Objects;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.MilestoneRequirement;

/**
 * Section 7's early Eye of Ender rule (#7, Task 3.2.1): no throw until the player has found a
 * Nether Fortress.
 *
 * <p>The Bukkit-free half of {@link EyeThrowListener}, split out for the reason every rules class
 * in this package is: the listener reads a {@code PlayerInteractEvent}, which cannot be built
 * without a server, and everything it decides is decided here.
 *
 * <h2>What counts as a throw</h2>
 *
 * Vanilla offers an eye to the clicked block first and throws it only if the block declines it.
 * An empty End Portal frame accepts it — the eye is set into the frame and nothing flies — while
 * every other block, a frame that already holds an eye included, declines it, and the eye is
 * thrown. So a right click in the air is always a throw, a right click on a block is a throw
 * unless that block is an empty frame, and a left click is never one. {@link #isThrow} is that
 * table. Setting eyes into a frame is left alone because #7 is about locating a stronghold, and a
 * player standing at an unfilled frame has already found one.
 */
public final class EyeThrowRules {

    private EyeThrowRules() {
    }

    /** What the player did with the hand holding the eye, reduced to what {@link #isThrow} needs. */
    public enum Click {

        /** A right click with nothing targeted. */
        RIGHT_AIR,

        /** A right click on a block. */
        RIGHT_BLOCK,

        /** A left click or a physical trigger. Never uses the item. */
        OTHER
    }

    /**
     * Whether the rule is switched on at all.
     *
     * <p>Asked after the material check and before anything else, so a server with the rule off pays
     * one enum comparison per interaction holding an eye and nothing more.
     */
    public static boolean armed(PluginConfig config) {
        return Milestone.earlyEyeThrowAdvancement(config).isPresent();
    }

    /**
     * Whether this interaction, if let through, would throw the eye rather than set it into a
     * frame.
     *
     * @param click            the kind of click
     * @param clickedEmptyFrame whether the clicked block is an End Portal frame with no eye in it.
     *                          Ignored unless {@code click} is {@link Click#RIGHT_BLOCK}
     */
    public static boolean isThrow(Click click, boolean clickedEmptyFrame) {
        Objects.requireNonNull(click, "click");
        return switch (click) {
            case RIGHT_AIR -> true;
            case RIGHT_BLOCK -> !clickedEmptyFrame;
            case OTHER -> false;
        };
    }

    /**
     * Whether the rule is waived for this player before their progression is looked at.
     *
     * <p>{@link DimensionGateRules#waived} with the End as its dimension, and the third waiver is the
     * reason this is not {@link ItemGateRules#waived}: an operator who has opened the End for
     * everyone with {@code /asr unlock} has decided the stronghold is fair game, and refusing the
     * eye that finds it would contradict them.
     *
     * @param hasBypassPermission {@code player.hasPermission(antispeedrun.bypass.anticheese)}
     * @param hasBypassGrant      {@code plugin.bypasses().hasBypass(player, now)}
     * @param endUnlocked         {@code plugin.dimensionUnlocks().isUnlocked(THE_END)}
     */
    public static boolean waived(boolean hasBypassPermission, boolean hasBypassGrant,
                                 boolean endUnlocked) {
        return hasBypassPermission || hasBypassGrant || endUnlocked;
    }

    /**
     * What a throw demands, in the shape {@code ProgressionManager} evaluates.
     *
     * <p>Built from {@link Milestone#earlyEyeThrowAdvancement}, the one place that decides whether
     * the key is required, so the capture and the listener agree. Empty — which evaluates as a
     * pass — when the rule is off.
     */
    public static MilestoneRequirement requirement(PluginConfig config) {
        return Milestone.earlyEyeThrowAdvancement(config)
                .map(key -> new MilestoneRequirement(List.of(key), 0.0D, 0))
                .orElseGet(MilestoneRequirement::none);
    }
}
