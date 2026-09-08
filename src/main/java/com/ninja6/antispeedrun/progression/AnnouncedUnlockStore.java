package com.ninja6.antispeedrun.progression;

import java.util.Optional;
import java.util.Set;

import org.bukkit.entity.Player;

/**
 * Where the "this player has been told about these gates" record lives across sessions.
 *
 * <p>An interface rather than a concrete store so {@link ProgressionManager} does not have to know
 * about {@code com.ninja6.antispeedrun.storage}, and so a manager built without one — every test
 * and any future embedding — behaves exactly as it did before #84: silent on join, announcing only
 * what happens while the player is watching.
 *
 * <h2>Threading</h2>
 *
 * Both methods take a live {@link Player} and the production implementation reads and writes their
 * {@code PersistentDataContainer}, which on Folia is owned by their region. Call them from an event
 * handler for that player or from a task on their {@code EntityScheduler} — the same rule every
 * other {@link Player}-taking method in this package carries.
 */
public interface AnnouncedUnlockStore {

    /**
     * A store that keeps nothing, for a manager wired without persistence.
     *
     * <p>{@link #load} returns empty, which {@link AnnouncedUnlocks#onJoin} reads as "no record" and
     * therefore announces nothing — the pre-#84 behaviour, deliberately, rather than an empty record
     * that would congratulate every player on every join.
     */
    AnnouncedUnlockStore NONE = new AnnouncedUnlockStore() {

        @Override
        public Optional<Set<String>> load(Player player) {
            return Optional.empty();
        }

        @Override
        public void save(Player player, Set<String> milestoneIds) {
            // Nothing to save to.
        }
    };

    /**
     * The milestone ids this player has already been announced.
     *
     * @return the record, or empty when the player has none at all. The two are not the same thing:
     *         see {@link AnnouncedUnlocks}
     */
    Optional<Set<String>> load(Player player);

    /** Records {@code milestoneIds} as the full set this player has been told about. */
    void save(Player player, Set<String> milestoneIds);
}
