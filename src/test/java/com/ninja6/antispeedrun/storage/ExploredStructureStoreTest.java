package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.listeners.TemplateDuplicationRules;
import com.ninja6.antispeedrun.listeners.TemplateDuplicationRules.CrafterVerdict;
import com.ninja6.antispeedrun.progression.TrimProgressionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The record of explored structures (#18) that lets a Crafter answer for an offline owner. */
class ExploredStructureStoreTest {

    private static final Logger LOGGER = Logger.getLogger(ExploredStructureStoreTest.class.getName());

    private static final String CITY = "trim:ancient_city";
    private static final String BASTION = "trim:bastion";

    private static final class InMemoryStateFile implements StateFile {

        private Map<String, Object> document = Map.of();
        private int saves;
        private IOException failLoadWith;
        private final List<Map<String, Object>> quarantined = new ArrayList<>();

        @Override
        public Map<String, Object> load() throws IOException {
            if (failLoadWith != null) {
                throw failLoadWith;
            }
            return document;
        }

        @Override
        public void save(Map<String, Object> document) {
            saves++;
            this.document = Map.copyOf(document);
        }

        @Override
        public Optional<String> quarantine() {
            quarantined.add(document);
            document = Map.of();
            failLoadWith = null;
            return Optional.of("explored-structures.yml.corrupt-test");
        }
    }

    private static ExploredStructureStore store(InMemoryStateFile file) {
        return new ExploredStructureStore(LOGGER, file, Runnable::run);
    }

    @Test
    @DisplayName("an explored structure survives a restart, so an offline owner's Crafter still works")
    void survivesRestart() {
        InMemoryStateFile file = new InMemoryStateFile();
        UUID owner = UUID.randomUUID();
        ExploredStructureStore before = store(file);
        assertTrue(before.loadNow());
        assertFalse(before.hasExplored(owner, CITY));
        assertTrue(before.record(owner, CITY, true));

        ExploredStructureStore after = store(file);
        assertTrue(after.loadNow());
        assertTrue(after.hasExplored(owner, CITY));
        assertFalse(after.hasExplored(owner, BASTION));
        assertFalse(after.hasExplored(UUID.randomUUID(), CITY));
    }

    @Test
    @DisplayName("an already placed Crafter becomes eligible when its online owner is primed")
    void existingCrafterAfterPriming() {
        InMemoryStateFile file = new InMemoryStateFile();
        UUID owner = UUID.randomUUID();
        String stamp = owner.toString();
        ExploredStructureStore store = store(file);
        store.loadNow();

        assertEquals(CrafterVerdict.UNEXPLORED, TemplateDuplicationRules.crafter(
                TemplateDuplicationRules.owner(stamp), TrimProgressionManager.ANCIENT_CITY,
                store::hasExplored));

        // The advancement may have been earned while trim progression was disabled. Priming the
        // already-online owner must update the record without requiring a new Crafter placement.
        store.record(owner, TrimProgressionManager.ANCIENT_CITY.id(), true);
        assertEquals(CrafterVerdict.ALLOW, TemplateDuplicationRules.crafter(
                TemplateDuplicationRules.owner(stamp), TrimProgressionManager.ANCIENT_CITY,
                store::hasExplored));

        ExploredStructureStore afterRestart = store(file);
        afterRestart.loadNow();
        assertEquals(CrafterVerdict.ALLOW, TemplateDuplicationRules.crafter(
                TemplateDuplicationRules.owner(stamp), TrimProgressionManager.ANCIENT_CITY,
                afterRestart::hasExplored));
    }

    @Test
    @DisplayName("recording what is already recorded writes nothing")
    void idempotent() {
        InMemoryStateFile file = new InMemoryStateFile();
        ExploredStructureStore store = store(file);
        store.loadNow();
        UUID owner = UUID.randomUUID();
        assertTrue(store.record(owner, CITY, true));
        assertFalse(store.record(owner, CITY, true));
        assertFalse(store.record(owner, BASTION, false));
        assertEquals(1, file.saves);
    }

    @Test
    @DisplayName("a revoked structure is cleared, and a player with nothing left has no key")
    void revocation() {
        InMemoryStateFile file = new InMemoryStateFile();
        ExploredStructureStore store = store(file);
        store.loadNow();
        UUID owner = UUID.randomUUID();
        store.record(owner, CITY, true);
        store.record(owner, BASTION, true);
        assertEquals(List.of(CITY, BASTION), file.document.get(ExploredStructureStore.KEY_PREFIX + owner));

        assertTrue(store.record(owner, CITY, false));
        assertFalse(store.hasExplored(owner, CITY));
        assertTrue(store.hasExplored(owner, BASTION));

        assertTrue(store.record(owner, BASTION, false));
        assertTrue(file.document.isEmpty());
    }

    @Test
    @DisplayName("hand-edited keys and values are skipped rather than failing the load")
    void skipsMalformed() {
        UUID owner = UUID.randomUUID();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put(ExploredStructureStore.KEY_PREFIX + owner, List.of(CITY, "", 7));
        document.put(ExploredStructureStore.KEY_PREFIX + "not-a-uuid", List.of(CITY));
        document.put(ExploredStructureStore.KEY_PREFIX + UUID.randomUUID(), BASTION);
        document.put("unrelated", List.of(CITY));

        assertEquals(Map.of(owner, Set.of(CITY)), ExploredStructureStore.fromDocument(document));
    }

    @Test
    @DisplayName("an unreadable file is kept until the first write moves it aside")
    void quarantinesDamage() {
        InMemoryStateFile file = new InMemoryStateFile();
        file.document = Map.of("explored-structures.x", List.of("y"));
        file.failLoadWith = new IOException("bad yaml");
        ExploredStructureStore store = store(file);

        assertFalse(store.loadNow());
        assertTrue(store.isAwaitingQuarantine());
        assertEquals(0, file.saves);

        UUID owner = UUID.randomUUID();
        store.record(owner, CITY, true);
        assertEquals(1, file.quarantined.size());
        assertFalse(store.isAwaitingQuarantine());
        assertEquals(List.of(CITY), file.document.get(ExploredStructureStore.KEY_PREFIX + owner));
    }
}
