package com.ninja6.antispeedrun.progression;

import org.bukkit.entity.Player;

/**
 * Resolves one configured advancement key against one player.
 *
 * <p>An interface rather than three inline calls, because the interesting behaviour is the failure
 * path: {@code NamespacedKey.fromString} returns {@code null} for a malformed key and
 * {@code Bukkit.getAdvancement} returns {@code null} for one no datapack defines. Both are
 * configuration or server-setup problems, not player state, and conflating either with "not earned"
 * seals a gate that no amount of play can open.
 *
 * <p>Implementations must be safe to call from any region thread <em>for the player they are
 * passed</em>; {@link BukkitAdvancementLookup} reads that player's advancement progress, which is
 * region-owned data.
 */
@FunctionalInterface
public interface AdvancementLookup {

    /** What a configured advancement key turned out to be for a given player. */
    enum State {
        /** The advancement exists and the player has completed it. */
        EARNED,
        /** The advancement exists and the player has not completed it. */
        NOT_EARNED,
        /**
         * The key resolves to no advancement on this server — malformed, or removed by a datapack,
         * or the server runs without vanilla advancements. Unsatisfiable by play.
         */
        UNRESOLVABLE
    }

    /**
     * @param player the player to test; must be owned by the calling thread's region
     * @param key    a namespaced advancement key exactly as it appears in {@code config.yml}
     */
    State state(Player player, String key);
}
