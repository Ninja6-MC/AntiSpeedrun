package com.ninja6.antispeedrun.storage;

import java.util.Objects;
import java.util.OptionalLong;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Temporary gate bypasses granted with {@code /asr bypass}, stored on the player and carrying an
 * expiry (finding C-08).
 *
 * <h2>Why the player's PDC and not a map</h2>
 *
 * A grant has to outlive a restart, and it belongs to exactly one player. The server already writes
 * that player's {@code PersistentDataContainer} out with their playerdata, so this needs no file,
 * no flush and no eviction — and, because it is not a per-player map at all, there is nothing to
 * register with {@code PlayerStateRegistry} and nothing to leak on quit (finding R-08).
 *
 * <h2>This is not the {@code antispeedrun.bypass} permission</h2>
 *
 * The permission node is the standing, operator-assigned exemption, and {@code plugin.yml} keeps it
 * deliberately outside the {@code antispeedrun.admin} tree so that being an operator does not
 * exempt you from your own gates. A grant here is the other thing: a time-boxed exemption an admin
 * hands one player for one situation. A gate should treat a player as bypassing when
 * <em>either</em> holds, and {@code antispeedrun.admin.bypass} — the right to hand grants out —
 * confers neither.
 *
 * <h2>Threading</h2>
 *
 * Every method takes a live {@link Player} and reads or writes their PDC, which on Folia is owned
 * by their region. Call these from an event handler for that player or from a task on their
 * {@code EntityScheduler}; {@code AntiSpeedrunCommand} does the latter.
 */
public final class BypassStore {

    /** Expiry value meaning "until revoked". See {@link BypassGrant#PERMANENT}. */
    public static final long PERMANENT = BypassGrant.PERMANENT;

    private final NamespacedKey expiresAt;

    public BypassStore(Plugin plugin) {
        this.expiresAt = new NamespacedKey(Objects.requireNonNull(plugin, "plugin"), "bypass-expires-at");
    }

    /** The stored expiry for {@code player}, whether or not it has elapsed. */
    public OptionalLong expiry(Player player) {
        Long stored = Objects.requireNonNull(player, "player").getPersistentDataContainer()
                .get(expiresAt, PersistentDataType.LONG);
        return stored == null ? OptionalLong.empty() : OptionalLong.of(stored);
    }

    /**
     * Whether {@code player} currently holds a granted bypass.
     *
     * <p>An elapsed grant is cleared from the container as it is read, so a player who was granted
     * ten minutes a year ago does not carry the tag forever. That write is why this method, and not
     * only {@link #grant}, must run on the player's own thread.
     */
    public boolean hasBypass(Player player, long nowMillis) {
        OptionalLong stored = expiry(player);
        if (stored.isEmpty()) {
            return false;
        }
        if (BypassGrant.isActive(stored.getAsLong(), nowMillis)) {
            return true;
        }
        revoke(player);
        return false;
    }

    /**
     * Grants {@code player} a bypass until {@code expiresAtMillis}, replacing any grant they hold.
     *
     * @param expiresAtMillis wall-clock expiry, or {@link #PERMANENT}
     */
    public void grant(Player player, long expiresAtMillis) {
        Objects.requireNonNull(player, "player").getPersistentDataContainer()
                .set(expiresAt, PersistentDataType.LONG, expiresAtMillis);
    }

    /**
     * Removes any grant {@code player} holds.
     *
     * @return {@code true} if one was removed
     */
    public boolean revoke(Player player) {
        var container = Objects.requireNonNull(player, "player").getPersistentDataContainer();
        if (!container.has(expiresAt, PersistentDataType.LONG)) {
            return false;
        }
        container.remove(expiresAt);
        return true;
    }
}
