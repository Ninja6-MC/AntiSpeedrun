package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The respawn-on-entry cooldown's arithmetic (#272). */
class RespawnCooldownRulesTest {

    private static final long HOUR = 3_600_000L;

    @Test
    @DisplayName("the cooldown runs from the victory for the configured hours")
    void remaining() {
        assertEquals(Optional.of(Duration.ofHours(4)),
                RespawnCooldownRules.remaining(OptionalLong.of(0L), 6.0D, 2 * HOUR));
        assertEquals(Optional.of(Duration.ofMillis(1)),
                RespawnCooldownRules.remaining(OptionalLong.of(0L), 6.0D, 6 * HOUR - 1));
        assertEquals(Optional.of(Duration.ofSeconds(36)),
                RespawnCooldownRules.remaining(OptionalLong.of(0L), 0.01D, 0L));
    }

    @Test
    @DisplayName("nothing is left once it runs out, with no victory, or with a cooldown of 0")
    void none() {
        assertTrue(RespawnCooldownRules.remaining(OptionalLong.of(0L), 6.0D, 6 * HOUR).isEmpty());
        assertTrue(RespawnCooldownRules.remaining(OptionalLong.empty(), 6.0D, 0L).isEmpty());
        assertTrue(RespawnCooldownRules.remaining(OptionalLong.of(0L), 0.0D, 0L).isEmpty());
        assertTrue(RespawnCooldownRules.remaining(OptionalLong.of(0L), Double.NaN, 0L).isEmpty());
    }

    @Test
    @DisplayName("a later victory restarts the cooldown from that victory")
    void later() {
        long now = 7 * HOUR;
        assertTrue(RespawnCooldownRules.remaining(OptionalLong.of(0L), 6.0D, now).isEmpty());
        assertEquals(Optional.of(Duration.ofHours(5)),
                RespawnCooldownRules.remaining(OptionalLong.of(6 * HOUR), 6.0D, now));
    }

    @Test
    @DisplayName("time left reads in hours and minutes, rounded up")
    void describe() {
        assertEquals("1m", RespawnCooldownRules.describe(Duration.ofSeconds(1)));
        assertEquals("1m", RespawnCooldownRules.describe(Duration.ofSeconds(60)));
        assertEquals("2m", RespawnCooldownRules.describe(Duration.ofSeconds(61)));
        assertEquals("42m", RespawnCooldownRules.describe(Duration.ofMinutes(42)));
        assertEquals("1h", RespawnCooldownRules.describe(Duration.ofHours(1)));
        assertEquals("5h 3m", RespawnCooldownRules.describe(Duration.ofMinutes(303)));
    }
}
