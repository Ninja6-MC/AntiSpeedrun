package com.ninja6.antispeedrun.logging;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Turns text the plugin did not write into text a single log record can carry.
 *
 * <p>Two places need this and they are in different packages: {@code ConfigReader} quotes a rejected
 * configuration value and the message of whatever rejected it, and {@code IdleReminderEngine} renders
 * a caught throwable for the throttled failure line. Both write one {@code Logger} call per warning,
 * so both have the same three obligations, and both used to discharge them with their own copy of the
 * same arithmetic. The copies drifted: the value half was bounded without being collapsed while the
 * diagnosis half was collapsed before being bounded, so one warning about a multi-line value became
 * one console record per line in it. One helper is what stops the next fix landing in only one of
 * them; the ceiling stays a caller's constant, because 120 characters of a quoted value and 200 of a
 * thrown message are different budgets for different readers.
 */
public final class LogLine {

    private LogLine() {
    }

    /**
     * Everything that must not reach a log record verbatim, as one character class.
     *
     * <p>{@code \s} is not enough on its own. It matches the line breaks and tabs that would split
     * one warning across several console records, but it does not match {@code ESC}, and an
     * arbitrary YAML scalar may contain one: a value carrying an ANSI sequence would otherwise be
     * replayed into the operator's terminal, where it colours or repositions text that is not the
     * plugin's to colour. {@code \p{Cntrl}} covers {@code ESC} with the rest of C0 and {@code DEL},
     * and the range U+0080 to U+009F covers the C1 set, whose {@code CSI} is the single-character
     * form of the same escape.
     *
     * <p>Neither class reaches outside Latin-1, and two more groups need naming. U+2028 LINE
     * SEPARATOR and U+2029 PARAGRAPH SEPARATOR are line breaks to any viewer that follows Unicode
     * rather than ASCII, so they split a warning exactly as {@code \n} would. The bidirectional
     * controls — U+061C, U+200E and U+200F, the embeddings and overrides U+202A to U+202E, and the
     * isolates U+2066 to U+2069 — are not line breaks, but a viewer that honours them reorders what
     * follows, so a value carrying U+202E can make the rest of the warning read backwards.
     *
     * <p>Each run becomes one space rather than nothing, so removing a line break cannot silently
     * join two words into one that was never written.
     */
    private static final Pattern NOT_ONE_LINE = Pattern.compile(
            "[\\s\\p{Cntrl}\\x{80}-\\x{9f}\\x{2028}\\x{2029}"
                    + "\\x{61c}\\x{200e}\\x{200f}\\x{202a}-\\x{202e}\\x{2066}-\\x{2069}]+");

    /**
     * {@code text} as a log record may carry it: one line, no control characters, {@code maxChars}
     * long at most.
     *
     * <p>A cut lands on a code-point boundary. A plain {@code substring} at a fixed char index can
     * fall between the halves of a surrogate pair and put a lone surrogate in the log, where it is no
     * longer the character whoever wrote it wrote. Backing off one char when the last kept char is a
     * high surrogate whose low half is being cut costs one comparison and keeps the prefix
     * well-formed.
     *
     * <p>The marker reports the length of the text as it arrived, before collapsing, so a truncated
     * quote cannot be mistaken for a short value and the reader learns how much was not shown.
     *
     * @param text     the untrusted text
     * @param maxChars how much of it the caller's line budget allows; at least 1
     * @return the sanitised text, with a {@code ... [truncated, N chars]} marker when it was cut
     */
    public static String oneLine(String text, int maxChars) {
        Objects.requireNonNull(text, "text");
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be at least 1, was " + maxChars);
        }
        String collapsed = NOT_ONE_LINE.matcher(text).replaceAll(" ").trim();
        if (collapsed.length() <= maxChars) {
            return collapsed;
        }
        int cut = maxChars;
        if (Character.isHighSurrogate(collapsed.charAt(cut - 1))
                && Character.isLowSurrogate(collapsed.charAt(cut))) {
            cut--;
        }
        return collapsed.substring(0, cut) + "... [truncated, " + text.length() + " chars]";
    }
}
