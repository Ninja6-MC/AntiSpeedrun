package com.ninja6.antispeedrun.listeners;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-tick budget that holds the single-hit cap for a same-tick burst (#203). */
class BossDamageLedgerTest {

    private static final double CAP = 12.0D;

    private final BossDamageLedger ledger = new BossDamageLedger();
    private final UUID wither = UUID.randomUUID();
    private final UUID dragon = UUID.randomUUID();

    /**
     * Runs a hit of {@code finalDamage} through the listener's decisions and returns what lands:
     * cancelled once the budget is spent, otherwise clamped to what is left of it.
     */
    private double hit(UUID boss, long tick, double finalDamage) {
        double cap = ledger.remaining(boss, tick, CAP);
        double landed = finalDamage;
        if (DamageCapRules.exceeds(finalDamage, cap)) {
            landed = DamageCapRules.exceeds(cap, 0.0D)
                    ? DamageCapRules.clampBase(finalDamage, base -> base, cap)
                    : 0.0D;
        }
        ledger.record(boss, tick, landed, 0L);
        return landed;
    }

    @Test
    @DisplayName("a fresh boss has the whole cap to spend")
    void freshBudget() {
        assertEquals(CAP, ledger.remaining(wither, 5L, CAP));
        assertEquals(0.0D, ledger.spent(wither, 5L));
    }

    @Test
    @DisplayName("eight same-tick explosions remove at most the cap in total")
    void sameTickStackHeldToCap() {
        // The finals #203 recorded with the per-hit cap alone: 43.10 in total.
        double[] stack = {45.44D, 4.28D, 3.09D, 1.73D, 3.04D, 2.39D, 7.84D, 8.74D};
        double total = 0.0D;
        for (double damage : stack) {
            total += hit(wither, 100L, damage);
        }
        assertEquals(CAP, total, 1e-9);
    }

    @Test
    @DisplayName("small hits in one tick share the budget and the one that crosses it is clamped")
    void smallHitsShareBudget() {
        assertEquals(5.0D, hit(dragon, 7L, 5.0D), 1e-9);
        assertEquals(5.0D, hit(dragon, 7L, 5.0D), 1e-9);
        assertEquals(2.0D, hit(dragon, 7L, 5.0D), 1e-9);
        assertEquals(0.0D, hit(dragon, 7L, 5.0D), 1e-9);
    }

    @Test
    @DisplayName("the budget refills on the next tick, so separate hits are each held to the cap")
    void nextTickRefills() {
        assertEquals(CAP, hit(wither, 1L, 40.0D), 1e-9);
        assertEquals(0.0D, ledger.remaining(wither, 1L, CAP), 1e-9);
        assertEquals(CAP, ledger.remaining(wither, 2L, CAP), 1e-9);
        assertEquals(CAP, hit(wither, 2L, 40.0D), 1e-9);
    }

    @Test
    @DisplayName("each boss has its own budget")
    void perBoss() {
        assertEquals(CAP, hit(wither, 3L, 40.0D), 1e-9);
        assertEquals(CAP, hit(dragon, 3L, 40.0D), 1e-9);
    }

    @Test
    @DisplayName("cancelled and zero hits spend nothing")
    void zeroNotRecorded() {
        ledger.record(wither, 4L, 0.0D, 0L);
        ledger.record(wither, 4L, Double.NaN, 0L);
        assertEquals(0, ledger.size());
    }

    @Test
    @DisplayName("a dead boss's entry is dropped")
    void forget() {
        hit(wither, 9L, 4.0D);
        ledger.forget(wither);
        assertEquals(0, ledger.size());
        assertEquals(CAP, ledger.remaining(wither, 9L, CAP));
    }

    @Test
    @DisplayName("entries for bosses gone quiet are pruned once the ledger grows")
    void prunesStale() {
        for (int i = 0; i < BossDamageLedger.PRUNE_AT; i++) {
            ledger.record(UUID.randomUUID(), 1L, 1.0D, 0L);
        }
        ledger.record(wither, 1L, 1.0D, BossDamageLedger.STALE_MILLIS + 1L);
        assertEquals(1, ledger.size());
        assertTrue(ledger.spent(wither, 1L) > 0.0D);
    }
}
