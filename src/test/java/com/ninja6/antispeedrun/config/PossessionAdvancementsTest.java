package com.ninja6.antispeedrun.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/** The two personal-credit toggles and the uncredited-gate warning (#215). */
class PossessionAdvancementsTest {

    private static final String UNCREDITED = "no personal credit answers it";

    private static PluginConfig parse(String document) throws ConfigLoadException {
        Object root = new Yaml().load(document);
        return PluginConfig.from(root == null ? MapConfigSection.EMPTY : MapConfigSection.of((Map<?, ?>) root));
    }

    private static PluginConfig resource(String path) throws ConfigLoadException, IOException {
        try (InputStream in = PossessionAdvancementsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " must be on the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return PluginConfig.from(MapConfigSection.of((Map<?, ?>) new Yaml().load(reader)));
            }
        }
    }

    private static List<String> uncredited(PluginConfig config) {
        return config.warnings().stream().filter(w -> w.contains(UNCREDITED)).toList();
    }

    @Test
    @DisplayName("both toggles default on, absent or empty")
    void defaultsOn() throws Exception {
        PluginConfig.ItemProgression items = PluginConfig.defaults().itemProgression();
        assertTrue(items.requirePersonalCredit());
        assertTrue(items.countStructureLoot());
    }

    @Test
    @DisplayName("both toggles read as written")
    void readsBothToggles() throws Exception {
        PluginConfig.ItemProgression items = parse("""
                item-progression:
                  require-personal-credit: false
                  count-structure-loot: false
                """).itemProgression();
        assertFalse(items.requirePersonalCredit());
        assertFalse(items.countStructureLoot());
    }

    @Test
    @DisplayName("a toggle of the wrong type keeps its default and says so")
    void wrongTypeFallsBack() throws Exception {
        PluginConfig config = parse("""
                item-progression:
                  require-personal-credit: "yes please"
                """);
        assertTrue(config.itemProgression().requirePersonalCredit());
        assertTrue(config.warnings().stream()
                .anyMatch(w -> w.startsWith("item-progression.require-personal-credit: expected a boolean")));
    }

    @Test
    @DisplayName("a gate on a possession-triggered advancement with no credit is reported, by path")
    void uncreditedGateWarns() throws Exception {
        PluginConfig config = parse("""
                dimension-gates:
                  nether:
                    require-advancements:
                      - "minecraft:story/obtain_armor"
                      - "minecraft:story/smelt_iron"
                item-progression:
                  gated-items:
                    netherite-tier:
                      items: ["NETHERITE_INGOT"]
                      require-advancements: ["minecraft:story/shiny_gear"]
                villager-progression:
                  gate-mending-trade: true
                  required-advancement: "minecraft:end/elytra"
                """);
        List<String> warnings = uncredited(config);
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith(
                "dimension-gates.nether.require-advancements: \"minecraft:story/obtain_armor\""));
        assertTrue(warnings.get(1).startsWith(
                "item-progression.gated-items.netherite-tier.require-advancements: "
                        + "\"minecraft:story/shiny_gear\""));
        assertTrue(warnings.get(2).startsWith(
                "villager-progression.required-advancement: \"minecraft:end/elytra\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"minecraft:story/mine_stone", "minecraft:story/smelt_iron",
            "minecraft:story/iron_tools", "minecraft:story/upgrade_tools",
            "minecraft:story/mine_diamond", "minecraft:nether/obtain_blaze_rod",
            "minecraft:story/enter_the_nether", "minecraft:nether/find_fortress"})
    @DisplayName("a credited or location-triggered advancement is not reported")
    void creditedOrNotPossessionIsQuiet(String key) throws Exception {
        PluginConfig config = parse("""
                dimension-gates:
                  nether:
                    require-advancements: ["%s"]
                """.formatted(key));
        assertEquals(List.of(), uncredited(config));
    }

    @Test
    @DisplayName("nothing is reported while require-personal-credit is off")
    void quietWhileToggleOff() throws Exception {
        PluginConfig config = parse("""
                dimension-gates:
                  nether:
                    require-advancements: ["minecraft:story/obtain_armor"]
                item-progression:
                  require-personal-credit: false
                """);
        assertEquals(List.of(), uncredited(config));
    }

    @Test
    @DisplayName("a gate that is switched off is not reported")
    void quietForDisabledGates() throws Exception {
        PluginConfig config = parse("""
                dimension-gates:
                  nether:
                    enabled: false
                    require-advancements: ["minecraft:story/obtain_armor"]
                item-progression:
                  enabled: false
                  gated-items:
                    netherite-tier:
                      items: ["NETHERITE_INGOT"]
                      require-advancements: ["minecraft:story/shiny_gear"]
                villager-progression:
                  gate-mending-trade: false
                  required-advancement: "minecraft:end/elytra"
                """);
        assertEquals(List.of(), uncredited(config));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/config.yml", "/profiles/casual.yml", "/profiles/smp_standard.yml",
            "/profiles/hardcore.yml"})
    @DisplayName("the shipped configuration and every profile gate on credited keys only")
    void shippedFilesAreCredited(String path) throws Exception {
        PluginConfig config = resource(path);
        assertTrue(config.itemProgression().requirePersonalCredit(), path);
        assertTrue(config.itemProgression().countStructureLoot(), path);
        assertEquals(List.of(), uncredited(config), path);
    }

    @Test
    @DisplayName("every credited advancement is possession-triggered")
    void creditedIsSubset() {
        assertTrue(PossessionAdvancements.POSSESSION_TRIGGERED.containsAll(PossessionAdvancements.CREDITED));
        assertFalse(PossessionAdvancements.uncredited("minecraft:story/mine_diamond"));
        assertTrue(PossessionAdvancements.uncredited("minecraft:story/lava_bucket"));
        assertFalse(PossessionAdvancements.uncredited("minecraft:story/enchant_item"));
    }
}
