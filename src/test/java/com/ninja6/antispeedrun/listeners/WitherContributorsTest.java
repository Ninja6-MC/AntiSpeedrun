package com.ninja6.antispeedrun.listeners;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WitherContributorsTest {
    @Test
    void onlyPositiveFinitePlayerDamageCounts() {
        assertTrue(WitherContributors.counts(0.01D, true, false));
        for (double damage : new double[] {0.0D, -1.0D, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(WitherContributors.counts(damage, true, false));
        }
        assertFalse(WitherContributors.counts(5.0D, false, false));
        assertFalse(WitherContributors.counts(5.0D, true, true));
    }

    @Test
    void contributorsSurviveEntitySaveAndReloadWithoutDuplicates() {
        UUID first = new UUID(Long.MIN_VALUE, Long.MAX_VALUE);
        UUID second = UUID.randomUUID();
        Set<UUID> contributors = new LinkedHashSet<>();
        contributors.add(first);
        contributors.add(second);
        contributors.add(first);
        long[] persisted = WitherContributors.encode(contributors);
        assertEquals(4, persisted.length);
        Set<UUID> reloaded = WitherContributors.decode(persisted);
        assertEquals(Set.of(first, second), reloaded);
        assertFalse(reloaded.add(first));
        assertEquals(4, WitherContributors.encode(reloaded).length);
    }

    @Test
    void missingOrMalformedEntityStateCreditsNobody() {
        assertTrue(WitherContributors.decode(null).isEmpty());
        assertTrue(WitherContributors.decode(new long[] {1L}).isEmpty());
        assertTrue(WitherContributors.decode(new long[0]).isEmpty());
    }

    @Test
    void damageAndDeathOnlyObserveUncancelledEvents() throws Exception {
        for (var method : WitherCreditListener.class.getDeclaredMethods()) {
            var handler = method.getAnnotation(org.bukkit.event.EventHandler.class);
            if (handler != null) {
                assertEquals(org.bukkit.event.EventPriority.MONITOR, handler.priority());
                assertTrue(handler.ignoreCancelled());
            }
        }
    }
}
