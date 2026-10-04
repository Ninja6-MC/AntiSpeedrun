package com.ninja6.antispeedrun.listeners;

import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * Section 7's bed and Respawn Anchor boss damage block (#39, Task 7.1.1).
 *
 * <p>The Bukkit-free half of {@link AntiCheeseListener}. The listener recognises the hit by its
 * damage type, {@code BAD_RESPAWN_POINT}, which covers beds and Respawn Anchors and nothing else.
 */
public final class BedAnchorDamageRules {

    private BedAnchorDamageRules() {
    }

    /** Whether the block is enforced at all. Off by default. */
    public static boolean armed(PluginConfig config) {
        PluginConfig.AntiCheese a = config.antiCheese();
        return a.enabled() && a.blockBedAnchorBossDamage();
    }
}
