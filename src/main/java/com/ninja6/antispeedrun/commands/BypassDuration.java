package com.ninja6.antispeedrun.commands;

import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ninja6.antispeedrun.storage.BypassGrant;

/**
 * Parsing and rendering of the optional duration argument to {@code /asr bypass}.
 *
 * <p>Task 8.1.1 specifies the signature as {@code /asr bypass <player>} and #57 requires the grant
 * to carry an expiry, so an unqualified grant has to mean <em>something</em> definite. It means
 * {@link #DEFAULT_MILLIS}: an admin handing out a bypass is nearly always unblocking one player
 * during one incident, and a grant that quietly never ends is how a server ends up with a
 * permanently exempt player nobody remembers exempting. {@code permanent} is available for the
 * cases that really are open-ended, and it has to be typed.
 *
 * <p>Free of Bukkit so the grammar is unit-tested rather than eyeballed.
 */
public final class BypassDuration {

    /** What an unqualified {@code /asr bypass <player>} grants: thirty minutes. */
    public static final long DEFAULT_MILLIS = 30L * 60L * 1000L;

    /** Sentinel returned for {@code off}: revoke rather than grant. */
    public static final long REVOKE = 0L;

    /** Sentinel returned for {@code permanent}: grant until revoked. */
    public static final long PERMANENT = BypassGrant.PERMANENT;

    /** Longest grant accepted: one year. Beyond this the operator means {@code permanent}. */
    public static final long MAX_MILLIS = 365L * 24L * 60L * 60L * 1000L;

    private static final Pattern TERM = Pattern.compile("(\\d+)([smhd])");

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60L * SECOND;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;

    private BypassDuration() {
    }

    /** Suggested values for tab completion, in the order they are offered. */
    public static List<String> suggestions() {
        return List.of("10m", "30m", "1h", "6h", "1d", "permanent", "off");
    }

    /**
     * Parses a duration token.
     *
     * <p>Accepts a sequence of {@code <number><unit>} terms with units {@code s}, {@code m},
     * {@code h}, {@code d} — {@code 30m}, {@code 1h30m}, {@code 2d} — plus the words
     * {@code permanent}/{@code forever} and {@code off}/{@code revoke}/{@code 0}. Nothing else,
     * deliberately: a bare number is rejected rather than guessed at, because an operator who types
     * {@code /asr bypass Steve 30} and gets thirty milliseconds has been silently misunderstood.
     *
     * @return the grant length in milliseconds, or {@link #PERMANENT}, or {@link #REVOKE}; empty
     *         when the token is not a duration at all
     */
    public static OptionalLong parse(String token) {
        if (token == null) {
            return OptionalLong.empty();
        }
        String normalised = token.trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            return OptionalLong.empty();
        }
        switch (normalised) {
            case "permanent", "forever" -> {
                return OptionalLong.of(PERMANENT);
            }
            case "off", "revoke", "none", "0" -> {
                return OptionalLong.of(REVOKE);
            }
            default -> {
                // fall through to the term grammar
            }
        }

        Matcher matcher = TERM.matcher(normalised);
        long total = 0L;
        int consumed = 0;
        while (matcher.find()) {
            if (matcher.start() != consumed) {
                return OptionalLong.empty();
            }
            consumed = matcher.end();
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException overflow) {
                return OptionalLong.empty();
            }
            long unit = switch (matcher.group(2)) {
                case "s" -> SECOND;
                case "m" -> MINUTE;
                case "h" -> HOUR;
                default -> DAY;
            };
            if (amount > MAX_MILLIS / unit) {
                return OptionalLong.empty();
            }
            total += amount * unit;
            if (total > MAX_MILLIS) {
                return OptionalLong.empty();
            }
        }
        if (consumed != normalised.length() || total <= 0L) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(total);
    }

    /**
     * Renders a grant length the way it is echoed back to the operator: {@code 1h 30m},
     * {@code 45s}, or {@code permanent}.
     */
    public static String format(long millis) {
        if (millis == PERMANENT) {
            return "permanent";
        }
        if (millis <= 0L) {
            return "0s";
        }
        StringBuilder rendered = new StringBuilder();
        long remaining = millis;
        remaining = append(rendered, remaining, DAY, "d");
        remaining = append(rendered, remaining, HOUR, "h");
        remaining = append(rendered, remaining, MINUTE, "m");
        append(rendered, remaining, SECOND, "s");
        return rendered.isEmpty() ? "0s" : rendered.toString();
    }

    private static long append(StringBuilder rendered, long remaining, long unit, String suffix) {
        long count = remaining / unit;
        if (count > 0L) {
            if (!rendered.isEmpty()) {
                rendered.append(' ');
            }
            rendered.append(count).append(suffix);
        }
        return remaining - count * unit;
    }
}
