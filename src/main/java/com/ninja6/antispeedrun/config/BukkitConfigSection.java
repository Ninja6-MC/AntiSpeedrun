package com.ninja6.antispeedrun.config;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Adapts a Bukkit {@link ConfigurationSection} to {@link ConfigSection}.
 *
 * <p>This is the only class in the package that touches the server API, and it is deliberately
 * trivial: it declares no defaults, performs no coercion and holds no state beyond a snapshot of
 * the wrapped section's direct children, so nothing that needs testing lives behind the
 * {@code compileOnly} dependency.
 *
 * <p><strong>How far the snapshot goes.</strong> One level, and no further. The map taken at
 * construction is the caller's own, copied and unmodifiable, but its <em>values</em> are the live
 * Bukkit children, so {@link #get(String)} and {@link #section(String)} read the wrapped tree one
 * level down at call time. That satisfies the effective immutability {@link ConfigSection} asks for
 * only because nothing in this package retains a {@code YamlConfiguration} past parsing and nothing
 * mutates one: {@link #load(File)} parses into a configuration no one else holds a reference to. A
 * caller that wrapped a section some other thread was still writing to would not get a stable view,
 * and this class does not try to give it one.
 *
 * <p><strong>Keys are literal.</strong> Bukkit's own accessors treat a key as a <em>path</em> and
 * split it on {@code '.'}, so {@code get("my.tier")} would look for a child section {@code my} and
 * read {@code tier} out of it. {@link ConfigSection} does not work that way: a key names one child
 * of this node and nothing else, which is what {@link MapConfigSection} has always done and what
 * the parser needs, because tier ids under {@code gated-items} are operator-chosen strings and
 * {@code my.tier} is a legal one. This adapter therefore reads {@code getValues(false)} once at
 * construction and answers every lookup from that map, never through a path-splitting accessor.
 * {@code ConfigSectionConformanceTest} runs one set of assertions against both implementations so
 * the two cannot drift apart again.
 *
 * <p>Nested mappings are normalised to plain {@code Map}s on the way out of {@link #get(String)},
 * so a caller that type-checks a raw value sees the same shape from either implementation.
 */
public final class BukkitConfigSection implements ConfigSection {

    /**
     * The path separator this package loads YAML with. {@code '\0'} cannot occur in a YAML key, so
     * Bukkit's path splitting is disabled outright rather than merely moved to a rarer character.
     */
    private static final char NO_PATH_SEPARATOR = '\0';

    private final Map<String, Object> values;

    /**
     * Wraps an already-loaded section.
     *
     * <p>Reading through this adapter is literal, but it can only be as literal as the document it
     * is handed: Bukkit splits dotted keys on the way <em>in</em> too, when
     * {@code YamlConfiguration.load} turns the parsed map into sections, and that has already
     * happened by the time this constructor runs. Use {@link #load(File)} to read {@code config.yml}
     * — it disables the splitting before parsing, so a dotted key survives as one key.
     */
    public BukkitConfigSection(ConfigurationSection section) {
        Objects.requireNonNull(section, "section");
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(section.getValues(false)));
    }

    /**
     * Reads a YAML document with literal keys, as {@link ConfigSection} requires.
     *
     * <p>Two things are deliberate here and neither is available from {@code JavaPlugin#getConfig()}:
     *
     * <ul>
     *   <li>The path separator is disabled <em>before</em> parsing. Bukkit's loader calls
     *       {@code createSection}/{@code set} for every key in the document, both of which split on
     *       {@code '.'}, so a tier id like {@code my.tier} would otherwise be silently restructured
     *       into a section {@code my} holding {@code tier} — a mis-parsed tier plus an
     *       {@code unknown key} warning, on a server, for a configuration that is perfectly legal
     *       and parses correctly everywhere else.</li>
     *   <li>A parse failure is a {@link ConfigLoadException} rather than an empty configuration, so
     *       the previous snapshot can be kept instead of every key silently resetting to its
     *       default.</li>
     * </ul>
     */
    public static ConfigSection load(File file) throws ConfigLoadException {
        Objects.requireNonNull(file, "file");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator(NO_PATH_SEPARATOR);
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException failure) {
            throw new ConfigLoadException(
                    file.getPath() + " could not be parsed: " + failure.getMessage(), failure);
        }
        return new BukkitConfigSection(yaml);
    }

    private BukkitConfigSection(Map<String, Object> values) {
        this.values = values;
    }

    @Override
    public Set<String> keys() {
        return values.keySet();
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public Object get(String key) {
        Object raw = values.get(key);
        return raw instanceof ConfigurationSection child ? copyOf(child) : raw;
    }

    @Override
    public ConfigSection section(String key) {
        Object raw = values.get(key);
        if (raw instanceof ConfigurationSection child) {
            return new BukkitConfigSection(child);
        }
        if (raw instanceof Map<?, ?> child) {
            return new BukkitConfigSection(copyOf(child));
        }
        return null;
    }

    /** A defensive, order-preserving copy of a mapping, with keys stringified. */
    private static Map<String, Object> copyOf(ConfigurationSection section) {
        return copyOf(section.getValues(false));
    }

    private static Map<String, Object> copyOf(Map<?, ?> raw) {
        Map<String, Object> copy = new LinkedHashMap<>(raw.size());
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}
