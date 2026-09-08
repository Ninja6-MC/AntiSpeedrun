package com.ninja6.antispeedrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Guards the convention that no production type takes a publicly reachable monitor.
 *
 * <p>A {@code synchronized} method takes the receiver's own monitor, and {@code synchronized
 * (this)} takes exactly the same one. For a type any other plugin on the server can reach — a
 * {@code JavaPlugin} through {@code Bukkit.getPluginManager().getPlugin}, anything handed out by an
 * accessor, or a listener the plugin manager holds — that monitor is public: a third party can hold
 * it across an arbitrary operation, or take it in an order that deadlocks against a lock this
 * plugin also uses. {@code synchronized (SomeClass.class)} has the same problem for the class
 * object, which every classloader-visible caller can name.
 *
 * <p>Every place in this plugin that needs mutual exclusion therefore declares a private lock
 * object and synchronizes a block on it. The exemplars are {@code ConfigSnapshotHolder.swapLock},
 * {@code ProfileApplier.APPLY_LOCK}, {@code DimensionUnlockStore.stateLock} and {@code
 * DimensionUnlockStore.writeLock}, and {@code AntiSpeedrunPlugin.configLock}.
 *
 * <p>The check reads source rather than bytecode on purpose. {@code paper-api} and Adventure are
 * {@code compileOnly}, so reflecting over the production classes would resolve signatures whose
 * types are not on the test classpath; a {@code synchronized (this)} block leaves no distinguishing
 * mark in a signature anyway; and the thing being asserted is a source-level convention that should
 * fail on the line that breaks it.
 *
 * <p>{@code synchronized} blocks on any other expression pass. Whether the object such a block locks
 * is genuinely unreachable from outside is a judgement about which the regular review is the right
 * instrument; {@code this}, {@code Outer.this} and {@code SomeClass.class} are the cases where no
 * judgement is needed.
 */
class SynchronizedMethodSweepTest {

    /** {@code synchronized} not immediately followed by a {@code (}, i.e. a method modifier. */
    private static final Pattern SYNCHRONIZED_METHOD =
            Pattern.compile("\\bsynchronized\\b(?!\\s*\\()");

