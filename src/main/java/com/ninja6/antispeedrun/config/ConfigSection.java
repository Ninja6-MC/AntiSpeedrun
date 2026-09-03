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
     * The raw value for {@code key}, or {@code null} when the key is absent or explicitly null.
     * Callers are expected to type-check; {@link ConfigReader} does that centrally.
     */
    Object get(String key);

    /**
     * The child mapping at {@code key}, or {@code null} when the key is absent or holds something
     * that is not a mapping.
     */
    ConfigSection section(String key);
}
