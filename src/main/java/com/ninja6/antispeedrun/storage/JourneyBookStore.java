package com.ninja6.antispeedrun.storage;

import java.util.Objects;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Whether a player has already been handed the Journey Guide Book.
 *
 * <h2>What this replaces, and why</h2>
 *
 * The obvious implementation of {@code give-on-first-join} is {@code !player.hasPlayedBefore()},
 * and finding C-08 is that it is wrong in the one case that matters most: it is {@code false} for
 * every player who joined before the plugin was installed. An established server enables the
 * feature and its entire existing population never receives the book — silently, because nothing
 * distinguishes "already had it" from "never eligible". A flag the plugin sets itself has no such
 * blind spot: absent means not yet delivered, whoever the player is and whenever they first joined.
 *
 * <h2>Not yet consumed — and that is deliberate, not an oversight</h2>
 *
 * There is no journey-book feature on {@code main} yet: {@code PluginConfig.JourneyBook} and the
 * {@code journeybook} command declaration exist, and nothing else does. This class is the persisted
 * flag that task needs, published ahead of it so the join listener is written against a store that
 * already exists rather than inventing a second one. Until that listener lands, nothing calls
 * {@link #markDelivered} and no player receives a book — the acceptance criterion in #57 about
 * existing players is only half met, and the pull request says so.
 *
 * <h2>Threading</h2>
 *
 * As {@link BypassStore}: the player's PDC is region-owned, so call these from a handler for that
 * player or from a task on their {@code EntityScheduler}.
 */
public final class JourneyBookStore {

    private final NamespacedKey delivered;

    public JourneyBookStore(Plugin plugin) {
        this.delivered = new NamespacedKey(Objects.requireNonNull(plugin, "plugin"), "journey-book-delivered");
    }

    /** Whether this player has already been given the book by this plugin. */
    public boolean hasReceived(Player player) {
        Byte flag = Objects.requireNonNull(player, "player").getPersistentDataContainer()
                .get(delivered, PersistentDataType.BYTE);
        return flag != null && flag != 0;
    }

    /** Records that the book has been delivered. Idempotent. */
    public void markDelivered(Player player) {
        Objects.requireNonNull(player, "player").getPersistentDataContainer()
                .set(delivered, PersistentDataType.BYTE, (byte) 1);
    }

    /**
     * Clears the flag, so the player is eligible again.
     *
     * <p>For an operator re-issuing the book after a rewrite, and for tests.
     *
     * @return {@code true} if a flag was cleared
     */
    public boolean clear(Player player) {
        var container = Objects.requireNonNull(player, "player").getPersistentDataContainer();
        if (!container.has(delivered, PersistentDataType.BYTE)) {
            return false;
        }
        container.remove(delivered);
        return true;
    }
}
