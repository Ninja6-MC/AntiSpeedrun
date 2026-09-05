package com.ninja6.antispeedrun.commands;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
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
     * <p>Only children mapped to {@code true}: a child mapped to {@code false} is an explicit
     * denial and grants nothing, so it is not something the parent makes reachable.
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
            if (Boolean.TRUE.equals(grants)) {
                granted.add(String.valueOf(child));
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

    /** The {@code description:} declared for a permission node. */
    static String descriptionOf(String node) {
        Object declaration = section("permissions").get(node);
        assertTrue(declaration instanceof Map<?, ?>,
                "plugin.yml must declare the \"" + node + "\" permission");
        Object description = ((Map<?, ?>) declaration).get("description");
        assertNotNull(description, node + " must carry a description");
        return String.valueOf(description);
    }
}
