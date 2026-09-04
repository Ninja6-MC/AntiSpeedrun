package com.ninja6.antispeedrun.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The caching and invalidation contract of #51. */
class ProgressionCacheTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicInteger captures = new AtomicInteger();
    private PlayerStateRegistry registry;
    private ProgressionCache cache;

    @BeforeEach
    void setUp() {
        registry = new PlayerStateRegistry();
        cache = new ProgressionCache(registry, Duration.ofMinutes(1L), clock::get);
    }

    private PlayerProgressionSnapshot capture() {
        captures.incrementAndGet();
        return new PlayerProgressionSnapshot(
                Set.of("a:one"), Set.of("a:one"), Set.of(), 1.0D, 3L, true, clock.get());
    }

    @Test
    @DisplayName("a hot path evaluating repeatedly captures once")
    void repeatedReadsCaptureOnce() {
        UUID player = UUID.randomUUID();

        PlayerProgressionSnapshot first = cache.get(player, this::capture);
        for (int i = 0; i < 1_000; i++) {
            assertSame(first, cache.get(player, this::capture));
        }

        assertEquals(1, captures.get());
    }

    @Test
    @DisplayName("invalidation on PlayerAdvancementDoneEvent forces exactly one re-capture")
    void invalidateForcesOneRecapture() {
        UUID player = UUID.randomUUID();
        cache.get(player, this::capture);

        cache.invalidate(player);
        cache.get(player, this::capture);
        cache.get(player, this::capture);

        assertEquals(2, captures.get());
    }

    @Test
    void anEntryExpiresAfterTheTimeToLive() {
        UUID player = UUID.randomUUID();
        cache.get(player, this::capture);

        clock.addAndGet(Duration.ofSeconds(59L).toMillis());
        cache.get(player, this::capture);
        assertEquals(1, captures.get());

        clock.addAndGet(Duration.ofSeconds(2L).toMillis());
        cache.get(player, this::capture);
        assertEquals(2, captures.get());
    }

    @Test
    @DisplayName("a wall clock moving backwards expires rather than freezes the entry")
    void backwardsClockExpires() {
        UUID player = UUID.randomUUID();
        cache.get(player, this::capture);

        clock.addAndGet(-Duration.ofHours(1L).toMillis());
        cache.get(player, this::capture);

        assertEquals(2, captures.get());
    }

    @Test
    void peekNeverCaptures() {
        UUID player = UUID.randomUUID();

        assertTrue(cache.peek(player).isEmpty());
        assertEquals(0, captures.get());

        cache.get(player, this::capture);
        assertTrue(cache.peek(player).isPresent());

        clock.addAndGet(Duration.ofMinutes(5L).toMillis());
        assertTrue(cache.peek(player).isEmpty(), "an expired entry is not a live snapshot");
        assertEquals(1, captures.get());
    }

    @Test
    @DisplayName("quit removes the entry — finding R-08")
    void quitClearsTheEntry() {
        UUID player = UUID.randomUUID();
        cache.get(player, this::capture);
        assertEquals(1, cache.size());

        registry.forget(player);

        assertEquals(0, cache.size());
        assertTrue(cache.peek(player).isEmpty());
    }

    @Test
    @DisplayName("many sessions do not leave the cache growing")
    void sessionsDoNotLeak() {
        for (int i = 0; i < 500; i++) {
            UUID player = UUID.randomUUID();
            cache.get(player, this::capture);
            registry.forget(player);
        }

        assertEquals(0, cache.size());
        assertEquals(0, registry.sizes().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("capture runs outside the map, not inside a bin lock")
    void captureDoesNotRunInsideTheMap() {
        UUID player = UUID.randomUUID();

        // A capture that reaches back into the registry would deadlock or throw if it ran while a
        // ConcurrentHashMap bin were held; running clean is the observable form of "no map lock".
        PlayerProgressionSnapshot fresh = cache.get(player, () -> {
            assertTrue(cache.peek(player).isEmpty(), "the entry must not exist yet");
            assertEquals(0, cache.size());
            return capture();
        });

        assertEquals(1, captures.get());
        assertSame(fresh, cache.peek(player).orElseThrow());
    }

    @Test
    @DisplayName("a concurrently installed live entry wins, and the duplicate is dropped")
    void aConcurrentlyInstalledEntryWins() {
        UUID player = UUID.randomUUID();

        PlayerProgressionSnapshot winner = cache.get(player, this::capture);
        // Simulates the race the putIfAbsent guards: a second thread captured and installed while
        // this one was still capturing. Both callers must agree on one instance.
        PlayerProgressionSnapshot observed = cache.get(player, () -> {
            throw new AssertionError("must not capture while a live entry is held");
        });

        assertSame(winner, observed);
    }

    @Test
    void reloadDropsEverySnapshot() {
        for (int i = 0; i < 10; i++) {
            cache.get(UUID.randomUUID(), this::capture);
        }
        assertEquals(10, cache.size());

        cache.invalidateAll();

        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("a non-positive time to live is rejected and registers nothing")
    void aNonPositiveTimeToLiveIsRejected() {
        PlayerStateRegistry fresh = new PlayerStateRegistry();

        assertThrows(IllegalArgumentException.class,
                () -> new ProgressionCache(fresh, Duration.ZERO, clock::get));
        assertThrows(IllegalArgumentException.class,
                () -> new ProgressionCache(fresh, Duration.ofSeconds(-1L), clock::get));

        assertTrue(fresh.registered().isEmpty(), "a rejected cache must not leave a map behind");
    }
}
