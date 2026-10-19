package com.ninja6.antispeedrun.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ElytraCollectionConfigTest {
    private PluginConfig parse(String yaml) throws ConfigLoadException {
        return PluginConfig.from(MapConfigSection.of((Map<?, ?>) new Yaml().load(yaml)));
    }
    @Test void defaultsOffAndIndependentOfOtherCredits() throws Exception {
        assertFalse(PluginConfig.defaults().itemProgression().requireElytraCollection());
        PluginConfig.ItemProgression items = parse("""
                item-progression:
                  require-personal-credit: false
                  require-elytra-collection: true
                """).itemProgression();
        assertTrue(items.requireElytraCollection());
        assertFalse(items.requirePersonalCredit());
        assertTrue(items.elytraCollectionApplies(false));
        assertEquals("collect an Elytra yourself from an untouched natural End Ship frame",
                CreditedActions.action("minecraft:end/elytra", items).orElseThrow());
    }
    @Test void permissionScopeControlsEnforcementAndWording() throws Exception {
        PluginConfig.ItemProgression items = parse("""
                item-progression:
                  require-elytra-collection: true
                  personal-credit-scope: PERMISSION
                """).itemProgression();
        assertFalse(items.elytraCollectionApplies(false));
        assertTrue(items.elytraCollectionApplies(true));
        assertTrue(CreditedActions.action("minecraft:end/elytra", items.forViewer(false)).isEmpty());
        assertTrue(CreditedActions.action("minecraft:end/elytra", items.forViewer(true)).isPresent());
    }
    @Test void warningReflectsOptionalCredit() throws Exception {
        String document = """
                item-progression:
                  require-elytra-collection: %s
                  gated-items:
                    wings:
                      items: [ELYTRA]
                      require-advancements: ["minecraft:end/elytra"]
                """;
        assertTrue(parse(document.formatted("false")).warnings().stream()
                .anyMatch(w -> w.contains("no personal credit answers it")));
        assertFalse(parse(document.formatted("true")).warnings().stream()
                .anyMatch(w -> w.contains("no personal credit answers it")));
    }
    @Test void explicitElytraCreditHasActionWordingWhileOptionalMaterialGateIsOff() throws Exception {
        PluginConfig.ItemProgression items = PluginConfig.defaults().itemProgression();
        assertTrue(CreditedActions.action("antispeedrun:credit/elytra", items).isPresent());
    }

}
