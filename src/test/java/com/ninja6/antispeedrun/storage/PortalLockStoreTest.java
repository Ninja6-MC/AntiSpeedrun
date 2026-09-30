package com.ninja6.antispeedrun.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

class PortalLockStoreTest {

    private static final class MemoryFile implements StateFile {
        private Map<String, Object> document = Map.of();
        private boolean failSave;

        @Override
        public Map<String, Object> load() {
            return document;
        }

        @Override
        public void save(Map<String, Object> document) throws IOException {
            if (failSave) {
                throw new IOException("full disk");
            }
            this.document = Map.copyOf(document);
        }

        @Override
        public Optional<String> quarantine() {
            return Optional.empty();
        }
    }

    private static PortalLockStore store(MemoryFile file) {
        return new PortalLockStore(Logger.getLogger("portal-lock-test"), file, Runnable::run);
    }

    @Test
    void markerIsDurableBeforeTheSealCallbackAndClearedAfterRelease() {
        MemoryFile file = new MemoryFile();
        UUID world = UUID.randomUUID();
        PortalLockStore first = store(file);
        assertTrue(first.loadNow());
        first.arm(world, saved -> {
            assertTrue(saved);
            assertEquals(Map.of(PortalLockStore.KEY_PREFIX + world, true), file.document);
        });
        assertTrue(first.isLocked(world));

        PortalLockStore afterRestart = store(file);
        assertTrue(afterRestart.loadNow());
        assertTrue(afterRestart.isLocked(world));
        afterRestart.clear(world);
        assertEquals(Map.of(), file.document);
        assertFalse(afterRestart.isLocked(world));
    }

    @Test
    void failedMarkerWriteDoesNotAllowSealing() {
        MemoryFile file = new MemoryFile();
        file.failSave = true;
        PortalLockStore store = store(file);
        store.loadNow();
        UUID world = UUID.randomUUID();
        AtomicBoolean callback = new AtomicBoolean(true);
        store.arm(world, callback::set);
        assertFalse(callback.get());
        assertFalse(store.isLocked(world));
    }

    @Test
    void failedClearKeepsRecoveryMarkerForNextStartup() {
        MemoryFile file = new MemoryFile();
        PortalLockStore store = store(file);
        store.loadNow();
        UUID world = UUID.randomUUID();
        store.arm(world, saved -> assertTrue(saved));
        file.failSave = true;
        store.clear(world);
        assertTrue(store.isLocked(world));
        PortalLockStore afterRestart = store(file);
        assertTrue(afterRestart.loadNow());
        assertTrue(afterRestart.isLocked(world));
    }

    @Test
    void handEditedOrFalseMarkersAreIgnored() {
        UUID world = UUID.randomUUID();
        assertEquals(java.util.Set.of(world), PortalLockStore.fromDocument(Map.of(
                PortalLockStore.KEY_PREFIX + world, true,
                PortalLockStore.KEY_PREFIX + "not-a-world", true,
                "other", false)));
    }
}
