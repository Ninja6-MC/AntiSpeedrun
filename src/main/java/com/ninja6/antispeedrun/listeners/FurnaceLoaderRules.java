package com.ninja6.antispeedrun.listeners;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryAction;

/**
 * The furnace loader stamp of the smelted-iron credit (#214), apart from Bukkit's events so each
 * step is testable. The definition is the Amendment of {@code docs/provenance-model.md}: an iron
 * ingot that finishes smelting credits the player the furnace's stamp names, and only a hand insert
 * of iron-bearing input stamps a furnace.
 *
 * <p>A stamp is {@code [loader, remaining]}. Hand-loading {@code n} items makes the stamp the
 * loader's: {@code remaining} grows by {@code n} if it already named them and becomes {@code n} if
 * it named someone else. Each iron ingot smelted takes one from {@code remaining} and credits the
 * loader only while it was above 0. {@code remaining} never exceeds the iron left in the input
 * slot, so iron the loader took back out cannot be replaced by someone else's.
 */
public final class FurnaceLoaderRules {

    /** The input that smelts to an iron ingot and that a hand insert stamps. */
    private static final Set<Material> IRON_BEARING = EnumSet.of(
            Material.RAW_IRON, Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE);

    /** The blocks that can hold a stamp. A smoker cannot smelt iron. */
    private static final Set<Material> FURNACES = EnumSet.of(
            Material.FURNACE, Material.BLAST_FURNACE);

    /** The length of the encoded stamp: the UUID's two halves, then {@code remaining}. */
    static final int ENCODED_LENGTH = 3;

    private FurnaceLoaderRules() {
    }

    /** Whether {@code material} is input a hand insert stamps the furnace for. */
    public static boolean ironBearing(Material material) {
        return material != null && IRON_BEARING.contains(material);
    }

    /** Whether a block of {@code material} keeps a loader stamp. */
    public static boolean furnace(Material material) {
        return material != null && FURNACES.contains(material);
    }

    /** Whether a smelt producing {@code result} is one the stamp counts. */
    public static boolean smeltsIron(Material result) {
        return result == Material.IRON_INGOT;
    }

    /**
     * The loader stamp of one furnace.
     *
     * @param loader    the player who hand-loaded it
     * @param remaining how many more iron ingots credit {@code loader}; always above 0, since a
     *                  spent stamp is removed rather than kept
     */
    public record Stamp(UUID loader, int remaining) {

        public Stamp {
            Objects.requireNonNull(loader, "loader");
            if (remaining <= 0) {
                throw new IllegalArgumentException("remaining must be positive: " + remaining);
            }
        }

        /** The stamp as stored in the furnace's container: {@code [mostSig, leastSig, remaining]}. */
        public long[] encode() {
            return new long[] {loader.getMostSignificantBits(), loader.getLeastSignificantBits(), remaining};
        }

        /**
         * The stamp {@code stored} encodes, if any. A missing value, one of the wrong length, or a
         * {@code remaining} that is not positive is no stamp; one beyond {@code int} range is capped.
         */
        public static Optional<Stamp> decode(long[] stored) {
            if (stored == null || stored.length != ENCODED_LENGTH || stored[2] <= 0) {
                return Optional.empty();
            }
            int remaining = (int) Math.min(stored[2], Integer.MAX_VALUE);
            return Optional.of(new Stamp(new UUID(stored[0], stored[1]), remaining));
        }
    }

    /**
     * The stamp after {@code player} hand-loads {@code inserted} iron-bearing items.
     *
     * @param current the stamp before, if any
     * @return {@code current} unchanged if nothing was inserted
     */
    public static Optional<Stamp> load(Optional<Stamp> current, UUID player, int inserted) {
        Objects.requireNonNull(player, "player");
        if (inserted <= 0) {
            return current;
        }
        int before = current.filter(stamp -> stamp.loader().equals(player))
                .map(Stamp::remaining)
                .orElse(0);
        int remaining = (int) Math.min((long) before + inserted, Integer.MAX_VALUE);
        return Optional.of(new Stamp(player, remaining));
    }

