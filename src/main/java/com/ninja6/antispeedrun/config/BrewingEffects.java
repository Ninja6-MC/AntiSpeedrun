package com.ninja6.antispeedrun.config;

import java.util.Locale;
import java.util.Set;

/** Stable effect identifiers; healing and harmful effects cannot enter a configured gate. */
public final class BrewingEffects {
    private static final Set<String> ALLOWED = Set.of("fire_resistance", "strength", "speed",
            "invisibility", "resistance", "regeneration", "slow_falling", "water_breathing",
            "night_vision", "jump_boost");

    private BrewingEffects() {
    }

    public static String canonical(String value) {
        if (value == null) {
            return null;
        }
        String key = value.toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (key.startsWith("minecraft:")) {
            key = key.substring("minecraft:".length());
        }
        if (key.equals("turtle_master")) {
            key = "resistance";
        }
        return ALLOWED.contains(key) ? key : null;
    }
}
