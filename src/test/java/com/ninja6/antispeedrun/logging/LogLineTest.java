package com.ninja6.antispeedrun.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What {@link LogLine#oneLine} removes, one character class at a time.
 *
 * <p>The C0 and whitespace halves are exercised through {@code IdleReminderTraceTest} and the
 * {@code ConfigReader} warnings. These are the classes neither reaches: C1, the Unicode line and
 * paragraph separators, and the bidirectional controls. Every character is built from its code
 * point rather than written as an escape, so the source itself carries none of them.
 */
class LogLineTest {

    private static String wrapped(int codePoint) {
        return "before" + Character.toString(codePoint) + "after";
    }

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(ints = {0x80, 0x85, 0x9b, 0x9f})
    @DisplayName("a C1 control becomes a space, CSI and NEL among them")
    void stripsC1(int codePoint) {
        assertEquals("before after", LogLine.oneLine(wrapped(codePoint), 200));
    }

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(ints = {0x2028, 0x2029})
    @DisplayName("a Unicode line or paragraph separator cannot split the record")
    void stripsUnicodeLineBreaks(int codePoint) {
        assertEquals("before after", LogLine.oneLine(wrapped(codePoint), 200));
    }

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(ints = {0x061c, 0x200e, 0x200f, 0x202a, 0x202b, 0x202c, 0x202d, 0x202e,
            0x2066, 0x2067, 0x2068, 0x2069})
    @DisplayName("a bidirectional control cannot reorder the rest of the line")
    void stripsBidiControls(int codePoint) {
        assertEquals("before after", LogLine.oneLine(wrapped(codePoint), 200));
    }

    @Test
    @DisplayName("the neighbours of each stripped range are left alone")
    void leavesTheNeighboursAlone() {
        // One past each end of every range above: Latin-1 NBSP, the zero-width joiner and
        // non-joiner that emoji and several scripts depend on, the narrow NBSP after the
        // embeddings, and the word joiner before the isolates.
        for (int codePoint : new int[] {0xa0, 0x200c, 0x200d, 0x202f, 0x2060, 0x206a}) {
            String text = wrapped(codePoint);
            assertEquals(text, LogLine.oneLine(text, 200),
                    "U+%04X is not in any stripped class".formatted(codePoint));
        }
    }

    @Test
    @DisplayName("a run of mixed separators collapses to one space")
    void collapsesAMixedRun() {
        String run = "a" + Character.toString(0x2028) + Character.toString(0x9b)
                + Character.toString(0x202e) + " " + Character.toString(0x2069) + "b";

        String line = LogLine.oneLine(run, 200);

        assertEquals("a b", line);
        assertFalse(line.chars().anyMatch(c -> c == 0x202e), "no override survives: " + line);
    }
}
