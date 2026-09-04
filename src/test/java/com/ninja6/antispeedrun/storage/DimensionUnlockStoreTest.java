package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store's contract: unlocks survive a restart, a redundant command writes nothing, and a
 * failure to read or write is loud but never fatal.
 *
 * <p>{@link StateFile} is the seam that makes this testable — Bukkit's {@code YamlConfiguration} is
 * not on the test classpath, so {@link YamlStateFile} itself is out of reach here and everything
 * above it is not.
 */
class DimensionUnlockStoreTest {

    /** An in-memory {@link StateFile} standing in for {@code state.yml}. */
    private static final class InMemoryStateFile implements StateFile {

        private Map<String, Object> document = Map.of();
        private int saves;
        private IOException failWith;

        @Override
        public Map<String, Object> load() throws IOException {
            if (failWith != null) {
                throw failWith;
            }
            return document;
        }

        @Override
        public void save(Map<String, Object> document) throws IOException {
            saves++;
            if (failWith != null) {
                throw failWith;
            }
            this.document = Map.copyOf(document);
        }
    }

    /** Runs writes inline; production hands them to Folia's AsyncScheduler. */
    private static final Executor INLINE = Runnable::run;

    private static Logger quietLogger(List<LogRecord> captured) {
        Logger logger = Logger.getLogger("DimensionUnlockStoreTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    @Test
    @DisplayName("an unlock survives a restart: it is written, and a fresh store reads it back")
    void survivesRestart() {
        InMemoryStateFile file = new InMemoryStateFile();
        DimensionUnlockStore first = new DimensionUnlockStore(quietLogger(new ArrayList<>()), file, INLINE);
        first.loadNow();

        assertTrue(first.unlock(DimensionUnlock.THE_END, 1_234L));

        // The same file, a new process.
        DimensionUnlockStore afterRestart =
                new DimensionUnlockStore(quietLogger(new ArrayList<>()), file, INLINE);
        assertTrue(afterRestart.loadNow());
        assertTrue(afterRestart.isUnlocked(DimensionUnlock.THE_END));
        assertFalse(afterRestart.isUnlocked(DimensionUnlock.NETHER));
        assertEquals(1_234L, afterRestart.state().unlockedAt(DimensionUnlock.THE_END).orElseThrow());
    }

    @Test
    @DisplayName("unlocking an already-unlocked dimension changes nothing and writes nothing")
    void redundantUnlockIsFree() {
        InMemoryStateFile file = new InMemoryStateFile();
        DimensionUnlockStore store = new DimensionUnlockStore(quietLogger(new ArrayList<>()), file, INLINE);

        assertTrue(store.unlock(DimensionUnlock.NETHER, 1L));
        assertEquals(1, file.saves);
        assertFalse(store.unlock(DimensionUnlock.NETHER, 2L));
        assertEquals(1, file.saves, "a no-op unlock must not touch the file");
    }

    @Test
    @DisplayName("locking removes the record and persists the removal")
    void lockPersists() {
        InMemoryStateFile file = new InMemoryStateFile();
        DimensionUnlockStore store = new DimensionUnlockStore(quietLogger(new ArrayList<>()), file, INLINE);

        store.unlock(DimensionUnlock.NETHER, 1L);
        assertTrue(store.lock(DimensionUnlock.NETHER));
        assertFalse(store.lock(DimensionUnlock.NETHER));

        DimensionUnlockStore afterRestart =
                new DimensionUnlockStore(quietLogger(new ArrayList<>()), file, INLINE);
        afterRestart.loadNow();
        assertFalse(afterRestart.isUnlocked(DimensionUnlock.NETHER));
    }

    @Test
    @DisplayName("the in-memory state is live before the write runs, so no gate flaps")
    void mutationIsSynchronous() {
        List<Runnable> queued = new ArrayList<>();
        InMemoryStateFile file = new InMemoryStateFile();
        DimensionUnlockStore store =
                new DimensionUnlockStore(quietLogger(new ArrayList<>()), file, queued::add);

        store.unlock(DimensionUnlock.NETHER, 1L);

        // Nothing has been written yet, but the gate must already see the unlock -- otherwise
        // /asr unlock would not take effect until the async write landed.
        assertEquals(0, file.saves);
        assertTrue(store.isUnlocked(DimensionUnlock.NETHER));

        queued.forEach(Runnable::run);
        assertEquals(1, file.saves);
    }

    @Test
    @DisplayName("a queued write persists the latest state, not the state when it was queued")
    void writesConverge() {
        List<Runnable> queued = new ArrayList<>();
        InMemoryStateFile file = new InMemoryStateFile();
        DimensionUnlockStore store =
                new DimensionUnlockStore(quietLogger(new ArrayList<>()), file, queued::add);

        store.unlock(DimensionUnlock.NETHER, 1L);
        store.unlock(DimensionUnlock.THE_END, 2L);
        // Run them out of order: neither may resurrect a superseded document.
        queued.get(1).run();
        queued.get(0).run();

        DimensionUnlockStore afterRestart =
                new DimensionUnlockStore(quietLogger(new ArrayList<>()), file, INLINE);
        afterRestart.loadNow();
        assertTrue(afterRestart.isUnlocked(DimensionUnlock.NETHER));
        assertTrue(afterRestart.isUnlocked(DimensionUnlock.THE_END));
    }

    @Test
    @DisplayName("an unreadable file is reported and treated as empty rather than failing startup")
    void unreadableFileIsNotFatal() {
        List<LogRecord> logged = new ArrayList<>();
        InMemoryStateFile file = new InMemoryStateFile();
        file.failWith = new IOException("state.yml is a directory");

        DimensionUnlockStore store = new DimensionUnlockStore(quietLogger(logged), file, INLINE);

        assertFalse(store.loadNow());
        assertEquals(UnlockState.empty(), store.state());
        assertTrue(logged.stream().anyMatch(record -> record.getLevel() == Level.SEVERE),
                "an unreadable state file must be logged at SEVERE, not swallowed");
    }

    @Test
    @DisplayName("a failed write is reported and leaves the unlock live for this run")
    void failedWriteIsNotFatal() {
        List<LogRecord> logged = new ArrayList<>();
        InMemoryStateFile file = new InMemoryStateFile();
        DimensionUnlockStore store = new DimensionUnlockStore(quietLogger(logged), file, INLINE);

        file.failWith = new IOException("disk full");
        assertTrue(store.unlock(DimensionUnlock.NETHER, 1L));

        assertTrue(store.isUnlocked(DimensionUnlock.NETHER));
        assertTrue(logged.stream().anyMatch(record -> record.getLevel() == Level.SEVERE));
    }
}
