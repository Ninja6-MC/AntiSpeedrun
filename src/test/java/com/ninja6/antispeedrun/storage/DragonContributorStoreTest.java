package com.ninja6.antispeedrun.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** The pending contributors of each End world's dragon fight (#278). */
class DragonContributorStoreTest {

    private static final Logger LOGGER = Logger.getLogger(DragonContributorStoreTest.class.getName());

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
            return Optional.of("dragon-contributors.yml.corrupt-test");
        }
    }

    private static DragonContributorStore store(InMemoryStateFile file) {
        DragonContributorStore store = new DragonContributorStore(LOGGER, file, Runnable::run);
        store.loadNow();
        return store;
    }

    @Test
    @DisplayName("contributors survive a restart, per world and in first-contribution order")
    void survivesRestart() {
        InMemoryStateFile file = new InMemoryStateFile();
        UUID end = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        DragonContributorStore before = store(file);
        assertTrue(before.add(end, second));
        assertTrue(before.add(end, first));
        assertTrue(before.add(other, first));

        DragonContributorStore after = store(file);
        assertEquals(List.of(second, first), List.copyOf(after.contributors(end)));
        assertEquals(Set.of(first), after.contributors(other));
        assertTrue(after.contributors(UUID.randomUUID()).isEmpty());
    }

    @Test
    @DisplayName("a repeat contribution writes nothing")
    void idempotent() {
        InMemoryStateFile file = new InMemoryStateFile();
        DragonContributorStore store = store(file);
        UUID end = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        assertTrue(store.add(end, player));
        assertFalse(store.add(end, player));
        assertEquals(1, file.saves);
    }

    @Test
    @DisplayName("take empties one world's fight, persisted, and leaves the others")
    void take() {
        InMemoryStateFile file = new InMemoryStateFile();
        DragonContributorStore store = store(file);
        UUID end = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        store.add(end, player);
        store.add(other, player);
        assertEquals(Set.of(player), store.take(end));
        assertTrue(store.contributors(end).isEmpty());
        assertEquals(Set.of(player), store.contributors(other));
        assertEquals(Map.of(DragonContributorStore.KEY_PREFIX + other, List.of(player.toString())),
                file.document);

        int saves = file.saves;
        assertTrue(store.take(end).isEmpty());
        assertEquals(saves, file.saves);
        assertTrue(store(file).contributors(end).isEmpty());
    }

    @Test
    @DisplayName("hand-edited keys and entries that are not UUIDs are skipped")
    void skipsNoise() {
        UUID end = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put(DragonContributorStore.KEY_PREFIX + end, List.of(player.toString(), "nobody", 7));
        document.put(DragonContributorStore.KEY_PREFIX + "not-a-uuid", List.of(player.toString()));
        document.put(DragonContributorStore.KEY_PREFIX + UUID.randomUUID(), player.toString());
        document.put(DragonContributorStore.KEY_PREFIX + UUID.randomUUID(), List.of("nobody"));
        document.put("something-else", List.of(player.toString()));
        assertEquals(Map.of(end, Set.of(player)), DragonContributorStore.fromDocument(document));
    }

    @Test
    @DisplayName("an unreadable file is kept until the first write moves it aside")
    void quarantinesBeforeWriting() {
        InMemoryStateFile file = new InMemoryStateFile();
        file.document = Map.of("damaged", true);
        file.failLoadWith = new IOException("truncated");
        DragonContributorStore store = new DragonContributorStore(LOGGER, file, Runnable::run);
        assertFalse(store.loadNow());
        assertTrue(store.isAwaitingQuarantine());
        assertEquals(0, file.saves);

        UUID end = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        store.add(end, player);
        assertEquals(List.of(Map.of("damaged", true)), file.quarantined);
        assertFalse(store.isAwaitingQuarantine());
        assertEquals(Map.of(DragonContributorStore.KEY_PREFIX + end, List.of(player.toString())),
                file.document);
    }
}