    /** A dotted name: {@code Foo}, {@code Outer.Inner}, {@code com.ninja6.Foo}. */
    private static final String QUALIFIER =
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\s*\\.\\s*[A-Za-z_$][A-Za-z0-9_$]*)*";

    /**
     * {@code synchronized (this)}, {@code synchronized (Outer.this)} or {@code synchronized
     * (Some.Qualified.Name.class)} — the block forms that take a monitor external code can also
     * name. A qualified {@code this} is the form an inner-class listener reaches for, and it takes
     * the enclosing instance's monitor, which is as public as its own. Redundant parentheses around
     * the monitor are tolerated, and every gap is {@code \s*}, so a form split across lines matches
     * too.
     */
    private static final Pattern SYNCHRONIZED_PUBLIC_MONITOR = Pattern.compile(
            "\\bsynchronized\\s*\\(\\s*(?:\\(\\s*)*(?:this|" + QUALIFIER
                    + "\\s*\\.\\s*(?:this|class))\\s*(?:\\)\\s*)*\\)");

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    @Test
    @DisplayName("no production type synchronizes on a publicly reachable monitor")
    void noPublicMonitorsInMainSources() throws IOException {
        assertTrue(Files.isDirectory(SOURCE_ROOT),
                "Expected to run with the project directory as the working directory, so that "
                        + SOURCE_ROOT + " resolves. It did not.");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                for (String offence : findOffenders(text)) {
                    offenders.add(source + ":" + offence);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "A synchronized method, or a block locking `this` or a class literal, takes a "
                        + "monitor that any code holding a reference to the instance or naming the "
                        + "class can also take. Declare a private lock object and synchronize a "
                        + "block on it instead. Found:\n"
                        + String.join("\n", offenders));
    }

    /**
     * Returns {@code line:text} for every offending line in {@code source}, in file order. Comments
     * and the contents of string, character and text-block literals are removed before matching, so
     * the word {@code synchronized} inside them is not mistaken for code.
     *
     * <p>Matching runs over the whole stripped text rather than line by line, so a form broken
     * across lines — {@code synchronized (} then {@code this) {} on the next — is still seen.
     * Nothing in the build reformats sources, so that layout is possible. Each match is mapped back
     * to the line its first character sits on; the stripper preserves every newline, so an offset
     * into the stripped text is an offset into the source.
     */
    private static List<String> findOffenders(String source) {
        String stripped = stripCommentsAndLiterals(source);
        String[] original = source.split("\n", -1);
        SortedSet<Integer> lines = new TreeSet<>();
        for (Pattern pattern : List.of(SYNCHRONIZED_METHOD, SYNCHRONIZED_PUBLIC_MONITOR)) {
            Matcher matcher = pattern.matcher(stripped);
            while (matcher.find()) {
                lines.add(lineOf(stripped, matcher.start()));
            }
        }
        List<String> offenders = new ArrayList<>();
        for (int line : lines) {
            offenders.add((line + 1) + ": " + original[line].trim());
        }
        return offenders;
    }

    /** The zero-based index of the line that {@code offset} falls on. */
    private static int lineOf(String text, int offset) {
        int line = 0;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Blanks out comments and literal contents, replacing every removed character with a space so
     * that line numbers and columns survive. Line-oriented stripping cannot do this correctly: a
     * {@code //} inside a string literal does not start a comment, and a declaration following a
     * closed {@code /* ... *}{@code /} on the same line is still code.
     */
    private static String stripCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);

            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
                continue;
            }
            if (c == '"' && source.startsWith("\"\"\"", i)) {
                out.append("   ");
                i += 3;
                while (i < n && !source.startsWith("\"\"\"", i)) {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        // A text-block line continuation escapes the newline itself. Emit that
                        // newline rather than a space, or the stripped text loses a line and every
                        // later offence is reported one line early.
                        out.append(' ').append(source.charAt(i + 1) == '\n' ? '\n' : ' ');
                        i += 2;
                        continue;
                    }
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("   ");
                    i += 3;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n && source.charAt(i) != quote && source.charAt(i) != '\n') {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        out.append(' ').append(source.charAt(i + 1) == '\n' ? '\n' : ' ');
                        i += 2;
                        continue;
                    }
                    out.append(' ');
                    i++;
                }
                if (i < n && source.charAt(i) == quote) {
                    out.append(' ');
                    i++;
                }
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * The sweep reads source with a hand-rolled scanner, so the scanner itself is worth pinning
     * down: a source-scanning test that can misread its own input gets disbelieved rather than
     * fixed.
     */
    @Nested
    @DisplayName("the scanner")
    class ScannerTest {

        @Test
        @DisplayName("flags a synchronized method modifier")
        void flagsMethodModifier() {
            assertEquals(List.of("1: private synchronized void go() { }"),
                    findOffenders("private synchronized void go() { }\n"));
        }

        @Test
        @DisplayName("flags synchronized (this)")
        void flagsThis() {
            assertEquals(List.of("1: synchronized (this) {"), findOffenders("synchronized (this) {\n"));
            assertEquals(List.of("1: synchronized(this) {"), findOffenders("synchronized(this) {\n"));
        }

        @Test
        @DisplayName("flags synchronized (Outer.this), the inner-class form of the same hazard")
        void flagsQualifiedThis() {
            assertEquals(List.of("1: synchronized (Outer.this) {"),
                    findOffenders("synchronized (Outer.this) {\n"));
            assertEquals(List.of("1: synchronized (com.ninja6.Outer.this) {"),
                    findOffenders("synchronized (com.ninja6.Outer.this) {\n"));
        }

        @Test
        @DisplayName("flags a monitor broken across lines, and one in redundant parentheses")
        void flagsMonitorSpanningLines() {
            assertEquals(List.of("1: synchronized ("),
                    findOffenders("synchronized (\n        this) {\n"));
            assertEquals(List.of("1: synchronized ((this)) {"),
                    findOffenders("synchronized ((this)) {\n"));
        }

        @Test
        @DisplayName("flags synchronized on a class literal, qualified or not")
        void flagsClassLiteral() {
            assertEquals(List.of("1: synchronized (Foo.class) {"),
                    findOffenders("synchronized (Foo.class) {\n"));
            assertEquals(List.of("1: synchronized (com.ninja6.Foo.class) {"),
                    findOffenders("synchronized (com.ninja6.Foo.class) {\n"));
        }

        @Test
        @DisplayName("passes a block on a private lock field")
        void passesPrivateLock() {
            assertEquals(List.of(), findOffenders("synchronized (stateLock) {\n"));
            assertEquals(List.of(), findOffenders("synchronized (this.stateLock) {\n"));
        }

        @Test
        @DisplayName("ignores synchronized inside a string literal")
        void ignoresStringLiteral() {
            assertEquals(List.of(),
                    findOffenders("log(\"synchronized (this) is banned\");\n"));
            assertEquals(List.of(), findOffenders("char c = '\\''; // synchronized (this)\n"));
        }

        @Test
        @DisplayName("ignores synchronized inside a text block")
        void ignoresTextBlock() {
            assertEquals(List.of(), findOffenders("""
                    String s = \"""
                            synchronized (this) {
                            private synchronized void go();
                            \""";
                    """));
        }

        @Test
        @DisplayName("ignores synchronized inside comments, including a // inside a string")
        void ignoresComments() {
            assertEquals(List.of(), findOffenders("// synchronized (this)\n"));
            assertEquals(List.of(), findOffenders("/*\n * synchronized (this)\n */\n"));
            assertEquals(List.of(), findOffenders("String url = \"a//b\"; /* synchronized (this) */\n"));
        }

        @Test
        @DisplayName("catches a declaration following a closed block comment on the same line")
        void catchesAfterSameLineBlockComment() {
            assertEquals(List.of("1: /* note */ private synchronized void go() { }"),
                    findOffenders("/* note */ private synchronized void go() { }\n"));
            assertEquals(List.of("1: /* note */ synchronized (this) {"),
                    findOffenders("/* note */ synchronized (this) {\n"));
        }

        @Test
        @DisplayName("reports the line the offence is on")
        void reportsLineNumber() {
            assertEquals(List.of("3: synchronized (this) {"),
                    findOffenders("class A {\n  void go() {\n    synchronized (this) {\n"));
        }

        @Test
        @DisplayName("keeps line numbers straight past a text-block line continuation")
        void reportsLineNumberAfterLineContinuation() {
            assertEquals(List.of("5: synchronized (this) {"),
                    findOffenders("class A {\n"
                            + "  String s = \"\"\"\n"
                            + "      a \\\n"
                            + "      b\"\"\";\n"
                            + "  synchronized (this) {\n"));
        }
    }
}
