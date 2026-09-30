package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.ConfigLoadException;
import com.ninja6.antispeedrun.config.MapConfigSection;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.listeners.TemplateDuplicationRules.CrafterVerdict;
import com.ninja6.antispeedrun.progression.TrimProgressionManager;

/** The template duplication lock's decisions (#18). */
class TemplateDuplicationRulesTest {

    private static PluginConfig trims(Map<String, Object> section) throws ConfigLoadException {
        return PluginConfig.from(MapConfigSection.of(Map.of("trim-progression", section)));
    }

    @Test
    @DisplayName("the lock follows its own key and the section switch")
    void locked() throws ConfigLoadException {
        assertTrue(TemplateDuplicationRules.locked(trims(Map.of("enabled", true))));
        assertFalse(TemplateDuplicationRules.locked(trims(Map.of("enabled", false))));
        assertFalse(TemplateDuplicationRules.locked(trims(Map.of(
                "enabled", true, "block-unearned-template-duplication", false))));
    }

    @Test
    @DisplayName("only a UUID stamp names an owner")
    void owner() {
        UUID id = UUID.randomUUID();
        assertEquals(Optional.of(id), TemplateDuplicationRules.owner(id.toString()));
        assertTrue(TemplateDuplicationRules.owner(null).isEmpty());
        assertTrue(TemplateDuplicationRules.owner(" ").isEmpty());
        assertTrue(TemplateDuplicationRules.owner("Notch").isEmpty());
    }

    @Test
    @DisplayName("a Crafter works for an owner who explored, and refuses otherwise or with no owner")
    void crafter() {
        UUID explorer = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Set<String> record = Set.of(explorer + "/" + TrimProgressionManager.ANCIENT_CITY.id());
        BiPredicate<UUID, String> explored = (player, id) -> record.contains(player + "/" + id);

        assertEquals(CrafterVerdict.ALLOW, TemplateDuplicationRules.crafter(
                Optional.of(explorer), TrimProgressionManager.ANCIENT_CITY, explored));
        assertEquals(CrafterVerdict.UNEXPLORED, TemplateDuplicationRules.crafter(
                Optional.of(explorer), TrimProgressionManager.END_CITY, explored));
        assertEquals(CrafterVerdict.UNEXPLORED, TemplateDuplicationRules.crafter(
                Optional.of(stranger), TrimProgressionManager.ANCIENT_CITY, explored));
        assertEquals(CrafterVerdict.NO_OWNER, TemplateDuplicationRules.crafter(
                Optional.empty(), TrimProgressionManager.ANCIENT_CITY, explored));
    }

    @Test
    @DisplayName("the refusal names the template and the structure")
    void rejection() {
        String line = TemplateDuplicationRules.rejection(
                "Silence Armor Trim Smithing Template", "Ancient City");
        assertTrue(line.contains("Silence Armor Trim Smithing Template"));
        assertTrue(line.contains("Ancient City explored"));
        assertFalse(line.contains("{"));
    }
}
