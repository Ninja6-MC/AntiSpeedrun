package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.ConfigLoadException;
import com.ninja6.antispeedrun.config.MapConfigSection;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.listeners.NaturalTrimLootRules.Looter;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.TrimProgressionManager;

/** The natural loot lock's decisions (#198). */
class NaturalTrimLootRulesTest {

    private static PluginConfig trims(Map<String, Object> section) throws ConfigLoadException {
        return PluginConfig.from(MapConfigSection.of(Map.of("trim-progression", section)));
    }

    private static List<Material> loot(Material... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }

    @Test
    @DisplayName("the lock needs its own key and the section switch, and is off by default")
    void locked() throws ConfigLoadException {
        assertTrue(NaturalTrimLootRules.locked(trims(Map.of("enabled", true, "gate-natural-trim-chests", true))));
        assertFalse(NaturalTrimLootRules.locked(trims(Map.of("enabled", true))));
        assertFalse(NaturalTrimLootRules.locked(trims(Map.of("enabled", false, "gate-natural-trim-chests", true))));
        assertFalse(NaturalTrimLootRules.locked(trims(Map.of(
                "enabled", true, "gate-natural-trim-chests", false,
                "block-unearned-template-duplication", false,
                "block-unearned-smithing", false,
                "block-wearing-unearned-trims", false))));
    }

    @Test
    @DisplayName("every gated template is removed for a looter who earned nothing; the rest stays")
    void stripsUnearned() {
        List<Material> chest = loot(Material.DIAMOND,
                Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE,
                Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE,
                Material.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE,
                Material.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE,
                Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
                Material.COAST_ARMOR_TRIM_SMITHING_TEMPLATE,
                Material.GOLD_INGOT);

        assertEquals(5, NaturalTrimLootRules.strip(chest, Function.identity(), milestone -> false));
        assertEquals(List.of(Material.DIAMOND, Material.COAST_ARMOR_TRIM_SMITHING_TEMPLATE, Material.GOLD_INGOT),
                chest);
    }

    @Test
    @DisplayName("only the templates of unexplored structures are removed")
    void stripsByStructure() {
        List<Material> chest = loot(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
                Material.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE,
                Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE);
        Predicate<Milestone> bastionOnly = TrimProgressionManager.BASTION::equals;

        assertEquals(1, NaturalTrimLootRules.strip(chest, Function.identity(), bastionOnly));
        assertEquals(List.of(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
                Material.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE), chest);
    }

    @Test
    @DisplayName("each structure is asked once, and not at all for loot with no gated template")
    void asksOncePerStructure() {
        AtomicInteger asked = new AtomicInteger();
        Predicate<Milestone> counting = milestone -> {
            asked.incrementAndGet();
            return false;
        };

        NaturalTrimLootRules.strip(loot(Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE,
                Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE,
                Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE), Function.identity(), counting);
        assertEquals(1, asked.get());

        asked.set(0);
        assertEquals(0, NaturalTrimLootRules.strip(loot(Material.DIAMOND, Material.AIR),
                Function.identity(), counting));
        assertEquals(0, asked.get());
    }

    @Test
    @DisplayName("null entries are kept and not inspected")
    void keepsNulls() {
        List<Material> chest = loot(null, Material.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE);
        assertEquals(1, NaturalTrimLootRules.strip(chest, Function.identity(), milestone -> false));
        assertEquals(1, chest.size());
        assertEquals(null, chest.get(0));
    }

    @Test
    @DisplayName("a live looter is evaluated live, a recorded one from the record, and no looter earns nothing")
    void earnedByLooter() {
        UUID explorer = UUID.randomUUID();
        Set<String> record = Set.of(explorer + "/" + TrimProgressionManager.END_CITY.id());
        BiPredicate<UUID, String> explored = (player, id) -> record.contains(player + "/" + id);
        Predicate<Milestone> live = TrimProgressionManager.ANCIENT_CITY::equals;

        Predicate<Milestone> byLive = NaturalTrimLootRules.earned(Looter.LIVE, explorer, live, explored);
        assertTrue(byLive.test(TrimProgressionManager.ANCIENT_CITY));
        assertFalse(byLive.test(TrimProgressionManager.END_CITY));

        Predicate<Milestone> byRecord = NaturalTrimLootRules.earned(Looter.RECORDED, explorer, live, explored);
        assertTrue(byRecord.test(TrimProgressionManager.END_CITY));
        assertFalse(byRecord.test(TrimProgressionManager.ANCIENT_CITY));

        Predicate<Milestone> nobody = NaturalTrimLootRules.earned(Looter.NONE, null, milestone -> true,
                (player, id) -> true);
        for (Milestone structure : TrimProgressionManager.STRUCTURES) {
            assertFalse(nobody.test(structure));
        }
    }
}
