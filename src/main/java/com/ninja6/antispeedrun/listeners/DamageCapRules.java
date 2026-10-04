package com.ninja6.antispeedrun.listeners;

import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * Section 7's single-hit boss damage cap (#24, Task 7.1.2).
 *
 * <p>The Bukkit-free half of {@link AntiCheeseListener}: whether the rule is on, and the arithmetic
 * that turns a cap on final damage into a base damage to set.
 *
 * <p>{@code EntityDamageEvent#setDamage} sets the base value, after which armour, resistance and
 * absorption apply again. Setting the cap itself would therefore land below it whenever a
 * reduction applies, so the base is scaled by the ratio of cap to final damage instead.
 */
public final class DamageCapRules {

    /** Tolerance, in health points, below which a hit is treated as already at the cap. */
    static final double EPSILON = 1.0e-6D;

    private DamageCapRules() {
    }

    /** Whether the cap is enforced at all. Off by default. */
    public static boolean armed(PluginConfig config) {
        PluginConfig.AntiCheese a = config.antiCheese();
        return a.enabled() && a.capSingleHitBossDamage() && a.maxSingleHitBossDamage() > 0.0D;
    }

    /** Whether a hit of this final damage exceeds the cap. */
    public static boolean exceeds(double finalDamage, double cap) {
        return finalDamage > cap + EPSILON;
    }

    /**
     * The base damage that brings a hit down to {@code cap} after reductions, assuming they scale
     * it linearly, as armour and resistance do.
     *
     * @param base        the event's current base damage
     * @param finalDamage the event's current final damage, above {@code cap}
     * @param cap         the configured maximum final damage
     */
    public static double scaledBase(double base, double finalDamage, double cap) {
        if (finalDamage <= 0.0D) {
            return base;
        }
        return base * (cap / finalDamage);
    }
}
