package com.ninja6.antispeedrun.gating;

import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * Thrown when one material is claimed by two tiers whose requirements are <em>incomparable</em>, so
 * no most-restrictive-wins rule can choose between them.
 *
 * <p>This is the named error audit finding R-11 asked for. The alternative it replaces is the
 * failure mode the finding actually objects to: a {@code Map<Material, ItemGateTier>} silently
 * keeping whichever tier happened to be written last, so a player's ability to pick up an item
 * depended on the iteration order of a hash map.
 *
 * <p>It is deliberately narrower than "two tiers matched the same material". Two tiers with the
 * same requirements, or one whose requirements strictly contain the other's, resolve without
 * ambiguity — see {@link ItemGateCompiler}. Only a genuine fork, where each tier demands something
 * the other does not, has no defensible answer. The operator resolves it by adding the material to
 * one tier's {@code exclude-materials}, which is what the message says.
 */
public class GateCollisionException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String materialName;
    private final transient PluginConfig.ItemTier first;
    private final transient PluginConfig.ItemTier second;

    GateCollisionException(String materialName, PluginConfig.ItemTier first,
                           PluginConfig.ItemTier second) {
        super(message(materialName, first, second));
        this.materialName = materialName;
        this.first = first;
        this.second = second;
    }

    /** The material both tiers claim. */
    public String materialName() {
        return materialName;
    }

    /** The tier that claimed the material first, in document order. */
    public PluginConfig.ItemTier first() {
        return first;
    }

    /** The tier that collided with it. */
    public PluginConfig.ItemTier second() {
        return second;
    }

    private static String message(String materialName, PluginConfig.ItemTier a,
                                  PluginConfig.ItemTier b) {
        return "item-progression.gated-items: " + materialName + " is claimed by both \""
                + a.id() + "\" " + requirements(a) + " and \"" + b.id() + "\" " + requirements(b)
                + ", and neither is more restrictive than the other, so there is no "
                + "most-restrictive-wins answer. Add \"" + materialName
                + "\" to the exclude-materials list of whichever tier should not own it.";
    }

    private static String requirements(PluginConfig.ItemTier tier) {
        return "(advancements=" + tier.requireAdvancements()
                + ", playtime-hours=" + tier.requirePlaytimeHours()
                + ", account-age-days=" + tier.requireAccountAgeDays() + ")";
    }
}