    /**
     * One iron ingot smelted under {@code current}.
     *
     * @param after    the stamp to keep; empty once it is spent
     * @param credited the player to credit, if {@code remaining} was above 0
     */
    public record Smelt(Optional<Stamp> after, Optional<UUID> credited) {
    }

    /** One iron ingot smelted under {@code current}. */
    public static Smelt smelt(Optional<Stamp> current) {
        if (current.isEmpty()) {
            return new Smelt(Optional.empty(), Optional.empty());
        }
        Stamp stamp = current.get();
        Optional<Stamp> after = stamp.remaining() > 1
                ? Optional.of(new Stamp(stamp.loader(), stamp.remaining() - 1))
                : Optional.empty();
        return new Smelt(after, Optional.of(stamp.loader()));
    }

    /**
     * {@code current} with {@code remaining} capped at the iron-bearing input still in the furnace,
     * applied after a player's click has changed it. Iron the loader took back out stops counting,
     * so a helper's hopper-fed iron put in its place earns them nothing.
     *
     * @param input       the input slot's item type, or {@code null} if empty
     * @param inputAmount its amount
     */
    public static Optional<Stamp> clamp(Optional<Stamp> current, Material input, int inputAmount) {
        if (current.isEmpty()) {
            return current;
        }
        int iron = ironBearing(input) ? Math.max(inputAmount, 0) : 0;
        Stamp stamp = current.get();
        if (stamp.remaining() <= iron) {
            return current;
        }
        return iron > 0 ? Optional.of(new Stamp(stamp.loader(), iron)) : Optional.empty();
    }

    /**
     * How many iron-bearing items one click puts into the furnace's input slot, before the click is
     * applied. Only the actions that move items into the slot count; anything else is 0, which can
     * only withhold a stamp, never grant one.
     *
     * @param action         the click's action
     * @param placing        the item being put in: the cursor for a click on the input slot, the
     *                       hotbar or offhand item for a number-key or offhand swap onto it, or the
     *                       clicked item for a shift-click from the player's inventory; {@code null}
     *                       if none
     * @param placingAmount  its amount
     * @param existing       the input slot's item type, or {@code null} if empty
     * @param existingAmount its amount
     * @param maxStack       the most the input slot holds of {@code placing}
     */
    public static int inserted(InventoryAction action, Material placing, int placingAmount,
                               Material existing, int existingAmount, int maxStack) {
        if (!ironBearing(placing) || placingAmount <= 0) {
            return 0;
        }
        boolean empty = empty(existing, existingAmount);
        int space = empty ? maxStack : placing == existing ? maxStack - existingAmount : 0;
        return switch (action) {
            case PLACE_ALL, PLACE_SOME, MOVE_TO_OTHER_INVENTORY -> Math.max(0, Math.min(placingAmount, space));
            case PLACE_ONE -> space > 0 ? 1 : 0;
            // A swap replaces the slot's whole stack with the one being put in; the clamp after
            // the click settles any of the loader's own iron the swap took back out.
            case SWAP_WITH_CURSOR, HOTBAR_SWAP -> Math.min(placingAmount, maxStack);
            default -> 0;
        };
    }

    /**
     * How many iron-bearing items a drag leaves in the input slot beyond what was there.
     *
     * @param dragged        the item type the drag leaves in the slot
     * @param draggedAmount  the slot's amount after the drag
     * @param existing       the slot's item type before, or {@code null} if empty
     * @param existingAmount its amount before
     */
    public static int dragged(Material dragged, int draggedAmount, Material existing, int existingAmount) {
        if (!ironBearing(dragged)) {
            return 0;
        }
        int before = !empty(existing, existingAmount) && existing == dragged ? existingAmount : 0;
        return Math.max(0, draggedAmount - before);
    }

    private static boolean empty(Material type, int amount) {
        return type == null || type == Material.AIR || amount <= 0;
    }
}
