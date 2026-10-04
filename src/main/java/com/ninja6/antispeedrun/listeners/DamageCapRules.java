package com.ninja6.antispeedrun.listeners;

import java.util.function.DoubleUnaryOperator;

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
     * Leaves the final damage at or under {@code cap}, whatever shape the reductions have.
     *
     * <p>Tries the linear rescale first, then bisects the base: final damage never falls as the base
     * rises, and is zero at a base of zero, so a base that holds the cap always exists. If even
     * bisection cannot find one the base is set to zero.
     *
     * @param base    the event's current base damage
     * @param applier sets a base damage on the event and returns the resulting final damage
     * @param cap     the configured maximum final damage
     * @return the final damage after the last base set
     */
    public static double clampBase(double base, DoubleUnaryOperator applier, double cap) {
        double result = applier.applyAsDouble(base);
        if (!exceeds(result, cap)) {
            return result;
        }
        double scaled = scaledBase(base, result, cap);
        result = applier.applyAsDouble(scaled);
        if (!exceeds(result, cap)) {
            return result;
        }
        double low = 0.0D;
        double high = Math.min(base, scaled);
        for (int i = 0; i < 60; i++) {
            double mid = (low + high) / 2.0D;
            if (exceeds(applier.applyAsDouble(mid), cap)) {
                high = mid;
            } else {
                low = mid;
            }
        }
        result = applier.applyAsDouble(low);
        return exceeds(result, cap) ? applier.applyAsDouble(0.0D) : result;
    }

    /**
     * The base damage that brings a hit down to {@code cap} after reductions, assuming they scale
     * it linearly. Resistance does; armour and absorption do not, which {@link #clampBase} covers.
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
