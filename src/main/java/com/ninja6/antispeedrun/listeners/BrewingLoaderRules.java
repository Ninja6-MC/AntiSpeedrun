package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryAction;

/** Ingredient counts attributed to the last player who hand-loaded the stand. */
public final class BrewingLoaderRules {
    private static final int ENCODED_LENGTH = 3;
    private BrewingLoaderRules() { }
    public record Stamp(UUID loader, int remaining) {

        public Stamp {
            Objects.requireNonNull(loader, "loader");
            if (remaining <= 0) {
                throw new IllegalArgumentException("remaining must be positive: " + remaining);
            }
        }

        /** The stamp as stored in the stand's container: {@code [mostSig, leastSig, remaining]}. */
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
     * The stamp after {@code player} hand-loads {@code inserted} ingredients.
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
     * One ingredient consumed under {@code current}.
     *
     * @param after    the stamp to keep; empty once it is spent
     * @param credited the player to credit, if {@code remaining} was above 0
     */
    public record Brew(Optional<Stamp> after, Optional<UUID> credited) {
    }

    /** One ingredient consumed under {@code current}. */
    public static Brew brew(Optional<Stamp> current) {
        if (current.isEmpty()) {
            return new Brew(Optional.empty(), Optional.empty());
        }
        Stamp stamp = current.get();
        Optional<Stamp> after = stamp.remaining() > 1
                ? Optional.of(new Stamp(stamp.loader(), stamp.remaining() - 1))
                : Optional.empty();
        return new Brew(after, Optional.of(stamp.loader()));
    }

    public static int inserted(InventoryAction action, Material placing, int placingAmount,
                               Material existing, int existingAmount, int maxStack) {
        if (placing == null || placing == Material.AIR || placingAmount <= 0) {
            return 0;
        }
        boolean empty = empty(existing, existingAmount);
        int space = empty ? maxStack : placing == existing ? maxStack - existingAmount : 0;
        return switch (action) {
            case PLACE_ALL, PLACE_SOME, MOVE_TO_OTHER_INVENTORY -> Math.max(0, Math.min(placingAmount, space));
            case PLACE_ONE -> space > 0 ? 1 : 0;
            // A swap replaces the slot's whole stack with the one being put in; the clamp after
            // the click settles any of the loader's own ingredients the swap took back out.
            case SWAP_WITH_CURSOR, HOTBAR_SWAP -> Math.min(placingAmount, maxStack);
            default -> 0;
        };
    }

    private static boolean empty(Material type, int amount) {
        return type == null || type == Material.AIR || amount <= 0;
    }
}
