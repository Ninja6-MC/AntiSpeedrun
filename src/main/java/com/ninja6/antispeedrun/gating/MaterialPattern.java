package com.ninja6.antispeedrun.gating;

import java.util.Locale;
import java.util.Objects;

/**
 * One {@code match-patterns} entry, parsed into a match mode and the literal it tests against.
 *
 * <p>The shipped file uses three of the four modes and Epic 5 needs the fourth, so
 * {@code pattern.replace("*", "")} plus {@code startsWith} — which is all the original blueprint
 * specified — cannot express what is already configured. Audit finding R-11.
 *
 * <table>
 *   <caption>Match modes</caption>
 *   <tr><th>Pattern</th><th>Mode</th><th>Matches</th></tr>
 *   <tr><td>{@code SHIELD}</td><td>{@link Mode#EXACT}</td><td>only {@code SHIELD}</td></tr>
 *   <tr><td>{@code IRON_*}</td><td>{@link Mode#PREFIX}</td><td>{@code IRON_INGOT}, {@code IRON_ORE}</td></tr>
 *   <tr><td>{@code *_IRON_ORE}</td><td>{@link Mode#SUFFIX}</td><td>{@code DEEPSLATE_IRON_ORE}</td></tr>
 *   <tr><td>{@code *_DIAMOND_*}</td><td>{@link Mode#CONTAINS}</td><td>{@code DEEPSLATE_DIAMOND_ORE}</td></tr>
 * </table>
 *
 * <p>The suffix mode is the one that closes audit finding C-11: prefix matching catches
 * {@code IRON_ORE} but not {@code DEEPSLATE_IRON_ORE}, so below Y=0 — where essentially all
 * diamond mining happens — silk-touched ore was ungated.
 *
 * <p>Matching is name-based and case-insensitive on input: a pattern is folded to upper case at
 * parse time, which is the case Bukkit's {@code Material} constants already use. All of this runs
 * once, while compiling a snapshot; nothing here is on an event path.
 *
 * @param mode    how {@link #literal()} is tested against a material name
 * @param literal the wildcard-free part of the pattern, upper-cased; empty only for {@link Mode#ALL}
 * @param source  the pattern exactly as it was written in {@code config.yml}, for error messages
 */
record MaterialPattern(Mode mode, String literal, String source) {

    /** How a pattern's literal is tested against a material name. */
    enum Mode {
        /** No wildcard: the name must equal the literal. */
        EXACT,
        /** Trailing wildcard, {@code IRON_*}. */
        PREFIX,
        /** Leading wildcard, {@code *_ORE}. */
        SUFFIX,
        /** Wildcards on both ends, {@code *_DIAMOND_*}. */
        CONTAINS,
        /** A bare {@code *}: every material in the universe. */
        ALL
    }

    MaterialPattern {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(literal, "literal");
        Objects.requireNonNull(source, "source");
    }

    /**
     * Parses one raw pattern.
     *
     * @return the parsed pattern, or {@code null} if it is unusable — an empty string, or a
     *         wildcard in the middle such as {@code IRON_*_ORE}, which no mode expresses. A caller
     *         records a warning naming the pattern rather than guessing at an interpretation,
     *         because silently reading {@code IRON_*_ORE} as the prefix {@code IRON_} would gate
     *         every iron item on a config line that asked for one.
     */
    static MaterialPattern parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.equals("*") || trimmed.equals("**")) {
            return new MaterialPattern(Mode.ALL, "", raw);
        }

        boolean leading = trimmed.charAt(0) == '*';
        boolean trailing = trimmed.charAt(trimmed.length() - 1) == '*';
        String core = trimmed.substring(leading ? 1 : 0, trimmed.length() - (trailing ? 1 : 0));
        if (core.isEmpty() || core.indexOf('*') >= 0) {
            return null;
        }

        Mode mode;
        if (leading && trailing) {
            mode = Mode.CONTAINS;
        } else if (trailing) {
            mode = Mode.PREFIX;
        } else if (leading) {
            mode = Mode.SUFFIX;
        } else {
            mode = Mode.EXACT;
        }
        return new MaterialPattern(mode, core, raw);
    }

    /** Whether {@code materialName}, which must already be upper case, matches this pattern. */
    boolean matches(String materialName) {
        return switch (mode) {
            case ALL -> true;
            case EXACT -> materialName.equals(literal);
            case PREFIX -> materialName.startsWith(literal);
            case SUFFIX -> materialName.endsWith(literal);
            case CONTAINS -> materialName.contains(literal);
        };
    }
}
