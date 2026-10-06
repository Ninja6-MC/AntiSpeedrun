package com.ninja6.antispeedrun.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.storage.CreditSource;
import com.ninja6.antispeedrun.storage.PersonalCredit;
import com.ninja6.antispeedrun.storage.PersonalCreditStore;
import com.ninja6.antispeedrun.storage.StateFile;

/** {@code /asr credit} changes apply in the order they were issued (#216). */
class SerialExecutorTest {

    private static final Logger LOGGER = Logger.getLogger(SerialExecutorTest.class.getName());

    /** A backing executor that holds its tasks until the test runs them, newest first if asked. */
    private static final class HeldExecutor implements Executor {
        final Deque<Runnable> held = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            held.add(task);
        }

        void runNewestFirst() {
            while (!held.isEmpty()) {
                held.pollLast().run();
            }
        }
    }

    @Test
    @DisplayName("a task issued after another runs after it, however the backing pool orders them")
    void issueOrderSurvivesAnUnorderedPool() {
        HeldExecutor backing = new HeldExecutor();
        SerialExecutor serial = new SerialExecutor(backing, LOGGER);
        List<String> ran = new ArrayList<>();

        serial.execute(() -> ran.add("revoke"));
        serial.execute(() -> ran.add("grant"));
        assertEquals(1, backing.held.size(), "only one drain is ever handed to the pool");

        backing.runNewestFirst();
        assertEquals(List.of("revoke", "grant"), ran);
    }

    @Test
    @DisplayName("a revoke then a grant of the same credit leaves the player holding it")
    void revokeThenGrantEndsGranted() throws Exception {
        PersonalCreditStore store = new PersonalCreditStore(LOGGER, new NoFile(), Runnable::run);
        store.loadNow();
        UUID player = UUID.randomUUID();
        store.record(player, PersonalCredit.MINE_STONE, CreditSource.ACTION);

        CreditArgument.Change revoke = change("revoke", player);
        CreditArgument.Change grant = change("grant", player);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 200; round++) {
                SerialExecutor serial = new SerialExecutor(pool, LOGGER);
                CountDownLatch done = new CountDownLatch(2);
                serial.execute(() -> {
                    revoke.applyTo(store, player);
                    done.countDown();
                });
                serial.execute(() -> {
                    grant.applyTo(store, player);
                    done.countDown();
                });
                assertTrue(done.await(5, TimeUnit.SECONDS));
                assertTrue(store.has(player, PersonalCredit.MINE_STONE, false),
                        "round " + round + ": the grant issued last must be what stands");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("on a real pool, tasks never overlap and finish in submission order")
    void noOverlapOnARealPool() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            SerialExecutor serial = new SerialExecutor(pool, LOGGER);
            List<Integer> ran = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger overlaps = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(500);
            for (int i = 0; i < 500; i++) {
                int n = i;
                serial.execute(() -> {
                    if (inFlight.incrementAndGet() != 1) {
                        overlaps.incrementAndGet();
                    }
                    ran.add(n);
                    inFlight.decrementAndGet();
                    done.countDown();
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(0, overlaps.get());
            for (int i = 0; i < 500; i++) {
                assertEquals(i, ran.get(i));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a task that throws does not stop the ones queued behind it")
    void failureDoesNotStall() {
        HeldExecutor backing = new HeldExecutor();
        SerialExecutor serial = new SerialExecutor(backing, Logger.getAnonymousLogger());
        List<String> ran = new ArrayList<>();
        serial.execute(() -> {
            throw new IllegalStateException("expected by the test");
        });
        serial.execute(() -> ran.add("after"));
        backing.runNewestFirst();
        assertEquals(List.of("after"), ran);

        serial.execute(() -> ran.add("later"));
        assertEquals(1, backing.held.size(), "an idle executor starts a new drain");
        backing.runNewestFirst();
        assertEquals(List.of("after", "later"), ran);
    }

    @Test
    @DisplayName("a pool that refuses work does not leave the executor waiting on a drain")
    void refusedDrainDoesNotWedge() {
        AtomicInteger refusals = new AtomicInteger(1);
        HeldExecutor accepting = new HeldExecutor();
        SerialExecutor serial = new SerialExecutor(task -> {
            if (refusals.getAndDecrement() > 0) {
                throw new RejectedExecutionException("disabled");
            }
            accepting.execute(task);
        }, LOGGER);
        List<String> ran = new ArrayList<>();
        assertThrows(RejectedExecutionException.class, () -> serial.execute(() -> ran.add("lost")));

        serial.execute(() -> ran.add("next"));
        assertFalse(accepting.held.isEmpty());
        accepting.runNewestFirst();
        assertEquals(List.of("next"), ran);
    }

    private static CreditArgument.Change change(String action, UUID player) {
        return (CreditArgument.Change) CreditArgument.parse(
                new String[] {"credit", action, player.toString(), "mine-stone"});
    }

    private static final class NoFile implements StateFile {
        @Override
        public Map<String, Object> load() {
            return Map.of();
        }

        @Override
        public void save(Map<String, Object> document) {
        }

        @Override
        public Optional<String> quarantine() {
            return Optional.empty();
        }
    }
}
