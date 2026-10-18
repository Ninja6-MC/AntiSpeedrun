package com.ninja6.antispeedrun.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.junit.jupiter.api.Assertions.*;

class ShulkerCollectionConfigTest {
    private PluginConfig parse(String text) throws ConfigLoadException {
        return PluginConfig.from(MapConfigSection.of((Map<?, ?>) new Yaml().load(text)));
    }
    @Test void defaultsOffAndMasterIsIndependentOfOtherPersonalCredits() throws Exception {
        assertFalse(PluginConfig.defaults().itemProgression().requireShulkerCollection());
        assertFalse(PluginConfig.defaults().itemProgression().gateShulkerBoxes());
        var config = parse("""
                item-progression:
                  require-personal-credit: false
                  require-shulker-collection: true
                  gate-shulker-boxes: true
                """);
        assertTrue(config.isClean(), config.warnings().toString());
        assertTrue(config.itemProgression().shulkerCollectionApplies(false));
        assertTrue(config.itemProgression().gateShulkerBoxes());
        assertTrue(CreditedActions.action("antispeedrun:credit/kill-shulker", config.itemProgression())
                .orElseThrow().contains("drops a shell"));
    }
    @Test void permissionScopeAlsoControlsThePresentationSnapshot() throws Exception {
        var items = parse("""
                item-progression:
                  require-personal-credit: false
                  require-shulker-collection: true
                  personal-credit-scope: PERMISSION
                """).itemProgression();
        assertFalse(items.shulkerCollectionApplies(false));
        assertTrue(items.shulkerCollectionApplies(true));
        assertFalse(items.forViewer(false).requireShulkerCollection());
        assertTrue(items.forViewer(true).requireShulkerCollection());
    }
}
