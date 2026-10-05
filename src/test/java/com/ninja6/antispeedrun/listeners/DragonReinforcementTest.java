package com.ninja6.antispeedrun.listeners;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.ninja6.antispeedrun.config.PluginConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reinforcement window's decisions (#37, Task 6.1.1), its rounding modes (#38, Task 6.1.2) and
 * resummoned fights (#20, Task 6.1.3). {@link BossCombatListener} needs a running
 * server and its schedulers, and stays untested for the reason {@link ItemGateRulesTest} records;
 * the window, the census join and the spawn arithmetic it drives are tested here.
 */
class DragonReinforcementTest {

    private static final PluginConfig.MultiDragon SHIPPED = PluginConfig.defaults().bossScaling().multiDragon();

    private static PluginConfig.MultiDragon multi(boolean enabled, double multiplier, int max) {
        return multi(enabled, multiplier, PluginConfig.RoundingMode.HALF_UP, max);
    }

    private static PluginConfig.MultiDragon multi(boolean enabled, double multiplier,
            PluginConfig.RoundingMode mode, int max) {
        return new PluginConfig.MultiDragon(enabled, multiplier, mode, max, true);
    }

    @Nested
    @DisplayName("how many dragons")
    class Count {

        @Test
        @DisplayName("a solo player fights the primary alone, and no secondary is spawned")
        void solo() {
            assertEquals(1, DragonReinforcementRules.dragonCount(1, SHIPPED));
            assertEquals(0, DragonReinforcementRules.secondaryCount(1, SHIPPED));
        }

        @Test
        @DisplayName("a party of four earns two dragons on the shipped multiplier")
        void partyOfFour() {
            assertEquals(2, DragonReinforcementRules.dragonCount(4, SHIPPED));
            assertEquals(1, DragonReinforcementRules.secondaryCount(4, SHIPPED));
        }

        @Test
        @DisplayName("an empty island still leaves the primary, and never a negative secondary count")
        void nobodyCounted() {
            assertEquals(1, DragonReinforcementRules.dragonCount(0, SHIPPED));
            assertEquals(0, DragonReinforcementRules.secondaryCount(0, SHIPPED));
        }

        @Test
        @DisplayName("max-dragons caps the count")
        void capped() {
            assertEquals(5, DragonReinforcementRules.dragonCount(40, SHIPPED));
            assertEquals(3, DragonReinforcementRules.dragonCount(40, multi(true, 0.5, 3)));
        }

        @Test
        @DisplayName("multi-dragon.enabled: false always yields one")
        void disabled() {
            assertEquals(1, DragonReinforcementRules.dragonCount(8, multi(false, 0.5, 5)));
        }

        @Test
        @DisplayName("the shipped HALF_UP rounds a half up")
        void halfUp() {
            assertEquals(2, DragonReinforcementRules.dragonCount(3, SHIPPED));
            assertEquals(3, DragonReinforcementRules.dragonCount(5, SHIPPED));
        }
    }

    @Nested
    @DisplayName("rounding modes across parties of one to ten")
    class Rounding {

        private static final PluginConfig.RoundingMode HALF_UP = PluginConfig.RoundingMode.HALF_UP;
        private static final PluginConfig.RoundingMode CEIL = PluginConfig.RoundingMode.CEIL;
        private static final PluginConfig.RoundingMode FLOOR = PluginConfig.RoundingMode.FLOOR;

        /** {@code expected[n - 1]} is the dragon count for a party of {@code n}; max-dragons is ten. */
        private static void assertCounts(double multiplier, PluginConfig.RoundingMode mode, int... expected) {
            PluginConfig.MultiDragon config = multi(true, multiplier, mode, 10);
            for (int n = 1; n <= 10; n++) {
                assertEquals(expected[n - 1], DragonReinforcementRules.dragonCount(n, config),
                        mode + " at " + multiplier + " for a party of " + n);
                assertEquals(expected[n - 1] - 1, DragonReinforcementRules.secondaryCount(n, config));
            }
        }

        @Test
        @DisplayName("HALF_UP rounds a half up and anything less down")
        void halfUp() {
            assertCounts(0.5, HALF_UP, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5);
            assertCounts(0.3, HALF_UP, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3);
            assertCounts(0.7, HALF_UP, 1, 1, 2, 3, 4, 4, 5, 6, 6, 7);
        }

        @Test
        @DisplayName("CEIL rounds any fraction up")
        void ceil() {
            assertCounts(0.5, CEIL, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5);
            assertCounts(0.3, CEIL, 1, 1, 1, 2, 2, 2, 3, 3, 3, 3);
            assertCounts(0.7, CEIL, 1, 2, 3, 3, 4, 5, 5, 6, 7, 7);
        }

        @Test
        @DisplayName("FLOOR drops any fraction, and never goes below one dragon")
        void floor() {
            assertCounts(0.5, FLOOR, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5);
            assertCounts(0.3, FLOOR, 1, 1, 1, 1, 1, 1, 2, 2, 2, 3);
            assertCounts(0.7, FLOOR, 1, 1, 2, 2, 3, 4, 4, 5, 6, 7);
        }

        @Test
        @DisplayName("a whole product is not pushed over by binary error: 10 * 0.7 is seven under CEIL")
        void exactProduct() {
            assertEquals(7, DragonReinforcementRules.dragonCount(10, multi(true, 0.7, CEIL, 10)));
            assertEquals(3, DragonReinforcementRules.dragonCount(10, multi(true, 0.3, FLOOR, 10)));
        }

        @Test
        @DisplayName("every mode keeps a solo player at one dragon, even with a multiplier above one")
        void soloUnderEveryMode() {
            for (PluginConfig.RoundingMode mode : PluginConfig.RoundingMode.values()) {
                assertEquals(1, DragonReinforcementRules.dragonCount(1, multi(true, 1.5, mode, 10)), mode.name());
                assertEquals(3, DragonReinforcementRules.dragonCount(2, multi(true, 1.5, mode, 10)), mode.name());
            }
        }

        @Test
        @DisplayName("every mode is capped by max-dragons, including a huge multiplier")
        void cappedUnderEveryMode() {
            for (PluginConfig.RoundingMode mode : PluginConfig.RoundingMode.values()) {
                assertEquals(4, DragonReinforcementRules.dragonCount(10, multi(true, 0.7, mode, 4)), mode.name());
                assertEquals(5, DragonReinforcementRules.dragonCount(10, multi(true, Double.MAX_VALUE, mode, 5)),
                        mode.name());
            }
        }
    }

    @Nested
    @DisplayName("who counts toward the party")
    class Party {

        @Test
        @DisplayName("a survival player on the main island counts, the 300-block boundary included")
        void onIsland() {
            assertTrue(DragonReinforcementRules.countsTowardParty(true, false, "SURVIVAL", 10, -20));
            assertTrue(DragonReinforcementRules.countsTowardParty(true, false, "ADVENTURE", 300, 0));
        }

        @Test
        @DisplayName("off the island, in another world, dead, creative or spectator does not")
        void excluded() {
            assertFalse(DragonReinforcementRules.countsTowardParty(true, false, "SURVIVAL", 213, 213));
            assertFalse(DragonReinforcementRules.countsTowardParty(false, false, "SURVIVAL", 0, 0));
            assertFalse(DragonReinforcementRules.countsTowardParty(true, true, "SURVIVAL", 0, 0));
            assertFalse(DragonReinforcementRules.countsTowardParty(true, false, "CREATIVE", 0, 0));
            assertFalse(DragonReinforcementRules.countsTowardParty(true, false, "SPECTATOR", 0, 0));
        }
    }

    @Nested
    @DisplayName("where secondaries appear")
    class Spawns {

        @Test
        @DisplayName("points are evenly spaced on the ring, at the spawn height, facing the centre")
        void ring() {
            List<DragonReinforcementRules.SpawnPoint> points = DragonReinforcementRules.spawnPoints(4);
            assertEquals(4, points.size());
            for (DragonReinforcementRules.SpawnPoint p : points) {
                assertEquals(DragonReinforcementRules.SECONDARY_SPAWN_RADIUS, Math.hypot(p.x(), p.z()), 1e-9);
                assertEquals(DragonReinforcementRules.SECONDARY_SPAWN_Y, p.y());
                // Minecraft's facing vector for a yaw: (-sin, cos). It must point back at the origin.
                double yaw = Math.toRadians(p.yaw());
                double fx = -Math.sin(yaw);
                double fz = Math.cos(yaw);
                assertEquals(-1.0, (fx * p.x() + fz * p.z()) / DragonReinforcementRules.SECONDARY_SPAWN_RADIUS,
                        1e-6);
            }
        }

        @Test
        @DisplayName("each point names the chunk, and so the region, its spawn is scheduled on")
        void chunks() {
            DragonReinforcementRules.SpawnPoint p = new DragonReinforcementRules.SpawnPoint(-0.5, 100, 33.0, 0F);
            assertEquals(-1, p.chunkX());
            assertEquals(2, p.chunkZ());
        }

        @Test
        @DisplayName("zero secondaries means no spawn, and a negative count is refused")
        void none() {
            assertTrue(DragonReinforcementRules.spawnPoints(0).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> DragonReinforcementRules.spawnPoints(-1));
        }
    }

    @Nested
    @DisplayName("the window")
    class Window {

        @Test
        @DisplayName("four players entering over twenty seconds open one window, which yields two dragons")
        void fourPlayersOneWindow() {
            ReinforcementWindow window = new ReinforcementWindow();
            // T-30: the first entry opens it; the three that follow within the window do not reopen it.
            assertTrue(window.tryOpen());
            assertFalse(window.tryOpen());
            assertFalse(window.tryOpen());
            assertFalse(window.tryOpen());
            // T=0: all four report from their own regions.
            AtomicInteger dragons = new AtomicInteger();
            CensusTally tally = CensusTally.start(4, counted -> {
                assertTrue(window.resolve());
                dragons.set(DragonReinforcementRules.dragonCount(counted, SHIPPED));
            });
            for (int i = 0; i < 4; i++) {
                tally.counted(true);
            }
            assertEquals(2, dragons.get());
            assertEquals(ReinforcementWindow.Phase.RESOLVED, window.phase());
            assertFalse(window.tryOpen(), "a resolved fight is not reinforced again by a later entry");
        }

        @Test
        @DisplayName("a world whose dragon has been killed opens no window")
        void previouslyKilled() {
            ReinforcementWindow window = new ReinforcementWindow();
            window.refreshPreviouslyKilled(true);
            assertFalse(window.tryOpen());
            assertEquals(ReinforcementWindow.Phase.IDLE, window.phase());
        }

        @Test
        @DisplayName("an abandoned window can be opened again, and resolves only once")
        void abandon() {
            ReinforcementWindow window = new ReinforcementWindow();
            assertTrue(window.tryOpen());
            window.abandon();
            assertEquals(ReinforcementWindow.Phase.IDLE, window.phase());
            assertTrue(window.tryOpen());
            assertTrue(window.resolve());
            assertFalse(window.resolve());
            window.abandon();
            assertEquals(ReinforcementWindow.Phase.RESOLVED, window.phase());
        }

        @Test
        @DisplayName("entries racing on many threads open the window exactly once")
        void racingEntries() throws InterruptedException {
            ReinforcementWindow window = new ReinforcementWindow();
            AtomicInteger opened = new AtomicInteger();
            runConcurrently(32, () -> {
                if (window.tryOpen()) {
                    opened.incrementAndGet();
                }
            });
            assertEquals(1, opened.get());
        }
    }

    @Nested
    @DisplayName("the census")
    class Census {

        @Test
        @DisplayName("only players who count are counted, and a retired player still completes the census")
        void mixed() {
            List<Integer> results = new ArrayList<>();
            CensusTally tally = CensusTally.start(4, results::add);
            tally.counted(true);
            tally.counted(false);
            tally.missed();
            assertTrue(results.isEmpty(), "incomplete until every player asked has reported");
            tally.counted(true);
            assertEquals(List.of(2), results);
            assertThrows(IllegalStateException.class, tally::missed);
        }

        @Test
        @DisplayName("nobody online completes immediately with zero")
        void empty() {
            List<Integer> results = new ArrayList<>();
            CensusTally.start(0, results::add);
            assertEquals(List.of(0), results);
        }

        @Test
        @DisplayName("reports from many threads complete once, with every count seen")
        void concurrent() throws InterruptedException {
            AtomicInteger completions = new AtomicInteger();
            AtomicInteger total = new AtomicInteger(-1);
            CensusTally tally = CensusTally.start(64, counted -> {
                completions.incrementAndGet();
                total.set(counted);
            });
            AtomicInteger n = new AtomicInteger();
            runConcurrently(64, () -> tally.counted(n.getAndIncrement() % 2 == 0));
            assertEquals(1, completions.get());
            assertEquals(32, total.get());
        }
    }

    @Nested
    @DisplayName("resummoned fights")
    class Resummon {

        private PluginConfig.BossScaling scaling(boolean enabled, boolean scaleResummoned) {
            PluginConfig.BossScaling shipped = PluginConfig.defaults().bossScaling();
            return new PluginConfig.BossScaling(enabled, scaleResummoned, shipped.battlePrepSeconds(),
                    shipped.multiDragon(), shipped.exitPortalEgg(), shipped.skullDropChance(),
                    shipped.exitPortalLockDuringBattle(), shipped.exitPortalLockReleaseMinutes());
        }

        @Test
        @DisplayName("resummon scaling is off by default and needs boss-scaling.enabled")
        void armed() {
            assertFalse(DragonReinforcementRules.resummonArmed(PluginConfig.defaults().bossScaling()));
            assertTrue(DragonReinforcementRules.resummonArmed(scaling(true, true)));
            assertFalse(DragonReinforcementRules.resummonArmed(scaling(true, false)));
            assertFalse(DragonReinforcementRules.resummonArmed(scaling(false, true)));
        }

        @Test
        @DisplayName("only the battle's own spawn of an untagged dragon may be the ritual's")
        void spawnReason() {
            assertTrue(DragonReinforcementRules.mayBeResummon("DEFAULT", false));
            assertFalse(DragonReinforcementRules.mayBeResummon("DEFAULT", true), "a secondary is never a resummon");
            for (String other : List.of("COMMAND", "SPAWNER_EGG", "CUSTOM", "NATURAL")) {
                assertFalse(DragonReinforcementRules.mayBeResummon(other, false), other);
            }
            assertFalse(DragonReinforcementRules.mayBeResummon(null, false));
        }

        @Test
        @DisplayName("a resummoned party of six is scaled like a first fight, and its window resolves once")
        void scaledLikeFirstFight() {
            ResummonFight fight = new ResummonFight(UUID.randomUUID());
            assertTrue(fight.window().tryOpen());
            assertFalse(fight.window().tryOpen());
            AtomicInteger dragons = new AtomicInteger();
            CensusTally tally = CensusTally.start(6, counted -> {
                assertTrue(fight.window().resolve());
                dragons.set(DragonReinforcementRules.dragonCount(counted, SHIPPED));
            });
            for (int i = 0; i < 6; i++) {
                tally.counted(true);
            }
            assertEquals(3, dragons.get());
            assertTrue(fight.ongoing());
        }

        @Test
        @DisplayName("each resummon gets a fresh window, even after the first fight's has resolved")
        void freshWindowPerResummon() {
            ReinforcementWindow first = new ReinforcementWindow(true);
            first.refreshPreviouslyKilled(true);
            assertFalse(first.tryOpen());
            ResummonFight one = new ResummonFight(UUID.randomUUID());
            assertTrue(one.window().tryOpen());
            assertTrue(one.window().resolve());
            ResummonFight two = new ResummonFight(UUID.randomUUID());
            assertTrue(two.window().tryOpen(), "a later resummon is reinforced again");
        }

        @Test
        @DisplayName("the fight ends with its own primary, not with another dragon")
        void endsWithPrimary() {
            UUID primary = UUID.randomUUID();
            ResummonFight fight = new ResummonFight(primary);
            assertFalse(fight.end(UUID.randomUUID()));
            assertTrue(fight.ongoing());
            assertTrue(fight.end(primary));
            assertFalse(fight.ongoing());
            assertFalse(fight.end(primary), "ending twice reports once");
        }

        @Test
        @DisplayName("a superseded fight is over, so its countdown abandons")
        void superseded() {
            ResummonFight fight = new ResummonFight(UUID.randomUUID());
            assertTrue(fight.window().tryOpen());
            fight.supersede();
            assertFalse(fight.ongoing());
        }
    }

    private static void runConcurrently(int tasks, Runnable body) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);
        try {
            for (int i = 0; i < tasks; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        body.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Nested
    @DisplayName("what opens the first fight's window")
    class Arrival {

        /**
         * #205: Folia never fires {@code PlayerChangedWorldEvent}, so a window opened only from it
         * never opened there for a player entering by portal or {@code /tp}. The arrival is read
         * from {@link EntityAddToWorldEvent}, which both platforms fire; that it arrives on a live
         * Folia server is not something a unit test can show.
         */
        @Test
        @DisplayName("being added to a world opens it, observing at MONITOR")
        void addedToWorld() throws NoSuchMethodException {
            Method handler = BossCombatListener.class.getMethod("onAddedToWorld", EntityAddToWorldEvent.class);
            EventHandler annotation = handler.getAnnotation(EventHandler.class);
            assertNotNull(annotation, "the handler must carry @EventHandler");
            assertEquals(EventPriority.MONITOR, annotation.priority());
        }

        @Test
        @DisplayName("nothing listens for PlayerChangedWorldEvent, which Folia never fires")
        void notChangedWorld() {
            for (Method method : BossCombatListener.class.getMethods()) {
                if (method.isAnnotationPresent(EventHandler.class)) {
                    assertFalse(List.of(method.getParameterTypes()).contains(PlayerChangedWorldEvent.class),
                            method.getName() + " listens for PlayerChangedWorldEvent");
                }
            }
        }

        @Test
        @DisplayName("a login inside the End still opens it")
        void join() throws NoSuchMethodException {
            Method handler = BossCombatListener.class.getMethod("onJoin", PlayerJoinEvent.class);
            assertNotNull(handler.getAnnotation(EventHandler.class), "the handler must carry @EventHandler");
        }

        @Test
        @DisplayName("repeated arrivals, as a login and an add both report, open one window")
        void repeatedArrivals() {
            ReinforcementWindow window = new ReinforcementWindow();
            assertTrue(window.tryOpen());
            assertFalse(window.tryOpen());
            assertFalse(window.tryOpen());
        }
    }
}
