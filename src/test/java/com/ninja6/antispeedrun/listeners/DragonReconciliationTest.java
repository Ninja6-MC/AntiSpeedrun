package com.ninja6.antispeedrun.listeners;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Single-battle reconciliation (#56, Task 6.1.5). {@link BossCombatListener} needs a running server;
 * the decisions it applies and the state it keeps are tested here.
 */
class DragonReconciliationTest {

    @Nested
    @DisplayName("who may die")
    class Death {

        @Test
        @DisplayName("the primary is held while any secondary lives")
        void primaryHeld() {
            assertTrue(DragonReconciliationRules.refusesDeath(false, 1));
            assertTrue(DragonReconciliationRules.refusesDeath(false, 4));
        }

        @Test
        @DisplayName("the primary dies normally once the last secondary is down")
        void primaryReleased() {
            assertFalse(DragonReconciliationRules.refusesDeath(false, 0));
        }

        @Test
        @DisplayName("a secondary is never held, whatever else still flies")
        void secondaryNeverHeld() {
            assertFalse(DragonReconciliationRules.refusesDeath(true, 0));
            assertFalse(DragonReconciliationRules.refusesDeath(true, 3));
        }

        @Test
        @DisplayName("killing one of five, in any order, leaves the victory to the last")
        void fiveDragons() {
            SecondaryRoster roster = new SecondaryRoster();
            UUID[] secondaries = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
            for (UUID dragon : secondaries) {
                roster.track(dragon);
            }
            for (UUID dragon : secondaries) {
                assertTrue(DragonReconciliationRules.refusesDeath(false, roster.living()));
                assertFalse(DragonReconciliationRules.refusesDeath(true, roster.living()));
                roster.forget(dragon);
            }
            assertFalse(DragonReconciliationRules.refusesDeath(false, roster.living()));
        }
    }

    @Nested
    @DisplayName("secondary XP")
    class Experience {

        @Test
        @DisplayName("balanced XP gives each secondary 1,000 instead of a first-kill award")
        void balanced() {
            assertEquals(1_000, DragonReconciliationRules.secondaryExperience(12_000, true));
            assertEquals(1_000, DragonReconciliationRules.secondaryExperience(500, true));
            assertEquals(1_000, DragonReconciliationRules.secondaryExperience(120, true));
        }

        @Test
        @DisplayName("without balanced XP the earlier vanilla repeat-kill cap remains")
        void unbalanced() {
            assertEquals(500, DragonReconciliationRules.secondaryExperience(12_000, false));
            assertEquals(500, DragonReconciliationRules.secondaryExperience(500, false));
            assertEquals(120, DragonReconciliationRules.secondaryExperience(120, false));
        }

        @Test
        @DisplayName("doMobLoot's zero is never converted into an award")
        void noMobLoot() {
            assertEquals(0, DragonReconciliationRules.secondaryExperience(0, true));
            assertEquals(0, DragonReconciliationRules.secondaryExperience(0, false));
            assertEquals(0, DragonReconciliationRules.secondaryExperience(-5, true));
            assertEquals(0, DragonReconciliationRules.secondaryExperience(-5, false));
        }
    }

    @Nested
    @DisplayName("crystal healing")
    class Crystals {

        @Test
        @DisplayName("a secondary does not heal from End crystals")
        void secondaryRefused() {
            assertTrue(DragonReconciliationRules.refusesRegain(true, "ENDER_CRYSTAL"));
        }

        @Test
        @DisplayName("the primary keeps its crystals, and other healing is untouched")
        void otherwiseAllowed() {
            assertFalse(DragonReconciliationRules.refusesRegain(false, "ENDER_CRYSTAL"));
            assertFalse(DragonReconciliationRules.refusesRegain(true, "MAGIC"));
            assertFalse(DragonReconciliationRules.refusesRegain(true, null));
        }
    }

    @Nested
    @DisplayName("the roster")
    class Roster {

        @Test
        @DisplayName("an unload keeps a secondary alive; every other removal ends it")
        void removalCauses() {
            assertTrue(DragonReconciliationRules.survivesRemoval("UNLOAD"));
            assertTrue(DragonReconciliationRules.survivesRemoval("PLAYER_QUIT"));
            assertFalse(DragonReconciliationRules.survivesRemoval("DEATH"));
            assertFalse(DragonReconciliationRules.survivesRemoval("PLUGIN"));
            assertFalse(DragonReconciliationRules.survivesRemoval("DISCARD"));
            assertFalse(DragonReconciliationRules.survivesRemoval(null));
        }

        @Test
        @DisplayName("a secondary reported twice, by its spawn and its chunk load, counts once")
        void idempotent() {
            SecondaryRoster roster = new SecondaryRoster();
            UUID dragon = UUID.randomUUID();
            assertTrue(roster.track(dragon));
            assertFalse(roster.track(dragon));
            assertEquals(1, roster.living());
            assertTrue(roster.forget(dragon));
            assertFalse(roster.forget(dragon));
            assertEquals(0, roster.living());
        }

        @Test
        @DisplayName("a spawn another plugin cancelled is not counted, so the primary is not held")
        void cancelledSpawn() {
            SecondaryRoster roster = new SecondaryRoster();
            assertFalse(roster.trackSpawned(UUID.randomUUID(), false));
            assertEquals(0, roster.living());
            assertFalse(DragonReconciliationRules.refusesDeath(false, roster.living()));
            assertTrue(roster.trackSpawned(UUID.randomUUID(), true));
            assertEquals(1, roster.living());
        }

        @Test
        @DisplayName("region threads reporting at once lose no secondary")
        void concurrent() throws InterruptedException {
            SecondaryRoster roster = new SecondaryRoster();
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int n = 0; n < 250; n++) {
                            roster.track(UUID.randomUUID());
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            pool.shutdownNow();
            assertEquals(threads * 250, roster.living());
        }
    }

    @Nested
    @DisplayName("the window across a restart")
    class Restart {

        @Test
        @DisplayName("a window recorded as resolved never opens again")
        void persistedResolved() {
            ReinforcementWindow window = new ReinforcementWindow(true);
            assertEquals(ReinforcementWindow.Phase.RESOLVED, window.phase());
            assertFalse(window.tryOpen());
        }

        @Test
        @DisplayName("a window with no record opens as before")
        void noRecord() {
            ReinforcementWindow window = new ReinforcementWindow(false);
            assertTrue(window.tryOpen());
        }

        @Test
        @DisplayName("a secondary found mid-countdown stops the window from spawning a second set")
        void foundMidCountdown() {
            ReinforcementWindow window = new ReinforcementWindow();
            assertTrue(window.tryOpen());
            assertTrue(window.markResolved());
            assertFalse(window.resolve());
            assertFalse(window.tryOpen());
        }

        @Test
        @DisplayName("marking an already resolved window reports no change")
        void markTwice() {
            ReinforcementWindow window = new ReinforcementWindow();
            assertTrue(window.markResolved());
            assertFalse(window.markResolved());
        }
    }
}
