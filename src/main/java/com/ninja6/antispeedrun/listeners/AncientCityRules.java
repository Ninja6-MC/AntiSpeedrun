package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.Set;

import org.bukkit.NamespacedKey;
import org.bukkit.util.BoundingBox;

/**
 * When a player's position proves they explored an Ancient City (#221), apart from Bukkit's
 * scheduler and world so each rule is testable. {@link AncientCityListener} applies them.
 *
 * <p>Vanilla proves a Bastion or an End City with a location trigger: every second it asks whether
 * the block the player stands in lies inside a piece of that structure. Vanilla has no such
 * advancement for an Ancient City, so the plugin asks the same question itself, less often and
 * only where a city can be.
 */
public final class AncientCityRules {

    /** Ticks between two looks at one player's position: about every 2 seconds. */
    public static final long PERIOD_TICKS = 40L;

    /**
     * The highest block y an Ancient City piece reaches in vanilla generation. A city starts at
     * y -27 and its pieces reach no higher than about y -10, all within the Deep Dark's depth, so
     * a player at y 0 or above is never inside one and costs no structure lookup.
     */
    public static final int DEEP_DARK_MAX_Y = -1;

    /** The loot tables of the chests an Ancient City generates with. */
    private static final Set<NamespacedKey> CITY_LOOT = Set.of(
            NamespacedKey.minecraft("chests/ancient_city"),
            NamespacedKey.minecraft("chests/ancient_city_ice_box"));

    private AncientCityRules() {
    }

    /**
     * Whether a player's position is worth a structure lookup: not yet recorded, in an overworld,
     * at Deep Dark depth, and not a spectator, who passes through walls and cannot be said to have
     * reached anything.
     *
     * @param recorded  whether the player already has the Ancient City recorded
     * @param overworld whether the player's world is an overworld ({@code Environment.NORMAL})
     * @param blockY    the y of the block the player stands in
     * @param spectator whether the player is in spectator mode
     */
    public static boolean worthLooking(boolean recorded, boolean overworld, int blockY, boolean spectator) {
        return !recorded && overworld && !spectator && blockY <= DEEP_DARK_MAX_Y;
    }

    /**
     * Whether a block lies inside any of the pieces' bounding boxes, both corners included, as
     * vanilla's own structure location check counts it. Paper reports a piece's box with its
     * maximum corner on the last block it covers, not one past it.
     */
    public static boolean inside(Iterable<BoundingBox> pieces, int x, int y, int z) {
        Objects.requireNonNull(pieces, "pieces");
        for (BoundingBox box : pieces) {
            if (x >= box.getMinX() && x <= box.getMaxX()
                    && y >= box.getMinY() && y <= box.getMaxY()
                    && z >= box.getMinZ() && z <= box.getMaxZ()) {
                return true;
            }
        }
        return false;
    }

    /** Whether generated loot came from an Ancient City chest's loot table. */
    public static boolean cityLoot(NamespacedKey table) {
        return table != null && CITY_LOOT.contains(table);
    }
}
