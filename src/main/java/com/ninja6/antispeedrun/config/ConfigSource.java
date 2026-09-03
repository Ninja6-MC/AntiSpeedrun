package com.ninja6.antispeedrun.config;

/**
 * Supplies the root section of a configuration document, re-reading it from disk each time it is
 * called.
 *
 * <p>Separating "where the document comes from" from "what the document means" is what makes the
 * reload path testable: production passes a source that reads {@code config.yml} through Bukkit's
 * YAML parser, and a test passes one that reads a string, or one that always fails.
 */
@FunctionalInterface
public interface ConfigSource {

    /**
     * Reads and parses the document.
     *
     * @throws ConfigLoadException if the document cannot be read or parsed at all
     */
    ConfigSection load() throws ConfigLoadException;
}
