package com.ninja6.antispeedrun.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class BrewedEffectStoreTest {
    private static class MemoryFile implements StateFile {
        Map<String, Object> document = Map.of();
        boolean broken, quarantined;
        int writes;
        public Map<String, Object> load() throws IOException {
            if (broken) throw new IOException("damaged");
            return document;
        }
        public void save(Map<String, Object> value) { document = Map.copyOf(value); writes++; }
        public Optional<String> quarantine() { quarantined = true; broken = false; return Optional.of("saved.corrupt"); }
    }
    private BrewedEffectStore store(MemoryFile file) {
        return new BrewedEffectStore(Logger.getAnonymousLogger(), new VersionedStateFile("brewed-effects.yml", file), Runnable::run);
    }
    @Test void restartPreservesOnlyLoaderAndRepeatedCreditDoesNotWrite() {
        var file = new MemoryFile();
        var store = store(file);
        UUID player = UUID.randomUUID();
        assertTrue(store.loadNow());
        assertTrue(store.record(player, "speed", true));
        int writes = file.writes;
        assertFalse(store.record(player, "speed", true));
        assertEquals(writes, file.writes);
        var restart = store(file);
        assertTrue(restart.loadNow());
        assertTrue(restart.hasBrewed(player, "speed"));
        assertFalse(restart.hasBrewed(UUID.randomUUID(), "speed"));
        assertTrue(restart.record(player, "speed", false));
        assertFalse(restart.hasBrewed(player, "speed"));
    }
    @Test void corruptionIsPreservedBeforeNewCreditIsWritten() {
        var file = new MemoryFile(); file.broken = true;
        var store = store(file);
        assertFalse(store.loadNow());
        assertEquals(0, file.writes);
        assertFalse(file.quarantined);
        store.record(UUID.randomUUID(), "strength", true);
        assertTrue(file.quarantined);
        assertEquals(1, file.writes);
        assertFalse(store.isAwaitingQuarantine());
    }
    @Test void unsafeAndNoncanonicalEffectWritesAreRejected() {
        var store = store(new MemoryFile());
        assertThrows(IllegalArgumentException.class, () -> store.record(UUID.randomUUID(), "poison", true));
        assertThrows(IllegalArgumentException.class, () -> store.record(UUID.randomUUID(), "turtle_master", true));
    }
}
