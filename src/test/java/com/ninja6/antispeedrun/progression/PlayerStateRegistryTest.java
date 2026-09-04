package com.ninja6.antispeedrun.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The per-player state contract of #51: concurrent by construction, and cleaned up on quit.
 *
 * <p>Finding C-07 notes that a plain {@code HashMap} here "passes every unit test in Epic 8,
 * because none of them run concurrently". {@link #concurrentWritersDoNotLoseEntries()} is the one
 * that does run concurrently.
 */
class PlayerStateRegistryTest {

    @Test
    @DisplayName("one forget clears every registered map, including ones added later")
    void forgetClearsEveryMap() {
        PlayerStateRegistry registry = new PlayerStateRegistry();
        PlayerStateMap<Long> throttle = registry.register("item-feedback-throttle");
        PlayerStateMap<String> bypass = registry.register("bypass-flags");

        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        throttle.put(player, 1L);
        bypass.put(player, "gates");
        throttle.put(other, 2L);

        registry.forget(player);

        assertTrue(throttle.get(player).isEmpty());
        assertTrue(bypass.get(player).isEmpty());
        assertTrue(throttle.get(other).isPresent(), "another player's state is untouched");
    }

    @Test
    void forgetIsIdempotentForAPlayerWithNoState() {
        PlayerStateRegistry registry = new PlayerStateRegistry();
        registry.register("anything");

        UUID stranger = UUID.randomUUID();
        registry.forget(stranger);
        registry.forget(stranger);

        assertEquals(0, registry.sizes().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("a session's state does not survive the session — finding R-08")
    void stateDoesNotLeakAcrossSessions() {
        PlayerStateRegistry registry = new PlayerStateRegistry();
        PlayerStateMap<Long> throttle = registry.register("item-feedback-throttle");

        for (int i = 0; i < 1_000; i++) {
            UUID player = UUID.randomUUID();
            throttle.put(player, (long) i);
            registry.forget(player);
        }

        assertEquals(0, throttle.size());
    }

    @Test
    void forgetAllDropsEveryPlayer() {
        PlayerStateRegistry registry = new PlayerStateRegistry();
        PlayerStateMap<Long> map = registry.register("map");
        for (int i = 0; i < 20; i++) {
            map.put(UUID.randomUUID(), 1L);
        }

        registry.forgetAll();

        assertEquals(0, map.size());
    }

    @Test
    void sizesNameEachRegisteredMap() {
        PlayerStateRegistry registry = new PlayerStateRegistry();
        PlayerStateMap<Long> throttle = registry.register("item-feedback-throttle");
        registry.register("bypass-flags");
        throttle.put(UUID.randomUUID(), 1L);

        assertEquals(2, registry.sizes().size());
        assertEquals(1, registry.sizes().get("item-feedback-throttle"));
        assertEquals(0, registry.sizes().get("bypass-flags"));
    }

    @Test
    void computeIfAbsentRunsTheFactoryOnce() {
        PlayerStateRegistry registry = new PlayerStateRegistry();
        PlayerStateMap<String> map = registry.register("map");
        UUID player = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();

        map.computeIfAbsent(player, id -> {
            calls.incrementAndGet();
            return "value";
        });
        String second = map.computeIfAbsent(player, id -> {
            calls.incrementAndGet();
            return "other";
        });

        assertEquals(1, calls.get());
        assertEquals("value", second);
    }

    @Test
    @DisplayName("concurrent writers from several region threads lose nothing")
    void concurrentWritersDoNotLoseEntries() throws InterruptedException {
        PlayerStateRegistry registry = new PlayerStateRegistry();
        PlayerStateMap<Long> map = registry.register("map");

        int threads = 8;
        int perThread = 500;
        List<UUID> players = new java.util.ArrayList<>(threads * perThread);
        for (int i = 0; i < threads * perThread; i++) {
            players.add(UUID.randomUUID());
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                int offset = t * perThread;
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            map.put(players.get(offset + i), (long) i);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30L, TimeUnit.SECONDS), "writers did not finish");
        } finally {
            pool.shutdownNow();
        }

        // A HashMap here loses entries and can corrupt its table under exactly this load.
        assertEquals(threads * perThread, map.size());
        for (UUID player : players) {
            assertTrue(map.contains(player));
        }
    }

    @Test
    void putIfAbsentReportsWhatWasAlreadyHeld() {
        PlayerStateRegistry registry = new PlayerStateRegistry();
        PlayerStateMap<String> map = registry.register("map");
        UUID player = UUID.randomUUID();

        assertTrue(map.putIfAbsent(player, "first").isEmpty());
        assertEquals("first", map.putIfAbsent(player, "second").orElseThrow());
        assertEquals("first", map.get(player).orElseThrow());
    }

    @Test
    @DisplayName("a conditional remove does not discard what another thread installed")
    void conditionalRemoveOnlyDropsTheExpectedValue() {
        PlayerStateRegistry registry = new PlayerStateRegistry();
        PlayerStateMap<String> map = registry.register("map");
        UUID player = UUID.randomUUID();
        map.put(player, "fresh");

        assertFalse(map.remove(player, "stale"), "a stale expectation removes nothing");
        assertEquals("fresh", map.get(player).orElseThrow());

        assertTrue(map.remove(player, "fresh"));
        assertFalse(map.contains(player));
    }

    @Test
    void removeReturnsWhatWasHeld() {
        PlayerStateRegistry registry = new PlayerStateRegistry();
        PlayerStateMap<Long> map = registry.register("map");
        UUID player = UUID.randomUUID();

        assertTrue(map.remove(player).isEmpty());
        map.put(player, 42L);
        assertEquals(42L, map.remove(player).orElseThrow());
        assertFalse(map.contains(player));
        assertEquals(7L, map.getOrDefault(player, 7L));
    }
}
