package com.ninja6.antispeedrun.storage;

import java.io.IOException;

/**
 * A state file declares a {@code state-version} this build cannot read: newer than it knows, or
 * not a whole number of at least 1.
 *
 * <p>Its own type, rather than a plain {@link IOException}, because it is handled differently from
 * damage. A damaged file is moved aside on the next write and the store starts empty; a file
 * written by a newer build is intact data this build does not understand, so it is never moved or
 * overwritten and startup stops instead, as it does for {@code config.yml}'s
 * {@code ConfigVersionException}.
 */
public final class StateVersionException extends IOException {

    private static final long serialVersionUID = 1L;

    public StateVersionException(String message) {
        super(message);
    }
}
