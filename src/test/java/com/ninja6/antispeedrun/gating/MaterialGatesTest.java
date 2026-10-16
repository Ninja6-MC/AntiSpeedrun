package com.ninja6.antispeedrun.gating;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.MapConfigSection;
import com.ninja6.antispeedrun.config.PluginConfig;

import static org.junit.jupiter.api.Assertions.*;

class MaterialGatesTest {
    private static Map<String, Object> tier(String advancement) {
        return Map.of("items", List.of("DIAMOND"),
                "require-advancements", List.of(advancement));
    }

    private static PluginConfig.ItemProgression disabled(Map<String, Object> tiers) throws Exception {
        return PluginConfig.from(MapConfigSection.of(Map.of("item-progression",
                Map.of("enabled", false, "gated-items", tiers)))).itemProgression();
    }

    @Test
    void disabledProgressionStillCompilesTheRealMaterialTable() throws Exception {
        var settings = disabled(Map.of("diamond-tier", tier("minecraft:story/mine_stone")));
        var warnings = new ArrayList<String>();
        var table = MaterialGates.compile(settings, warnings);
        assertFalse(settings.enabled());
        assertEquals(1, table.size());
        assertTrue(table.isGated(Material.DIAMOND));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void disabledProgressionRejectsTierCollisionsAtCompilation() throws Exception {
        var settings = disabled(Map.of(
                "stone", tier("minecraft:story/mine_stone"),
                "iron", tier("minecraft:story/smelt_iron")));
        assertThrows(GateCollisionException.class,
                () -> MaterialGates.compile(settings, new ArrayList<>()));
    }
}
