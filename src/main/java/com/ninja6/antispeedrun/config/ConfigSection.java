package com.ninja6.antispeedrun.config;

import java.util.Set;

/**
 * A read-only view of one mapping node in a configuration document.
 *
 * <p>This is the seam that keeps {@link PluginConfig} parseable without a running server.
 * Bukkit's {@code FileConfiguration} is {@code compileOnly} and is not on the test classpath, so
 * the model parses from this interface instead. {@link BukkitConfigSection} adapts the server's
 * configuration at runtime and {@link MapConfigSection} adapts any plain nested {@code Map}.
 *
 * <p>Implementations must be effectively immutable: nothing in this package retains a section
 * after parsing, but a section may be read from more than one thread while a snapshot is built.
 *
 * <h2>Keys are literal, never paths</h2>
 *
 * A key names exactly one child of this node. It is <strong>never</strong> split on {@code '.'} or
 * on any other separator, so {@code get("my.tier")} reads the child literally called
 * {@code my.tier} and returns {@code null} when the document instead nests {@code tier} inside a
 * section {@code my}. This is the canonical semantics for every implementation, because the keys
 * this parser reads include operator-chosen strings — a tier id under {@code gated-items} may
 * legitimately contain a dot — and path-splitting would silently turn one such tier into a
 * mis-parsed nested section plus an {@code unknown key} warning.
 *
 * <p>Bukkit's own accessors do split on {@code '.'}; {@link BukkitConfigSection} deliberately does
 * not use them. {@code ConfigSectionConformanceTest} runs one set of assertions against every
 * implementation so a future divergence fails the build rather than surfacing on a live server.
 */
public interface ConfigSection {

    /**
     * The keys declared directly on this node, in document order where the implementation can
     * preserve it. Never {@code null}.
     */
    Set<String> keys();

    /** Whether {@code key} is declared directly on this node. */
    default boolean contains(String key) {
        return keys().contains(key);
    }

    /**
     * The raw value for the child literally called {@code key}, or {@code null} when the key is
     * absent or explicitly null. Callers are expected to type-check; {@link ConfigReader} does that
     * centrally. A child that is itself a mapping reads back as a {@code Map}.
     */
    Object get(String key);

    /**
     * The child mapping literally called {@code key}, or {@code null} when the key is absent or
     * holds something that is not a mapping.
     */
    ConfigSection section(String key);
}
