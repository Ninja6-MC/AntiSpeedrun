package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

        /** Set independently of {@code failWith}: a file can be unreadable but still movable. */
        private IOException failQuarantineWith;

        /** Documents quarantine moved aside, in order. Empty until damage is preserved. */
        private final List<Map<String, Object>> quarantined = new ArrayList<>();

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

        @Override
        public Optional<String> quarantine() throws IOException {
            if (failQuarantineWith != null) {
                throw failQuarantineWith;
            }
            if (document.isEmpty()) {
                return Optional.empty();
            }
            // Stands in for the rename: the damaged bytes leave the live document and survive.
            quarantined.add(document);
            document = Map.of();
            failWith = null;
            return Optional.of("state.yml.corrupt-20260904-153012");
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
    @DisplayName("a damaged file is moved aside, not overwritten, by the next unlock")
    void damagedFileIsPreservedBeforeBeingReplaced() {
        List<LogRecord> logged = new ArrayList<>();
        InMemoryStateFile file = new InMemoryStateFile();
        // A server that had both dimensions open, whose state.yml is now unreadable.
        file.document = Map.of("dimension-unlocks.nether", 1L, "dimension-unlocks.the_end", 2L);
        Map<String, Object> damaged = file.document;
        file.failWith = new IOException("state.yml is truncated");

        DimensionUnlockStore store = new DimensionUnlockStore(quietLogger(logged), file, INLINE);
        assertFalse(store.loadNow());
        assertTrue(store.isAwaitingQuarantine());

        store.unlock(DimensionUnlock.NETHER, 99L);

        // The regression: this used to replace the only surviving record of what had been
        // unlocked with an empty document, silently. The damaged bytes must still exist.
        assertEquals(List.of(damaged), file.quarantined,
                "the damaged document must be moved aside before anything overwrites it");
        assertFalse(store.isAwaitingQuarantine(), "quarantine clears the latch");
        assertEquals(1, file.saves);
    }

    @Test
    @DisplayName("once quarantined, the new state persists normally across a restart")
    void writesResumeAfterQuarantine() {
        InMemoryStateFile file = new InMemoryStateFile();
        file.document = Map.of("dimension-unlocks.nether", 1L);
        file.failWith = new IOException("state.yml is truncated");

        DimensionUnlockStore store = new DimensionUnlockStore(quietLogger(new ArrayList<>()), file, INLINE);
        store.loadNow();
        store.unlock(DimensionUnlock.THE_END, 99L);

        // Refusing to persist at all would have kept the damaged file intact but made every unlock
        // for the rest of the run silently non-durable. Quarantine gets both.
        DimensionUnlockStore afterRestart =
                new DimensionUnlockStore(quietLogger(new ArrayList<>()), file, INLINE);
        assertTrue(afterRestart.loadNow());
        assertTrue(afterRestart.isUnlocked(DimensionUnlock.THE_END));
    }

    @Test
    @DisplayName("if the damaged file cannot be moved aside, nothing is written over it")
    void unmovableDamagedFileIsLeftAlone() {
        List<LogRecord> logged = new ArrayList<>();
        InMemoryStateFile file = new InMemoryStateFile();
        Map<String, Object> damaged = Map.of("dimension-unlocks.nether", 1L);
        file.document = damaged;
        file.failWith = new IOException("state.yml is truncated");
        file.failQuarantineWith = new IOException("permission denied");

        DimensionUnlockStore store = new DimensionUnlockStore(quietLogger(logged), file, INLINE);
        store.loadNow();
        store.unlock(DimensionUnlock.THE_END, 99L);

        assertEquals(0, file.saves, "the write must be abandoned, not attempted");
        assertEquals(damaged, file.document, "the damaged document must be exactly as it was");
        assertTrue(store.isAwaitingQuarantine(), "the latch stays set so a later unlock retries");
        assertTrue(logged.stream().anyMatch(record -> record.getLevel() == Level.SEVERE));
        // The unlock is still in effect for this run; only its durability was lost.
        assertTrue(store.isUnlocked(DimensionUnlock.THE_END));
    }

    @Test
    @DisplayName("a clean load never quarantines anything")
    void healthyFileIsNeverQuarantined() {
        InMemoryStateFile file = new InMemoryStateFile();
        file.document = Map.of("dimension-unlocks.nether", 1L);

        DimensionUnlockStore store = new DimensionUnlockStore(quietLogger(new ArrayList<>()), file, INLINE);
        assertTrue(store.loadNow());
        store.unlock(DimensionUnlock.THE_END, 2L);

        assertTrue(file.quarantined.isEmpty());
        assertFalse(store.isAwaitingQuarantine());
    }

    @Test
    @DisplayName("two dimensions unlocked at the same instant cannot lose one of the updates")
    void concurrentMutationsDoNotLoseUpdates() throws Exception {
        // #75: unlock and lock each read `state`, derived a new UnlockState from that read and
        // wrote it back. Two commands interleaving lost whichever update was derived from the
        // older snapshot -- silently, with both commands reporting success. Repeated because a
        // lost update is a race, not a certainty.
        for (int round = 0; round < 200; round++) {
            InMemoryStateFile file = new InMemoryStateFile();
            DimensionUnlockStore store =
                    new DimensionUnlockStore(quietLogger(new ArrayList<>()), file, INLINE);

            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> nether = pool.submit(() -> {
                    start.await();
                    return store.unlock(DimensionUnlock.NETHER, 1L);
                });
                Future<Boolean> end = pool.submit(() -> {
                    start.await();
                    return store.unlock(DimensionUnlock.THE_END, 2L);
                });
                assertTrue(nether.get(), "the nether unlock changed something, so it must say so");
                assertTrue(end.get(), "the end unlock changed something, so it must say so");
            } finally {
                pool.shutdownNow();
            }

            assertTrue(store.isUnlocked(DimensionUnlock.NETHER)
                            && store.isUnlocked(DimensionUnlock.THE_END),
                    "round " + round + ": both unlocks must survive, in state " + store.state());
        }
    }

    @Test
    @DisplayName("an unlock and a lock racing each other both take effect")
    void concurrentUnlockAndLockBothLand() throws Exception {
        for (int round = 0; round < 200; round++) {
            InMemoryStateFile file = new InMemoryStateFile();
            DimensionUnlockStore store =
                    new DimensionUnlockStore(quietLogger(new ArrayList<>()), file, INLINE);
            // The End starts open, so the racing lock has something to remove.
            store.unlock(DimensionUnlock.THE_END, 1L);

            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> opening = pool.submit(() -> {
                    start.await();
                    return store.unlock(DimensionUnlock.NETHER, 2L);
                });
                Future<Boolean> closing = pool.submit(() -> {
                    start.await();
                    return store.lock(DimensionUnlock.THE_END);
                });
                assertTrue(opening.get());
                assertTrue(closing.get());
            } finally {
                pool.shutdownNow();
            }

            assertTrue(store.isUnlocked(DimensionUnlock.NETHER),
                    "round " + round + ": the nether unlock was lost, state " + store.state());
            assertFalse(store.isUnlocked(DimensionUnlock.THE_END),
                    "round " + round + ": the end lock was lost, state " + store.state());
        }
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
