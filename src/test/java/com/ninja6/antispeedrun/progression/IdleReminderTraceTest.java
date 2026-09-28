package com.ninja6.antispeedrun.progression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bound on {@link IdleReminderEngine#traced}, asserted rather than assumed.
 *
 * <p>{@code traced} is what keeps an admitted failure to one log line: the throttle admits one line
 * per stage per minute, and handing the throwable to {@code Logger#log} instead would print every
 * frame of it -- up to 1024 for the {@code StackOverflowError} the engine now catches. It is the same
 * arithmetic as the quoted value in {@code ConfigReader}, now shared through
 * {@link com.ninja6.antispeedrun.logging.LogLine}, and until now only that copy had tests, which is
 * how the same regression reached review twice.
 *
 * <p>No engine is constructed: {@code traced} is static and touches nothing but the throwable, which
 * is the point of it being separable.
 */
class IdleReminderTraceTest {

    private static final String NEWLINE = String.valueOf((char) 0x0a);
    private static final String RETURN = String.valueOf((char) 0x0d);
    private static final String ESC = String.valueOf((char) 0x1b);
    private static final String BELL = String.valueOf((char) 0x07);
    private static final String NUL = String.valueOf((char) 0x00);

    /** A throwable with a stack of exactly {@code depth} frames, named so their width is known. */
    private static Throwable withFrames(Throwable thrown, int depth) {
        StackTraceElement[] frames = new StackTraceElement[depth];
        for (int i = 0; i < depth; i++) {
            frames[i] = new StackTraceElement("C", "m" + i, "C.java", i);
        }
        thrown.setStackTrace(frames);
        return thrown;
    }

    @Test
    @DisplayName("an overflow's thousand frames and embedded message come out as one bounded line")
    void boundsTheMessageAndTheFrames() {
        // The shape the SEND arm actually catches: MiniMessage builds a parse message by embedding
        // the whole template, and a recursive overflow arrives with a full stack.
        Throwable thrown = withFrames(new RuntimeException("goal ".repeat(20_000)), 1_024);

        String traced = IdleReminderEngine.traced(thrown);

        assertTrue(traced.contains("truncated"),
                "the embedded template is marked rather than quoted whole: " + traced);
        assertTrue(traced.contains("more frames"),
                "and the elided frames are counted: " + traced);
        assertTrue(traced.length() < 1_200,
                "one admitted failure is one bounded line whatever the throwable carries, was "
                        + traced.length() + " characters for a " + thrown.getMessage().length()
                        + "-character message and " + thrown.getStackTrace().length + " frames");
    }

    @Test
    @DisplayName("a multi-line message with an escape in it stays one line, with no control left")
    void collapsesWhitespaceAndStripsControls() {
        // The engine emits this through a single logger.warning call, so a message carrying line
        // breaks would become one console record per line. ESC is not whitespace, so collapsing
        // whitespace alone would still replay an ANSI sequence into the operator's terminal.
        Throwable thrown = withFrames(
                new RuntimeException("first" + NEWLINE + "second" + RETURN + "third" + ESC
                        + "[31m" + BELL + NUL + "fourth"), 2);

        String traced = IdleReminderEngine.traced(thrown);

        assertFalse(traced.contains(NEWLINE) || traced.contains(RETURN),
                "no half of an admitted line may carry a line break: " + traced);
        for (int i = 0; i < traced.length(); i++) {
            char at = traced.charAt(i);
            assertFalse(at < 0x20 || at == 0x7f,
                    "a control character U+%04X survived at %d".formatted((int) at, i));
        }
        assertTrue(traced.contains("first second third [31m fourth"),
                "and what was written is still legible: " + traced);
    }

    @Test
    @DisplayName("a wrapped fault contributes its own message, not just its class name")
    void namesTheCausesMessage() {
        // Where the send wraps the real fault, the message and the throwing frame are the cause's and
        // the wrapper's frames identify only the plugin code that re-threw. A line that reads
        // "RuntimeException ... caused by NullPointerException" and stops there carries nothing to act
        // on.
        Throwable cause = withFrames(new IllegalStateException("tag resolver rejected the placeholder"), 4);
        Throwable thrown = withFrames(new RuntimeException("send failed", cause), 4);

        String traced = IdleReminderEngine.traced(thrown);

        assertTrue(traced.contains("caused by java.lang.IllegalStateException"),
                "the cause is still named: " + traced);
        assertTrue(traced.contains("tag resolver rejected the placeholder"),
                "and it carries the message that identifies it: " + traced);
        assertTrue(traced.contains("C.m0"),
                "with the frame that threw: " + traced);
    }

    @Test
    @DisplayName("a wrapped fault's own message and stack cannot lift the ceiling")
    void boundsTheCausesMessage() {
        Throwable cause = withFrames(new IllegalStateException("goal ".repeat(20_000)), 1_024);
        Throwable thrown = withFrames(new RuntimeException("send failed", cause), 1_024);

        String traced = IdleReminderEngine.traced(thrown);

        assertTrue(traced.length() < 1_600,
                "a cause cannot lift the ceiling by however deep its own stack was, was "
                        + traced.length() + " characters");
    }

    @Test
    @DisplayName("the cut lands on a code-point boundary, not between the halves of a pair")
    void keepsSurrogatePairsWhole() {
        // 199 filler chars, then a high surrogate at index 199 and its low half at 200: a cut at a
        // fixed char index would put a lone surrogate in the log.
        Throwable thrown = withFrames(
                new RuntimeException("a".repeat(199) + "😀" + "b".repeat(50)), 2);

        String traced = IdleReminderEngine.traced(thrown);

        assertTrue(traced.contains("truncated"), traced);
        int i = 0;
        while (i < traced.length()) {
            char at = traced.charAt(i);
            if (Character.isHighSurrogate(at)) {
                assertTrue(i + 1 < traced.length() && Character.isLowSurrogate(traced.charAt(i + 1)),
                        "a high surrogate at " + i + " with no low half after it: " + traced);
                i += 2;
                continue;
            }
            assertFalse(Character.isLowSurrogate(at),
                    "a low surrogate at " + i + " with no high half before it: " + traced);
            i++;
        }
    }
}
