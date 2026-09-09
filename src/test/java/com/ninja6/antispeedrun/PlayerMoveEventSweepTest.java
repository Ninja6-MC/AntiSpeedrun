package com.ninja6.antispeedrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Guards #4's first acceptance criterion: <em>zero {@code PlayerMoveEvent} listeners registered in
 * the entire codebase</em>.
 *
 * <p>It is a whole-codebase rule rather than a rule about the idle reminder, so it is asserted the
 * way the {@code synchronized} convention is — by sweeping {@code src/main/java} — rather than by a
 * reviewer remembering. The event fires several times per player per tick, and the two features that
 * reached for it both wanted an answer a periodic position comparison gives just as well:
 * {@code IdleReminderEngine} polls the player's own {@code EntityScheduler}, and finding R-07 records
 * that Task 7.2.1 (#26) was amended to do the same rather than have this criterion concede.
 *
 * <p>The check bans the type name outright, not merely {@code @EventHandler} on it. A handler can be
 * registered without the annotation being adjacent to the name — a dynamic registration, a
 * {@code Bukkit.getPluginManager().registerEvent} call, a lambda — and a production source that
 * mentions the type at all is a source that has an opinion about it. Naming it in a comment, as this
 * test's own subject matter does throughout the plugin's javadoc, is exempt: what is being forbidden
 * is a reference, and explaining why the reference is absent is the opposite of one.
 */
class PlayerMoveEventSweepTest {

    private static final String FORBIDDEN = "PlayerMoveEvent";

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    @Test
    @DisplayName("no production source references PlayerMoveEvent")
    void noPlayerMoveEventInMainSources() throws IOException {
        assertTrue(Files.isDirectory(SOURCE_ROOT),
                "Expected to run with the project directory as the working directory, so that "
                        + SOURCE_ROOT + " resolves. It did not.");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String offence : findOffenders(Files.readString(source, StandardCharsets.UTF_8))) {
                    offenders.add(source + ":" + offence);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "PlayerMoveEvent is banned across this plugin (issue #4, finding R-07). It fires "
                        + "several times per player per tick, and everything that has wanted it here "
                        + "wanted to know whether a player had moved -- which a poll on the player's "
                        + "own EntityScheduler answers, at a fraction of the cost and without a "
                        + "handler on the hottest event the server raises. See IdleReminderEngine. "
                        + "Found:\n" + String.join("\n", offenders));
    }

    /**
     * Returns {@code line:text} for every line of {@code source} that references the forbidden type
     * outside a comment, in file order.
     *
     * <p>Comment detection is deliberately simple — a line whose first non-blank character begins a
     * {@code //} or {@code /*} comment, or continues a javadoc block with {@code *} — because that is
     * the only form the exemption needs to cover and a full scanner would be a second copy of the one
     * in {@code SynchronizedMethodSweepTest}. The failure mode is a false positive on a trailing
     * comment sharing a line with code, which is a line worth looking at anyway.
     */
    private static List<String> findOffenders(String source) {
        List<String> offenders = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.contains(FORBIDDEN) || isComment(trimmed)) {
                continue;
            }
            offenders.add((i + 1) + ": " + trimmed);
        }
        return offenders;
    }

    /** Whether a trimmed line opens or continues a comment rather than carrying code. */
    private static boolean isComment(String trimmed) {
        return trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*");
    }

    /**
     * A source-scanning test that can misread its own input gets disbelieved rather than fixed, so
     * the scanner is pinned down the same way the {@code synchronized} sweep pins down its own.
     */
    @Nested
    @DisplayName("the scanner")
    class ScannerTest {

        @Test
        @DisplayName("flags an import and a handler parameter")
        void flagsCode() {
            assertEquals(List.of("1: import org.bukkit.event.player.PlayerMoveEvent;"),
                    findOffenders("import org.bukkit.event.player.PlayerMoveEvent;\n"));
            assertEquals(List.of("2: public void onMove(PlayerMoveEvent event) {"),
                    findOffenders("@EventHandler\npublic void onMove(PlayerMoveEvent event) {\n"));
        }

        @Test
        @DisplayName("exempts a line comment, a block comment and a javadoc body")
        void exemptsComments() {
            assertEquals(List.of(), findOffenders("// no PlayerMoveEvent listener here\n"));
            assertEquals(List.of(), findOffenders("/* PlayerMoveEvent is banned */\n"));
            assertEquals(List.of(), findOffenders(" * There is no PlayerMoveEvent handler.\n"));
        }

        @Test
        @DisplayName("reports the line the reference is on")
        void reportsLineNumber() {
            assertEquals(List.of("3: PlayerMoveEvent e;"),
                    findOffenders("class A {\n  void go() {\n    PlayerMoveEvent e;\n"));
        }
    }
}
