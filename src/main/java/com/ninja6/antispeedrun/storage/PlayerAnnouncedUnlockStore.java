package com.ninja6.antispeedrun.storage;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import com.ninja6.antispeedrun.progression.AnnouncedUnlocks;
import com.ninja6.antispeedrun.progression.AnnouncedUnlockStore;

/**
 * The durable "already congratulated" record, kept on the player (issue #84).
 *
 * <p>Third of the per-player flags #57 puts in the {@code PersistentDataContainer}, and it is there
 * for the reasons {@link BypassStore} gives: the record belongs to exactly one player, the server
 * already writes their PDC out with the rest of their playerdata, and because it is not a per-player
 * map there is nothing to register with {@code PlayerStateRegistry} and nothing to leak on quit
 * (finding R-08).
 *
 * <p>The value is one string rather than a container of its own. There are two milestone ids today
 * and item tiers will add a handful more; a delimited string is the shape the PDC is cheapest at,
 * and {@link AnnouncedUnlocks#encode} and {@link AnnouncedUnlocks#decode} keep the parsing where it
 * can be tested without a server.
 *
 * <h2>Absent is not empty</h2>
 *
 * {@link #load} returns empty only when the key is genuinely missing — a player this plugin has
 * never recorded. A player who has been recorded as having been told about nothing stores the empty
 * string and reads back as a present, empty set. {@link AnnouncedUnlocks} turns on that distinction:
 * it is what stops a rollout on an established server from congratulating everyone at once.
 *
 * <h2>Threading</h2>
 *
 * As {@link BypassStore}: the player's PDC is region-owned, so call these from a handler for that
 * player or from a task on their {@code EntityScheduler}.
 */
public final class PlayerAnnouncedUnlockStore implements AnnouncedUnlockStore {

    private final NamespacedKey announced;

    public PlayerAnnouncedUnlockStore(Plugin plugin) {
        this.announced =
                new NamespacedKey(Objects.requireNonNull(plugin, "plugin"), "announced-milestones");
    }

    @Override
    public Optional<Set<String>> load(Player player) {
        String stored = Objects.requireNonNull(player, "player").getPersistentDataContainer()
                .get(announced, PersistentDataType.STRING);
        return AnnouncedUnlocks.decode(stored);
    }

    @Override
    public void save(Player player, Set<String> milestoneIds) {
        Objects.requireNonNull(player, "player").getPersistentDataContainer()
                .set(announced, PersistentDataType.STRING,
                        AnnouncedUnlocks.encode(Objects.requireNonNull(milestoneIds, "milestoneIds")));
    }

    /**
     * Clears the record, so the next join primes silently again rather than announcing a difference.
     *
     * <p>For an operator resetting a player, and for tests.
     *
     * @return {@code true} if a record was cleared
     */
    public boolean clear(Player player) {
        var container = Objects.requireNonNull(player, "player").getPersistentDataContainer();
        if (!container.has(announced, PersistentDataType.STRING)) {
            return false;
        }
        container.remove(announced);
        return true;
    }
}
