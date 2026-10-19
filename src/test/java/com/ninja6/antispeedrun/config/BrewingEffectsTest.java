package com.ninja6.antispeedrun.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
class BrewingEffectsTest {
    @Test void stableIdsAndSafetyAllowlist() {
        assertEquals("resistance", BrewingEffects.canonical("Turtle Master"));
        assertEquals("fire_resistance", BrewingEffects.canonical("minecraft:fire_resistance"));
        assertNull(BrewingEffects.canonical("instant_health"));
        assertNull(BrewingEffects.canonical("poison"));
        assertNull(BrewingEffects.canonical("unknown"));
    }
    @Test void configDefaultsOffAndRejectsHarmfulEffects() throws Exception {
        assertFalse(PluginConfig.from(MapConfigSection.of(Map.of())).potionProgression().enabled());
        var config = PluginConfig.from(MapConfigSection.of(Map.of("potion-progression", Map.of(
                "enabled", true, "gated-effects", List.of("turtle_master", "poison", "instant_health")))));
        assertTrue(config.potionProgression().enabled());
        assertEquals(List.of("resistance"), config.potionProgression().gatedEffects());
        assertEquals(2, config.warnings().stream().filter(warning -> warning.contains("gated-effects entry")).count());
    }
}
