package com.ninja6.antispeedrun.listeners;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.ninja6.antispeedrun.config.MapConfigSection;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.storage.DimensionUnlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionIngressTest {
    @Test
    void pluginAndCommandCausesRespectTheOptOut() throws Exception {
        PluginConfig defaults = PluginConfig.defaults();
        assertTrue(DimensionGateRules.requiresVoucher(true, defaults));
        assertFalse(DimensionGateRules.requiresVoucher(false, defaults));
        PluginConfig optOut = PluginConfig.from(MapConfigSection.of(Map.of(
                "dimension-gates", Map.of("gate-plugin-teleports", false))));
        assertFalse(DimensionGateRules.requiresVoucher(true, optOut));
    }

    @Test
    void inspectionRetainsVoucherUntilArrivalAndDoesNotRefreshItsLifetime() {
        UUID world = UUID.randomUUID();
        DimensionGateRules.Decision note = new DimensionGateRules.Decision(world, 1000L);
        Map<DimensionUnlock, DimensionGateRules.Decision> notes = new HashMap<>();
        notes.put(DimensionUnlock.NETHER, note);
        assertTrue(DimensionGateRules.decisionCovers(notes.get(DimensionUnlock.NETHER), world, 1050L));
        assertSame(note, notes.get(DimensionUnlock.NETHER));
        assertFalse(DimensionGateRules.decisionCovers(note, UUID.randomUUID(), 1050L));
        assertFalse(DimensionGateRules.decisionCovers(note, world,
                1000L + DimensionGateRules.DECISION_WINDOW_MILLIS));
    }

    @Test
    void delayedReturnIsInvalidatedByLogoutNewSessionOrMovement() {
        Object arrival = new Object();
        assertTrue(DimensionGateRules.currentArrival(arrival, arrival, true, true));
        assertFalse(DimensionGateRules.currentArrival(arrival, null, true, true));
        assertFalse(DimensionGateRules.currentArrival(arrival, new Object(), true, true));
        assertFalse(DimensionGateRules.currentArrival(arrival, arrival, false, true));
        assertFalse(DimensionGateRules.currentArrival(arrival, arrival, true, false));
        assertFalse(DimensionGateRules.currentArrival(null, null, true, true));
    }
}
