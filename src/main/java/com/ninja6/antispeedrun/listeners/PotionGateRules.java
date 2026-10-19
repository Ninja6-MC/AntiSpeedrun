package com.ninja6.antispeedrun.listeners;

import java.util.HashSet;
import java.util.Set;

/** Effect introduction ignores duration, amplifier and bottle form. */
final class PotionGateRules {
    private PotionGateRules() { }
    static boolean locked(String effect, java.util.Collection<String> configured,
                          java.util.function.Predicate<String> earned) {
        String canonical = com.ninja6.antispeedrun.config.BrewingEffects.canonical(effect);
        return canonical != null && configured.contains(canonical) && !earned.test(canonical);
    }
    static Set<String> introduced(Set<String> before, Set<String> after) {
        Set<String> introduced = new HashSet<>(after);
        introduced.removeAll(before);
        return Set.copyOf(introduced);
    }
}
