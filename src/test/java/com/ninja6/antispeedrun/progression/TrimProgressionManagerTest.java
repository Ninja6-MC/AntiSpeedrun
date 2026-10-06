package com.ninja6.antispeedrun.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.ConfigLoadException;
import com.ninja6.antispeedrun.config.MapConfigSection;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.storage.ExploredStructureStore;
import com.ninja6.antispeedrun.storage.StateFile;

/** Trim pattern and smithing template resolution to structure milestones (#36). */
class TrimProgressionManagerTest {

    private static final long NOW = 1_000_000_000_000L;

    private static final class InMemoryStateFile implements StateFile {
        @Override
        public Map<String, Object> load() {
            return Map.of();
        }

        @Override
        public void save(Map<String, Object> document) {
        }

        @Override
        public Optional<String> quarantine() {
            return Optional.empty();
        }
    }

    private static NamespacedKey pattern(String key) {
        return NamespacedKey.minecraft(key);
    }

    private static Player onlinePlayer(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isOnline" -> true;
                    case "getUniqueId" -> id;
                    case "getName" -> "Explorer";
                    case "getStatistic" -> 0;
                    case "getFirstPlayed" -> NOW;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "onlinePlayer(" + id + ")";
                    default -> throw new AssertionError("unexpected call: " + method.getName());
                });
    }

    private static PluginConfig trims(Map<String, Object> section) throws ConfigLoadException {
        return PluginConfig.from(MapConfigSection.of(Map.of("trim-progression", section)));
    }

    @Nested
    @DisplayName("trim patterns")
    class Patterns {

        @Test
        @DisplayName("Silence and Ward require the Ancient City")
        void ancientCity() {
            assertEquals(Optional.of(TrimProgressionManager.ANCIENT_CITY),
                    TrimProgressionManager.forPattern(pattern("silence")));
            assertEquals(Optional.of(TrimProgressionManager.ANCIENT_CITY),
                    TrimProgressionManager.forPattern(pattern("ward")));
        }

        @Test
        @DisplayName("Snout requires a Bastion and Spire an End City")
        void bastionAndEndCity() {
            assertEquals(Optional.of(TrimProgressionManager.BASTION),
                    TrimProgressionManager.forPattern(pattern("snout")));
            assertEquals(Optional.of(TrimProgressionManager.END_CITY),
                    TrimProgressionManager.forPattern(pattern("spire")));
        }

        @Test
        @DisplayName("every other vanilla pattern, and a datapack namesake, is ungated")
        void othersAreUngated() {
            for (String key : List.of("sentry", "dune", "coast", "wild", "eye", "vex", "tide", "rib",
                    "wayfinder", "shaper", "raiser", "host", "flow", "bolt")) {
                assertTrue(TrimProgressionManager.forPattern(pattern(key)).isEmpty(), key);
            }
            assertTrue(TrimProgressionManager.forPattern(new NamespacedKey("custom", "silence")).isEmpty());
        }

        @Test
        @DisplayName("the Bastion and End City require exactly their advancement; the Ancient City none")
        void requirements() {
            assertTrue(TrimProgressionManager.ANCIENT_CITY.requirement().isEmpty());
            assertTrue(TrimProgressionManager.provenByLocation(TrimProgressionManager.ANCIENT_CITY));
            assertFalse(TrimProgressionManager.provenByLocation(TrimProgressionManager.BASTION));
            assertFalse(TrimProgressionManager.provenByLocation(TrimProgressionManager.END_CITY));
            assertEquals(List.of("minecraft:nether/find_bastion"),
                    TrimProgressionManager.BASTION.requirement().advancements());
            assertEquals(List.of("minecraft:end/find_end_city"),
                    TrimProgressionManager.END_CITY.requirement().advancements());
            for (Milestone structure : TrimProgressionManager.STRUCTURES) {
                assertTrue(structure.id().startsWith(TrimProgressionManager.ID_PREFIX), structure.id());
                assertEquals(0.0D, structure.requirement().playtimeHours());
                assertEquals(0, structure.requirement().accountAgeDays());
            }
        }
    }

    @Nested
    @DisplayName("smithing templates")
    class Templates {

        @Test
        @DisplayName("a trim template resolves through the pattern it applies")
        void trimTemplates() {
            assertEquals(Optional.of(TrimProgressionManager.ANCIENT_CITY),
                    TrimProgressionManager.forTemplate(Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE));
            assertEquals(Optional.of(TrimProgressionManager.ANCIENT_CITY),
                    TrimProgressionManager.forTemplate(Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE));
            assertEquals(Optional.of(TrimProgressionManager.BASTION),
                    TrimProgressionManager.forTemplate(Material.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE));
            assertEquals(Optional.of(TrimProgressionManager.END_CITY),
                    TrimProgressionManager.forTemplate(Material.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE));
            assertTrue(TrimProgressionManager.forTemplate(Material.COAST_ARMOR_TRIM_SMITHING_TEMPLATE)
                    .isEmpty());
        }

        @Test
        @DisplayName("the netherite upgrade template is named explicitly and requires a Bastion")
        void netheriteUpgrade() {
            assertEquals(Optional.of(TrimProgressionManager.BASTION),
                    TrimProgressionManager.forTemplate(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        }

        @Test
        @DisplayName("materials that are not templates, and a bare suffix, are ungated")
        void nonTemplates() {
            assertTrue(TrimProgressionManager.forTemplate(Material.DIAMOND).isEmpty());
            assertTrue(TrimProgressionManager.forTemplate(Material.NETHERITE_INGOT).isEmpty());
            assertTrue(TrimProgressionManager.forTemplate("_ARMOR_TRIM_SMITHING_TEMPLATE").isEmpty());
        }

        @Test
        @DisplayName("exactly the four structure templates and the upgrade template are gated")
        void everyGatedTemplate() {
            Set<Material> gated = new HashSet<>();
            int templates = 0;
            for (Material material : Material.values()) {
                if (material.name().endsWith(TrimProgressionManager.TRIM_TEMPLATE_SUFFIX)) {
                    templates++;
                }
                if (TrimProgressionManager.forTemplate(material).isPresent()) {
                    gated.add(material);
                }
            }
            assertEquals(Set.of(Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE,
                    Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE,
                    Material.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE,
                    Material.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE,
                    Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE), gated);
            assertEquals(18, templates, "one template per vanilla pattern in 1.21.4");
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("the shipped defaults gate trims, so the capture queries both structure advancements")
        void defaultsAreCaptured() {
            PluginConfig config = PluginConfig.defaults();
            assertTrue(TrimProgressionManager.isActive(config));
            assertEquals(Set.of(TrimProgressionManager.BASTION_ADVANCEMENT,
                    TrimProgressionManager.END_CITY_ADVANCEMENT), TrimProgressionManager.requiredAdvancements(config));
            assertTrue(Milestone.allRequiredAdvancements(config).containsAll(List.of(
                    TrimProgressionManager.BASTION_ADVANCEMENT,
                    TrimProgressionManager.END_CITY_ADVANCEMENT)));
        }

        @Test
        @DisplayName("Sneak 100 is no longer captured for trims (#221)")
        void sneakIsNotCaptured() {
            assertFalse(TrimProgressionManager.requiredAdvancements(PluginConfig.defaults())
                    .contains("minecraft:adventure/avoid_vibration"));
        }

        @Test
        @DisplayName("nothing is captured with the section disabled")
        void disabled() throws ConfigLoadException {
            PluginConfig config = trims(Map.of("enabled", false));
            assertFalse(TrimProgressionManager.isActive(config));
            assertTrue(TrimProgressionManager.requiredAdvancements(config).isEmpty());
            assertFalse(Milestone.allRequiredAdvancements(config)
                    .contains(TrimProgressionManager.END_CITY_ADVANCEMENT));
        }

        @Test
        @DisplayName("nothing is captured with every lock off")
        void everyLockOff() throws ConfigLoadException {
            PluginConfig config = trims(Map.of(
                    "enabled", true,
                    "gate-natural-trim-chests", false,
                    "block-unearned-template-duplication", false,
                    "block-unearned-smithing", false,
                    "block-wearing-unearned-trims", false));
            assertFalse(TrimProgressionManager.isActive(config));
        }

        @Test
        @DisplayName("one lock on is enough to capture")
        void oneLockOn() throws ConfigLoadException {
            PluginConfig config = trims(Map.of(
                    "enabled", true,
                    "block-unearned-template-duplication", false,
                    "block-unearned-smithing", false,
                    "block-wearing-unearned-trims", true));
            assertTrue(TrimProgressionManager.isActive(config));
            assertEquals(2, TrimProgressionManager.requiredAdvancements(config).size());
        }
    }

    @Nested
    @DisplayName("evaluation")
    class Evaluation {

        private final Set<String> earned = new HashSet<>();
        private final List<String> lookedUp = new ArrayList<>();
        private final ExploredStructureStore explored =
                new ExploredStructureStore(Logger.getLogger("test"), new InMemoryStateFile(), Runnable::run);

        private TrimProgressionManager manager() {
            AdvancementLookup lookup = (player, key) -> {
                lookedUp.add(key);
                return earned.contains(key) ? AdvancementLookup.State.EARNED : AdvancementLookup.State.NOT_EARNED;
            };
            return new TrimProgressionManager(new ProgressionManager(Logger.getLogger("test"), lookup,
                    new PlayerStateRegistry(), Duration.ofMinutes(1), () -> NOW), explored);
        }

        private PluginConfig countLoot(boolean on) throws ConfigLoadException {
            return PluginConfig.from(MapConfigSection.of(Map.of(
                    "item-progression", Map.of("count-structure-loot", on))));
        }

        @Test
        @DisplayName("an unexplored structure refuses and names its advancement")
        void unexplored() {
            EligibilityResult result = manager().evaluate(onlinePlayer(UUID.randomUUID()),
                    PluginConfig.defaults(), Material.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE);
            assertFalse(result.eligible());
            assertEquals(List.of(TrimProgressionManager.BASTION_ADVANCEMENT), result.missingAdvancements());
        }

        @Test
        @DisplayName("Sneak 100 alone does not open the Ancient City, and no advancement is looked up (#221)")
        void sneakAloneRefuses() {
            earned.add("minecraft:adventure/avoid_vibration");
            EligibilityResult result = manager().evaluate(onlinePlayer(UUID.randomUUID()),
                    PluginConfig.defaults(), Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE);
            assertFalse(result.eligible());
            assertTrue(result.missingAdvancements().isEmpty());
            assertTrue(lookedUp.isEmpty());
        }

        @Test
        @DisplayName("an entered Ancient City opens Silence and Ward, live and from the record")
        void enteredCity() {
            UUID id = UUID.randomUUID();
            explored.record(id, TrimProgressionManager.ANCIENT_CITY.id(), true);
            TrimProgressionManager manager = manager();
            Player player = onlinePlayer(id);
            assertTrue(manager.evaluate(player, PluginConfig.defaults(),
                    Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE).eligible());
            assertTrue(manager.evaluate(player, PluginConfig.defaults(),
                    Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE).eligible());
            assertTrue(manager.recorded(id, PluginConfig.defaults(), TrimProgressionManager.ANCIENT_CITY.id()));
        }

        @Test
        @DisplayName("Ancient City loot counts only while count-structure-loot is on, and is kept either way")
        void cityLoot() throws ConfigLoadException {
            UUID id = UUID.randomUUID();
            explored.record(id, TrimProgressionManager.ANCIENT_CITY_LOOT_ID, true);
            TrimProgressionManager manager = manager();
            Player player = onlinePlayer(id);
            assertTrue(manager.evaluate(player, countLoot(true), TrimProgressionManager.ANCIENT_CITY).eligible());
            assertFalse(manager.evaluate(player, countLoot(false), TrimProgressionManager.ANCIENT_CITY).eligible());
            assertFalse(manager.recorded(id, countLoot(false), TrimProgressionManager.ANCIENT_CITY.id()));
            assertTrue(manager.recorded(id, countLoot(true), TrimProgressionManager.ANCIENT_CITY.id()));
            assertFalse(manager.recorded(id, countLoot(true), TrimProgressionManager.BASTION.id()),
                    "the loot record stands in for the Ancient City only");
        }

        @Test
        @DisplayName("evaluating an advancement structure records the answer; the Ancient City record is left alone")
        void recordsAdvancementStructures() {
            UUID id = UUID.randomUUID();
            earned.add(TrimProgressionManager.BASTION_ADVANCEMENT);
            TrimProgressionManager manager = manager();
            Player player = onlinePlayer(id);
            for (Milestone structure : TrimProgressionManager.STRUCTURES) {
                manager.evaluate(player, PluginConfig.defaults(), structure);
            }
            assertTrue(explored.hasExplored(id, TrimProgressionManager.BASTION.id()));
            assertFalse(explored.hasExplored(id, TrimProgressionManager.END_CITY.id()));
            assertFalse(explored.hasExplored(id, TrimProgressionManager.ANCIENT_CITY.id()));

            explored.record(id, TrimProgressionManager.ANCIENT_CITY.id(), true);
            for (Milestone structure : TrimProgressionManager.STRUCTURES) {
                manager.evaluate(player, PluginConfig.defaults(), structure);
            }
            assertTrue(explored.hasExplored(id, TrimProgressionManager.ANCIENT_CITY.id()));
        }

        @Test
        @DisplayName("an explored structure passes")
        void explored() {
            earned.add(TrimProgressionManager.BASTION_ADVANCEMENT);
            TrimProgressionManager manager = manager();
            Player player = onlinePlayer(UUID.randomUUID());
            assertTrue(manager.evaluate(player, PluginConfig.defaults(),
                    Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE).eligible());
            assertFalse(manager.evaluate(player, PluginConfig.defaults(),
                    Material.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE).eligible());
        }

        @Test
        @DisplayName("an ungated pattern or material passes without a lookup")
        void ungatedPasses() {
            TrimProgressionManager manager = manager();
            Player player = onlinePlayer(UUID.randomUUID());
            assertTrue(manager.evaluate(player, PluginConfig.defaults(),
                    Material.COAST_ARMOR_TRIM_SMITHING_TEMPLATE).eligible());
            assertTrue(manager.evaluate(player, PluginConfig.defaults(), Material.DIAMOND).eligible());
            assertTrue(lookedUp.isEmpty());
        }

        @Test
        @DisplayName("everything passes without a lookup while the section is off")
        void disabledPasses() throws ConfigLoadException {
            PluginConfig config = trims(Map.of("enabled", false));
            assertTrue(manager().evaluate(onlinePlayer(UUID.randomUUID()), config,
                    TrimProgressionManager.END_CITY).eligible());
            assertTrue(lookedUp.isEmpty());
        }
    }
}
