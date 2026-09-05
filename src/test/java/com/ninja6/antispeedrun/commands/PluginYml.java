package com.ninja6.antispeedrun.commands;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code plugin.yml}, read off the test classpath and queried.
 *
 * <p>The point of reading the real file rather than transcribing it: a test that compares one
 * hardcoded list against another cannot detect drift in the thing it names (#76). {@code plugin.yml}
 * is on the test classpath because it is a main resource, and SnakeYAML is already a test dependency
 * — the same technique {@code PresetProfileTest} uses on the shipped presets, for the same reason
 * (Bukkit is not on the test classpath, so its own YAML reader is out of reach).
 *
 * <p>Parsed once and held, since nothing here mutates it. Note that the copy on the classpath is
 * the <em>processed</em> resource, so {@code ${version}} has already been expanded by
 * {@code processResources}.
 */
final class PluginYml {

    private static final Map<?, ?> ROOT = load();

    private PluginYml() {
    }

    private static Map<?, ?> load() {
        try (InputStream in = PluginYml.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(in, "src/main/resources/plugin.yml must be on the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Object parsed = new Yaml().load(reader);
                assertTrue(parsed instanceof Map<?, ?>, "plugin.yml root must be a mapping");
                return (Map<?, ?>) parsed;
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("plugin.yml could not be read", failure);
        }
    }

    private static Map<?, ?> section(String key) {
        Object value = ROOT.get(key);
        assertTrue(value instanceof Map<?, ?>, "plugin.yml must declare a \"" + key + "\" mapping");
        return (Map<?, ?>) value;
    }

    /** Every permission node {@code plugin.yml} declares, in declaration order. */
    static Set<String> declaredPermissions() {
        Set<String> nodes = new LinkedHashSet<>();
        for (Object key : section("permissions").keySet()) {
            nodes.add(String.valueOf(key));
        }
        return nodes;
    }

    /**
     * The nodes {@code node} grants directly.
     *
     * <p>Bukkit's {@code Permission.loadPermissions} accepts two shapes under {@code children:}, and
     * both are handled here because a reader that understood only one would answer "grants nothing"
     * for the other and quietly weaken every assertion built on it:
     *
     * <ul>
     *   <li>a <strong>boolean</strong>, where {@code true} grants the child and {@code false} is an
     *       explicit denial that grants nothing, so it is not something the parent makes
     *       reachable;</li>
     *   <li>a <strong>map</strong>, which Bukkit reads as an inline definition of that child and
     *       then grants it exactly as if {@code true} had been written. This is the shape someone
     *       would reach for to nest {@code antispeedrun.bypass} under {@code antispeedrun.admin}
     *       while writing its description in place — precisely what
     *       {@code bypassPermissionIsNotAnAdminNode} exists to stop.</li>
     * </ul>
     *
     * <p>Anything else is a shape this reader does not model, and it fails rather than being
     * skipped: silently returning nothing for an unrecognised child is how a drift detector stops
     * detecting drift without anyone noticing.
     */
    static Set<String> childrenOf(String node) {
        Object declaration = section("permissions").get(node);
        if (!(declaration instanceof Map<?, ?> body)) {
            return Set.of();
        }
        if (!(body.get("children") instanceof Map<?, ?> children)) {
            return Set.of();
        }
        Set<String> granted = new LinkedHashSet<>();
        children.forEach((child, grants) -> {
            String name = String.valueOf(child);
            if (grants instanceof Map<?, ?> || Boolean.TRUE.equals(grants)) {
                granted.add(name);
            } else {
                assertTrue(Boolean.FALSE.equals(grants),
                        "the child \"" + name + "\" of \"" + node + "\" is declared as "
                                + (grants == null ? "null" : grants.getClass().getSimpleName())
                                + ", which this reader does not model. Bukkit accepts a boolean or "
                                + "an inline map; add the shape here rather than letting it be "
                                + "skipped.");
            }
        });
        return granted;
    }

    /**
     * Every node {@code node} grants, transitively.
     *
     * <p>Transitive rather than direct because Bukkit resolves the whole tree: a node three levels
     * below {@code antispeedrun.admin} is granted to every operator just as surely as one directly
     * beneath it. {@code node} itself is not included.
     */
    static Set<String> reachableFrom(String node) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(childrenOf(node));
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            if (seen.add(next)) {
                pending.addAll(childrenOf(next));
            }
        }
        return seen;
    }

    /** The {@code usage:} line declared for a command. */
    static String usageOf(String command) {
        Object declaration = section("commands").get(command);
        assertTrue(declaration instanceof Map<?, ?>,
                "plugin.yml must declare the \"" + command + "\" command");
        Object usage = ((Map<?, ?>) declaration).get("usage");
        assertNotNull(usage, "plugin.yml must declare a usage line for \"" + command + "\"");
        return String.valueOf(usage);
    }

    /**
     * Every operator-facing string in the file that could name a subcommand, keyed by where it was
     * read from so a failure says which line to fix.
     *
     * <p>Both sections, not just {@code permissions:}. A {@code description:} or {@code usage:}
     * under {@code commands:} is read by an operator in exactly the same way — {@code /help} prints
     * it — so it makes the same promise and can go stale the same way.
     */
    static Map<String, String> advertisements() {
        Map<String, String> texts = new LinkedHashMap<>();
        collect(texts, "commands", "description", "usage");
        collect(texts, "permissions", "description");
        // A node with no description is not merely undocumented, it is invisible to the scan
        // below: a missing key cannot advertise a stale subcommand, so the assertion built on
        // this map would pass by having nothing to read.
        for (String node : declaredPermissions()) {
            assertTrue(texts.containsKey("permissions." + node + ".description"),
                    node + " must carry a description");
        }
        return texts;
    }

    private static void collect(Map<String, String> into, String sectionName, String... keys) {
        section(sectionName).forEach((name, declaration) -> {
            if (!(declaration instanceof Map<?, ?> body)) {
                return;
            }
            for (String key : keys) {
                Object text = body.get(key);
                if (text != null) {
                    into.put(sectionName + "." + name + "." + key, String.valueOf(text));
                }
            }
        });
    }

}
