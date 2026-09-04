package com.ninja6.antispeedrun.storage;

/**
 * What a stored bypass expiry means, with no Bukkit anywhere near it.
 *
 * <p>Split out of {@link BypassStore} deliberately. {@code paper-api} is {@code compileOnly}, so a
 * class that mentions {@code Player} or {@code NamespacedKey} cannot be referenced from a test at
 * all — and the one part of a bypass grant that has real semantics worth asserting is precisely
 * this: when it has elapsed. Keeping the predicate here means the boundary is tested rather than
 * assumed, and {@link BypassStore} is left as a thin container read and write.
 */
public final class BypassGrant {

    /** Expiry meaning "until revoked". */
    public static final long PERMANENT = Long.MAX_VALUE;

    private BypassGrant() {
    }

    /**
     * Whether a grant is in force.
     *
     * <p>Strictly greater than: a grant whose expiry is exactly now has elapsed. Anything else
     * would make a zero-length grant briefly real.
     *
     * @param expiresAtMillis a stored expiry, or {@link #PERMANENT}
     * @param nowMillis       wall-clock now
     */
    public static boolean isActive(long expiresAtMillis, long nowMillis) {
        return expiresAtMillis == PERMANENT || expiresAtMillis > nowMillis;
    }
}
