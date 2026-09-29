package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The secondary dragons one End world is known to hold alive (#56).
 *
 * <h2>Folia</h2>
 *
 * Secondaries fly over the whole main island, which Folia may split across several regions, and
 * the primary's death is decided on whichever region the primary is in. No thread may read another
 * region's entities, so the roster is not a scan: each secondary reports itself, on its own region,
 * when it is spawned or its chunk loads, and again when it dies or is removed. The set is concurrent
 * so every one of those threads may write it and the primary's region may count it.
 *
 * <h2>What "alive" means across a restart</h2>
 *
 * The roster is memory only and starts empty. A secondary joins it when its chunk loads, which for
 * a fight in progress is as soon as a player is back on the island. One whose chunk has not loaded
 * since the restart is not counted, so a secondary lost to a crash between its spawn and the next
 * chunk save can never hold the primary alive forever. An unload does not remove a secondary: it is
 * still saved in the world.
 */
public final class SecondaryRoster {

    private final Set<UUID> living = ConcurrentHashMap.newKeySet();

    /**
     * Records a secondary as alive.
     *
     * @return {@code true} if it was not already recorded
     */
    public boolean track(UUID dragon) {
        return living.add(Objects.requireNonNull(dragon, "dragon"));
    }

    /**
     * Records a secondary just spawned, but only if it actually entered the world. A spawn another
     * plugin cancelled still hands back an entity, and counting it would hold the primary alive until
     * a restart.
     *
     * @param addedToWorld {@code Entity#isValid()} after the spawn
     * @return {@code true} if the secondary is now counted
     */
    public boolean trackSpawned(UUID dragon, boolean addedToWorld) {
        Objects.requireNonNull(dragon, "dragon");
        if (!addedToWorld) {
            return false;
        }
        track(dragon);
        return true;
    }

    /**
     * Records a secondary as gone.
     *
     * @return {@code true} if it had been recorded
     */
    public boolean forget(UUID dragon) {
        return living.remove(Objects.requireNonNull(dragon, "dragon"));
    }

    /** How many secondaries are alive. */
    public int living() {
        return living.size();
    }
}
