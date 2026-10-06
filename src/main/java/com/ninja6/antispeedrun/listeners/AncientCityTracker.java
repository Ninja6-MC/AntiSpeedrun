package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.util.BoundingBox;

import com.ninja6.antispeedrun.progression.TrimProgressionManager;
import com.ninja6.antispeedrun.storage.ExploredStructureStore;

/**
 * Writes the Ancient City exploration record (#221) from what {@link AncientCityListener} sees,
 * applying {@link AncientCityRules}. Kept apart from Bukkit's scheduler and world so the decisions
 * and the store writes can be tested together.
 *
 * <p>The record is only ever set, never cleared: nothing a player does undoes having been inside a
 * city, and the trim locks read it on every evaluation.
 */
public final class AncientCityTracker {

    /** The Ancient City pieces referenced by the chunk holding a block, by block x and z. */
    @FunctionalInterface
    public interface PieceLookup {
        Iterable<BoundingBox> pieces(int blockX, int blockZ);
    }

    private final ExploredStructureStore explored;

    public AncientCityTracker(ExploredStructureStore explored) {
        this.explored = Objects.requireNonNull(explored, "explored");
    }

    /**
     * Looks at one player's position and records the Ancient City if they stand in one of its
     * pieces. The lookup is asked only when {@link AncientCityRules#worthLooking} says so.
     *
     * @return whether the record changed
     */
    public boolean observe(UUID player, boolean overworld, boolean spectator, int x, int y, int z,
                           PieceLookup lookup) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(lookup, "lookup");
        String city = TrimProgressionManager.ANCIENT_CITY.id();
        if (!AncientCityRules.worthLooking(explored.hasExplored(player, city), overworld, y, spectator)) {
            return false;
        }
        if (!AncientCityRules.inside(lookup.pieces(x, z), x, y, z)) {
            return false;
        }
        return explored.record(player, city, true);
    }

    /**
     * Records that a player generated an Ancient City chest's loot. Recorded whatever
     * {@code count-structure-loot} says, under its own id, so that the toggle decides at lookup.
     *
     * @param creditable whether the looter may earn anything at all: a player, not an NPC
     * @return whether the record changed
     */
    public boolean looted(UUID player, NamespacedKey table, boolean creditable) {
        Objects.requireNonNull(player, "player");
        if (!creditable || !AncientCityRules.cityLoot(table)) {
            return false;
        }
        return explored.record(player, TrimProgressionManager.ANCIENT_CITY_LOOT_ID, true);
    }
}
