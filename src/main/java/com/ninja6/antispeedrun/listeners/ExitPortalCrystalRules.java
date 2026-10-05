package com.ninja6.antispeedrun.listeners;

import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * Section 7's exit portal crystal block (#25, Task 7.1.3).
 *
 * <p>The Bukkit-free half of {@link AntiCheeseListener}. An End Crystal always lands on the block
 * above the one clicked, whichever face is clicked, so the rule is on the clicked block's column.
 * The exit portal is centred on (0, 0) of an End world. The crystals of the resummon ritual (#20)
 * stand on the portal's rim, at (+-3, 0) and (0, +-3), which this rule never matches.
 */
public final class ExitPortalCrystalRules {

    private ExitPortalCrystalRules() {
    }

    /** Whether the rule is enforced at all. Off by default. */
    public static boolean armed(PluginConfig config) {
        PluginConfig.AntiCheese a = config.antiCheese();
        return a.enabled() && a.blockExitPortalCrystalPlace();
    }

    /** Whether a crystal placed by clicking the block in this column would stand on the centre. */
    public static boolean isCentreColumn(int blockX, int blockZ) {
        return blockX == 0 && blockZ == 0;
    }
}
