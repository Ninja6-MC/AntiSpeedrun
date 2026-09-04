package com.ninja6.antispeedrun.config;

/**
 * Thrown when a configuration document cannot be turned into a snapshot at all — an unreadable
 * file, a YAML syntax error, or a document whose root is not a mapping.
 *
 * <p>This is the named error required by the reload contract. It is never a reason to disable the
 * plugin: {@link ConfigSnapshotHolder#reload(ConfigSource)} catches it, logs it, and leaves the
 * previously loaded snapshot live.
 *
 * <p>It is deliberately narrow. A key that is missing falls back silently to its shipped default
 * and a key that is present with the wrong type falls back with a warning; neither aborts the
 * load, because refusing an entire file over one typo is worse than running one key on its
 * default.
 */
public class ConfigLoadException extends Exception {

    private static final long serialVersionUID = 1L;

    public ConfigLoadException(String message) {
        super(message);
    }

    public ConfigLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
