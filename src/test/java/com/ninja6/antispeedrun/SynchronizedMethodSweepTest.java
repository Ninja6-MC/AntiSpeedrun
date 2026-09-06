package com.ninja6.antispeedrun;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the convention that no production type declares a {@code synchronized} <em>method</em>.
 *
 * <p>A {@code synchronized} method takes the receiver's own monitor. For a type any other plugin on
 * the server can reach — a {@code JavaPlugin} through {@code Bukkit.getPluginManager().getPlugin},
 * anything handed out by an accessor, or a listener the plugin manager holds — that monitor is
 * public: a third party can hold it across an arbitrary operation, or take it in an order that
 * deadlocks against a lock this plugin also uses. Every place in this plugin that needs mutual
 * exclusion therefore declares a private lock object and synchronizes a block on it
 * ({@code ConfigSnapshotHolder.swapLock}, {@code ProfileApplier.APPLY_LOCK},
 * {@code DimensionUnlockStore.stateLock}, {@code AntiSpeedrunPlugin.configLock}).
 *
 * <p>The check reads source rather than bytecode on purpose. {@code paper-api} and Adventure are
 * {@code compileOnly}, so reflecting over the production classes would resolve signatures whose
 * types are not on the test classpath; and the thing being asserted is a source-level convention
 * that should fail on the line that breaks it.
 *
 * <p>{@code synchronized} <em>blocks</em> are what this convention asks for, so they pass. Nothing
 * here checks that the object a block locks is private — that is a judgement about which the
 * regular review is the right instrument.
 */
class SynchronizedMethodSweepTest {

    /** {@code synchronized} not immediately followed by a {@code (}, i.e. a method modifier. */
    private static final Pattern SYNCHRONIZED_METHOD = Pattern.compile("\\bsynchronized\\b(?!\\s*\\()");

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    @Test
    @DisplayName("no production type declares a synchronized method")
    void noSynchronizedMethodsInMainSources() throws IOException {
        assertTrue(Files.isDirectory(SOURCE_ROOT),
                "Expected to run with the project directory as the working directory, so that "
                        + SOURCE_ROOT + " resolves. It did not.");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                collectOffenders(source, offenders);
            }
        }

        assertTrue(offenders.isEmpty(),
                "A synchronized method takes the declaring instance's own monitor, which is public "
                        + "for any type external code holds a reference to. Declare a private lock "
                        + "object and synchronize a block on it instead. Found:\n"
                        + String.join("\n", offenders));
    }

    private static void collectOffenders(Path source, List<String> offenders) throws IOException {
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        boolean inBlockComment = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();

            if (inBlockComment) {
                if (trimmed.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (trimmed.startsWith("/*")) {
                inBlockComment = !trimmed.contains("*/");
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }

            Matcher matcher = SYNCHRONIZED_METHOD.matcher(stripTrailingComment(line));
            if (matcher.find()) {
                offenders.add(source + ":" + (i + 1) + ": " + trimmed);
            }
        }
    }

    private static String stripTrailingComment(String line) {
        int comment = line.indexOf("//");
        return comment < 0 ? line : line.substring(0, comment);
    }
}
