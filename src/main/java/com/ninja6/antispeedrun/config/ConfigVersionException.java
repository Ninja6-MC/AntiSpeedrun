package com.ninja6.antispeedrun.config;

/**
 * {@code config.yml} declares a {@code config-version} this build cannot read: newer than it
 * knows, or not a whole number of at least 1.
 *
 * <p>Its own type, rather than a plain {@link ConfigLoadException}, because the startup policy
 * treats it differently. A file this build cannot parse says nothing and the defaults are a fair
 * place to land; a file written by a newer build describes gating this build may not know how to
 * enforce, so landing on the defaults would turn it off. It fails closed instead.
 */
public final class ConfigVersionException extends ConfigLoadException {

    private static final long serialVersionUID = 1L;

    public ConfigVersionException(String message) {
        super(message);
    }
}
