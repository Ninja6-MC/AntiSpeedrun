package com.ninja6.antispeedrun.listeners;

import java.util.Objects;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.MilestoneRequirement;
import com.ninja6.antispeedrun.storage.DimensionUnlock;

/**
 * The Dragon's Breath bottling gate (#17), with no Bukkit type in the signature.
 *
 * <p>Bottling is not a pickup, a container click or a trade: a glass bottle used inside a cloud the
 * Ender Dragon left turns into {@code DRAGON_BREATH} straight in the player's inventory, so no
 * material gate ever sees it. It is gated behind The End rather than behind an item tier, because
 * the item is an End resource and the End gate is what already decides who belongs there. A player
 * the End gate would admit may bottle; one it would refuse may not, however they reached the cloud.
 *
 * <p>Armed exactly when {@code dimension-gates.the-end.enabled} is true. An operator who has opened
 * the End has no End requirement left to enforce here, and a separate switch would let the two
 * disagree.
 */
public final class DragonBreathRules {

    /**
     * How far past the player's bounding box a cloud is found, in blocks.
     *
     * <p>Matches the vanilla glass bottle, which searches the player's box inflated by two blocks.
     * Wider would refuse a bottle vanilla would have filled with water; narrower would miss a cloud
     * vanilla would have bottled.
     */
    public static final double REACH = 2.0;

    /**
     * The key {@link ItemProgressionListener} throttles this gate's feedback under.
     *
     * <p>Shares the item gate's cooldown map in the way {@link MendingTradeRules#FEEDBACK_KEY}
     * does, and carries a colon for the same reason: a tier id is an operator-written YAML key, and
     * a colon keeps this one out of that namespace.
     */
    public static final String FEEDBACK_KEY = "end:dragon-breath";

    private DragonBreathRules() {
    }

    /** Whether the gate applies at all: {@code dimension-gates.the-end.enabled}. */
    public static boolean armed(PluginConfig config) {
        Objects.requireNonNull(config, "config");
        return DimensionGateRules.gate(DimensionUnlock.THE_END, config).enabled();
    }

    /** What bottling demands: exactly what entering The End demands. */
    public static MilestoneRequirement requirement(PluginConfig config) {
        return DimensionGateRules.requirement(DimensionUnlock.THE_END, config);
    }

    /**
     * Whether the gate is waived before progression is consulted.
     *
     * <p>Any waiver that would let the player into the End, plus the item gate's own permission.
     * The first set because the requirement is End access; the second because this refuses an item
     * reaching a player's inventory, which is what {@code antispeedrun.bypass.items} describes, and
     * an operator who exempted a builder from the item gate should not find one item still refused.
     *
     * @param hasItemBypassPermission {@code antispeedrun.bypass.items}
     * @param hasGateBypassPermission {@code antispeedrun.bypass.gates}
     * @param hasBypassGrant          an unexpired {@code /asr bypass} grant
     * @param endUnlocked             The End opened server-wide with {@code /asr unlock}
     */
    public static boolean waived(boolean hasItemBypassPermission, boolean hasGateBypassPermission,
                                 boolean hasBypassGrant, boolean endUnlocked) {
        return hasItemBypassPermission
                || DimensionGateRules.waived(hasGateBypassPermission, hasBypassGrant, endUnlocked);
    }
}
