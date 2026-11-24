package com.ninja6.antispeedrun.listeners;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

/** The arithmetic of the respawn-on-entry cooldown (#272), free of the server so it can be tested. */
public final class RespawnCooldownRules {

    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private RespawnCooldownRules() {
    }

    /**
     * How long is left of a cooldown of {@code hours} measured from {@code lastVictory}.
     *
     * @return empty with no victory recorded, with a cooldown of {@code 0}, or once it has run out
     */
    public static Optional<Duration> remaining(OptionalLong lastVictory, double hours, long nowMillis) {
        if (lastVictory.isEmpty() || !(hours > 0.0D) || !Double.isFinite(hours)) {
            return Optional.empty();
        }
        long ends = lastVictory.getAsLong() + Math.round(hours * MILLIS_PER_HOUR);
        long left = ends - nowMillis;
        return left > 0 ? Optional.of(Duration.ofMillis(left)) : Optional.empty();
    }

    /**
     * A remaining time as a player reads it, rounded up to the minute: {@code "5h 3m"},
     * {@code "42m"}, {@code "1m"}.
     */
    public static String describe(Duration left) {
        long minutes = Math.max(1L, (left.toMillis() + 59_999L) / 60_000L);
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        if (hours == 0L) {
            return rest + "m";
        }
        return rest == 0L ? hours + "h" : hours + "h " + rest + "m";
    }
}
