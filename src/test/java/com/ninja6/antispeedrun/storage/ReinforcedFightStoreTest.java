package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The record of reinforced dragon fights (#56) that keeps a restart from spawning secondaries twice. */
class ReinforcedFightStoreTest {

    private static final Logger LOGGER = Logger.getLogger(ReinforcedFightStoreTest.class.getName());

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
            return Optional.of("dragon-fights.yml.corrupt-test");
        }
    }

    private static ReinforcedFightStore store(InMemoryStateFile file) {
        return new ReinforcedFightStore(LOGGER, file, Runnable::run);
    }

    @Test
    @DisplayName("a resolved window survives a restart")
    void survivesRestart() {
        InMemoryStateFile file = new InMemoryStateFile();
        UUID end = UUID.randomUUID();
        ReinforcedFightStore before = store(file);
        assertTrue(before.loadNow());
        assertFalse(before.isReinforced(end));
        assertTrue(before.markReinforced(end, 42L));

        ReinforcedFightStore after = store(file);
        assertTrue(after.loadNow());
        assertTrue(after.isReinforced(end));
        assertFalse(after.isReinforced(UUID.randomUUID()));
    }

    @Test
    @DisplayName("marking a world twice writes once")
    void idempotent() {
        InMemoryStateFile file = new InMemoryStateFile();
        ReinforcedFightStore store = store(file);
        store.loadNow();
        UUID end = UUID.randomUUID();
        assertTrue(store.markReinforced(end, 1L));
        assertFalse(store.markReinforced(end, 2L));
        assertEquals(1, file.saves);
        assertEquals(1L, file.document.get(ReinforcedFightStore.KEY_PREFIX + end));
    }

    @Test
    @DisplayName("hand-edited keys and values that are not a UUID or a number are skipped")
    void skipsNoise() {
        UUID end = UUID.randomUUID();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put(ReinforcedFightStore.KEY_PREFIX + end, 7);
        document.put(ReinforcedFightStore.KEY_PREFIX + "not-a-uuid", 7L);
        document.put(ReinforcedFightStore.KEY_PREFIX + UUID.randomUUID(), "yesterday");
        document.put("something-else", 1L);
        assertEquals(Map.of(end, 7L), ReinforcedFightStore.fromDocument(document));
    }

    @Test
    @DisplayName("an unreadable file is kept until the first write moves it aside")
    void quarantinesBeforeWriting() {
        InMemoryStateFile file = new InMemoryStateFile();
        file.document = Map.of("damaged", true);
        file.failLoadWith = new IOException("truncated");
        ReinforcedFightStore store = store(file);
        assertFalse(store.loadNow());
        assertTrue(store.isAwaitingQuarantine());
        assertEquals(0, file.saves);

        UUID end = UUID.randomUUID();
        store.markReinforced(end, 3L);
        assertEquals(List.of(Map.of("damaged", true)), file.quarantined);
        assertFalse(store.isAwaitingQuarantine());
        assertEquals(Map.of(ReinforcedFightStore.KEY_PREFIX + end, 3L), file.document);
    }
}
