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
import java.util.OptionalLong;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** When each player last helped win a dragon fight (#272). */
class RespawnCooldownStoreTest {

    private static final Logger LOGGER = Logger.getLogger(RespawnCooldownStoreTest.class.getName());

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
            return Optional.of("respawn-cooldowns.yml.corrupt-test");
        }
    }

    private static RespawnCooldownStore store(InMemoryStateFile file) {
        RespawnCooldownStore store = new RespawnCooldownStore(LOGGER, file, Runnable::run);
        store.loadNow();
        return store;
    }

    @Test
    @DisplayName("a victory survives a restart")
    void survivesRestart() {
        InMemoryStateFile file = new InMemoryStateFile();
        UUID player = UUID.randomUUID();
        assertTrue(store(file).stamp(player, 42L));
        RespawnCooldownStore after = store(file);
        assertEquals(OptionalLong.of(42L), after.lastVictory(player));
        assertTrue(after.lastVictory(UUID.randomUUID()).isEmpty());
    }

    @Test
    @DisplayName("a later victory replaces an earlier one; an earlier or equal one changes nothing")
    void laterWins() {
        InMemoryStateFile file = new InMemoryStateFile();
        RespawnCooldownStore store = store(file);
        UUID player = UUID.randomUUID();
        assertTrue(store.stamp(player, 100L));
        assertTrue(store.stamp(player, 200L));
        assertFalse(store.stamp(player, 150L));
        assertFalse(store.stamp(player, 200L));
        assertEquals(OptionalLong.of(200L), store.lastVictory(player));
        assertEquals(2, file.saves);
    }

    @Test
    @DisplayName("clear removes only that player's cooldown, persisted")
    void clear() {
        InMemoryStateFile file = new InMemoryStateFile();
        RespawnCooldownStore store = store(file);
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        store.stamp(player, 1L);
        store.stamp(other, 2L);
        assertTrue(store.clear(player));
        assertFalse(store.clear(player));
        assertTrue(store(file).lastVictory(player).isEmpty());
        assertEquals(OptionalLong.of(2L), store(file).lastVictory(other));
    }

    @Test
    @DisplayName("hand-edited keys and values that are not a UUID or a number are skipped")
    void skipsNoise() {
        UUID player = UUID.randomUUID();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put(RespawnCooldownStore.KEY_PREFIX + player, 7);
        document.put(RespawnCooldownStore.KEY_PREFIX + "not-a-uuid", 7L);
        document.put(RespawnCooldownStore.KEY_PREFIX + UUID.randomUUID(), "yesterday");
        document.put("something-else", 1L);
        assertEquals(Map.of(player, 7L), RespawnCooldownStore.fromDocument(document));
    }

    @Test
    @DisplayName("an unreadable file is kept until the first write moves it aside")
    void quarantinesBeforeWriting() {
        InMemoryStateFile file = new InMemoryStateFile();
        file.document = Map.of("damaged", true);
        file.failLoadWith = new IOException("truncated");
        RespawnCooldownStore store = new RespawnCooldownStore(LOGGER, file, Runnable::run);
        assertFalse(store.loadNow());
        assertTrue(store.isAwaitingQuarantine());
        UUID player = UUID.randomUUID();
        store.stamp(player, 3L);
        assertEquals(List.of(Map.of("damaged", true)), file.quarantined);
        assertEquals(Map.of(RespawnCooldownStore.KEY_PREFIX + player, 3L), file.document);
    }
}
