package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.listeners.FurnaceLoaderRules.Smelt;
import com.ninja6.antispeedrun.listeners.FurnaceLoaderRules.Stamp;

/** The furnace loader stamp and the smelted-iron credit rule (#214). */
class FurnaceLoaderRulesTest {

    private static final UUID RECIPIENT = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID HELPER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** How many of a run of smelts credited one player, and the stamp left after it. */
    private record Run(int credited, Optional<Stamp> after) {
    }

    /** Smelts {@code ingots} iron ingots under {@code stamp}, counting those that credit {@code who}. */
    private static Run run(Optional<Stamp> stamp, int ingots, UUID who) {
        int credited = 0;
        Optional<Stamp> current = stamp;
        for (int i = 0; i < ingots; i++) {
            Smelt smelt = FurnaceLoaderRules.smelt(current);
            if (smelt.credited().filter(who::equals).isPresent()) {
                credited++;
            }
            current = smelt.after();
        }
        return new Run(credited, current);
    }

    @Test
    @DisplayName("only raw iron and the two iron ores stamp a furnace")
    void ironBearing() {
        assertTrue(FurnaceLoaderRules.ironBearing(Material.RAW_IRON));
        assertTrue(FurnaceLoaderRules.ironBearing(Material.IRON_ORE));
        assertTrue(FurnaceLoaderRules.ironBearing(Material.DEEPSLATE_IRON_ORE));
        assertFalse(FurnaceLoaderRules.ironBearing(Material.RAW_IRON_BLOCK));
        assertFalse(FurnaceLoaderRules.ironBearing(Material.IRON_INGOT));
        assertFalse(FurnaceLoaderRules.ironBearing(Material.RAW_GOLD));
        assertFalse(FurnaceLoaderRules.ironBearing(Material.COAL));
        assertFalse(FurnaceLoaderRules.ironBearing(null));
    }

    @Test
    @DisplayName("furnaces and blast furnaces keep a stamp; smokers do not")
    void furnaces() {
        assertTrue(FurnaceLoaderRules.furnace(Material.FURNACE));
        assertTrue(FurnaceLoaderRules.furnace(Material.BLAST_FURNACE));
        assertFalse(FurnaceLoaderRules.furnace(Material.SMOKER));
        assertFalse(FurnaceLoaderRules.furnace(Material.CAMPFIRE));
        assertTrue(FurnaceLoaderRules.smeltsIron(Material.IRON_INGOT));
        assertFalse(FurnaceLoaderRules.smeltsIron(Material.IRON_NUGGET));
        assertFalse(FurnaceLoaderRules.smeltsIron(Material.GOLD_INGOT));
    }

    @Test
    @DisplayName("the stamp round-trips as [mostSig, leastSig, remaining]")
    void encoding() {
        Stamp stamp = new Stamp(RECIPIENT, 12);
        long[] stored = stamp.encode();
        assertArrayEquals(new long[] {RECIPIENT.getMostSignificantBits(),
                RECIPIENT.getLeastSignificantBits(), 12}, stored);
        assertEquals(Optional.of(stamp), Stamp.decode(stored));
    }

    @Test
    @DisplayName("a missing, malformed or spent stored value is no stamp")
    void decodeRejects() {
        assertEquals(Optional.empty(), Stamp.decode(null));
        assertEquals(Optional.empty(), Stamp.decode(new long[] {1L, 2L}));
        assertEquals(Optional.empty(), Stamp.decode(new long[] {1L, 2L, 3L, 4L}));
        assertEquals(Optional.empty(), Stamp.decode(new long[] {1L, 2L, 0L}));
        assertEquals(Optional.empty(), Stamp.decode(new long[] {1L, 2L, -5L}));
        assertEquals(Integer.MAX_VALUE, Stamp.decode(new long[] {1L, 2L, Long.MAX_VALUE})
                .orElseThrow().remaining());
        assertThrows(IllegalArgumentException.class, () -> new Stamp(RECIPIENT, 0));
    }

    @Test
    @DisplayName("loading adds for the same player and replaces another's stamp")
    void load() {
        Optional<Stamp> first = FurnaceLoaderRules.load(Optional.empty(), RECIPIENT, 5);
        assertEquals(Optional.of(new Stamp(RECIPIENT, 5)), first);
        Optional<Stamp> more = FurnaceLoaderRules.load(first, RECIPIENT, 3);
        assertEquals(Optional.of(new Stamp(RECIPIENT, 8)), more);
        Optional<Stamp> replaced = FurnaceLoaderRules.load(more, HELPER, 2);
        assertEquals(Optional.of(new Stamp(HELPER, 2)), replaced);
        assertEquals(more, FurnaceLoaderRules.load(more, HELPER, 0), "nothing inserted");
        assertEquals(Integer.MAX_VALUE, FurnaceLoaderRules.load(
                Optional.of(new Stamp(RECIPIENT, Integer.MAX_VALUE)), RECIPIENT, 64)
                .orElseThrow().remaining(), "saturates");
    }

    @Test
    @DisplayName("each iron ingot takes one from the stamp and credits the loader only while it was above 0")
    void smelt() {
        Run five = run(Optional.of(new Stamp(RECIPIENT, 3)), 5, RECIPIENT);
        assertEquals(3, five.credited());
        assertEquals(Optional.empty(), five.after(), "a spent stamp is removed");

        Smelt one = FurnaceLoaderRules.smelt(Optional.of(new Stamp(RECIPIENT, 2)));
        assertEquals(Optional.of(RECIPIENT), one.credited());
        assertEquals(Optional.of(new Stamp(RECIPIENT, 1)), one.after());

        Smelt none = FurnaceLoaderRules.smelt(Optional.empty());
        assertEquals(Optional.empty(), none.credited(), "an unstamped furnace credits nobody");
        assertEquals(Optional.empty(), none.after());
    }

    @Test
    @DisplayName("a hand loader is credited however the output leaves; a recipient taking a helper's ingot is not")
    void helperLoaded() {
        // The stamp is the only input to the credit: who takes the ingot, or whether a hopper does,
        // never enters it.
        Optional<Stamp> stamp = FurnaceLoaderRules.load(Optional.empty(), HELPER, 4);
        assertEquals(0, run(stamp, 4, RECIPIENT).credited());
        assertEquals(4, run(stamp, 4, HELPER).credited());
    }

    @Test
    @DisplayName("hopper-fed iron in a furnace the recipient loaded earns only what the recipient loaded")
    void hopperFedBeyondLoad() {
        // Recipient hand-loads 3; a helper's hopper adds 20 without touching the stamp.
        Optional<Stamp> stamp = FurnaceLoaderRules.load(Optional.empty(), RECIPIENT, 3);
        Run all = run(stamp, 23, RECIPIENT);
        assertEquals(3, all.credited());
        assertEquals(Optional.empty(), all.after());
    }

    @Test
    @DisplayName("iron the loader takes back out stops counting")
    void clamp() {
        Optional<Stamp> stamp = Optional.of(new Stamp(RECIPIENT, 10));
        assertEquals(Optional.of(new Stamp(RECIPIENT, 4)),
                FurnaceLoaderRules.clamp(stamp, Material.RAW_IRON, 4));
        assertEquals(stamp, FurnaceLoaderRules.clamp(stamp, Material.RAW_IRON, 30), "never raised");
        assertEquals(Optional.empty(), FurnaceLoaderRules.clamp(stamp, null, 0), "emptied");
        assertEquals(Optional.empty(), FurnaceLoaderRules.clamp(stamp, Material.RAW_GOLD, 16),
                "other input");
        assertEquals(Optional.empty(), FurnaceLoaderRules.clamp(Optional.empty(), Material.RAW_IRON, 8));

        // Load 1, take it back out, let a helper's hopper refill it: nothing is credited.
        Optional<Stamp> loaded = FurnaceLoaderRules.load(Optional.empty(), RECIPIENT, 1);
        Optional<Stamp> taken = FurnaceLoaderRules.clamp(loaded, null, 0);
        assertEquals(0, run(taken, 64, RECIPIENT).credited());
    }

    @Test
    @DisplayName("clicks count what fits into the input slot")
    void clickInserts() {
        assertEquals(16, FurnaceLoaderRules.inserted(InventoryAction.PLACE_ALL,
                Material.RAW_IRON, 16, null, 0, 64));
        assertEquals(16, FurnaceLoaderRules.inserted(InventoryAction.PLACE_ALL,
                Material.RAW_IRON, 16, Material.AIR, 0, 64), "air is empty");
        assertEquals(16, FurnaceLoaderRules.inserted(InventoryAction.PLACE_ALL,
                Material.RAW_IRON, 16, Material.RAW_IRON, 40, 64));
        assertEquals(4, FurnaceLoaderRules.inserted(InventoryAction.PLACE_SOME,
                Material.RAW_IRON, 30, Material.RAW_IRON, 60, 64));
        assertEquals(1, FurnaceLoaderRules.inserted(InventoryAction.PLACE_ONE,
                Material.IRON_ORE, 30, Material.IRON_ORE, 10, 64));
        assertEquals(0, FurnaceLoaderRules.inserted(InventoryAction.PLACE_ONE,
                Material.IRON_ORE, 30, Material.IRON_ORE, 64, 64), "full");
        assertEquals(12, FurnaceLoaderRules.inserted(InventoryAction.SWAP_WITH_CURSOR,
                Material.DEEPSLATE_IRON_ORE, 12, Material.RAW_GOLD, 5, 64));
        assertEquals(9, FurnaceLoaderRules.inserted(InventoryAction.HOTBAR_SWAP,
                Material.RAW_IRON, 9, Material.RAW_IRON, 50, 64));
    }

    @Test
    @DisplayName("a shift-click counts only what the input slot takes")
    void shiftClick() {
        assertEquals(64, FurnaceLoaderRules.inserted(InventoryAction.MOVE_TO_OTHER_INVENTORY,
                Material.RAW_IRON, 64, null, 0, 64));
        assertEquals(14, FurnaceLoaderRules.inserted(InventoryAction.MOVE_TO_OTHER_INVENTORY,
                Material.RAW_IRON, 64, Material.RAW_IRON, 50, 64));
        assertEquals(0, FurnaceLoaderRules.inserted(InventoryAction.MOVE_TO_OTHER_INVENTORY,
                Material.RAW_IRON, 64, Material.IRON_ORE, 1, 64), "another type");
    }

    @Test
    @DisplayName("non-iron input and non-insert actions count nothing")
    void clickIgnores() {
        assertEquals(0, FurnaceLoaderRules.inserted(InventoryAction.PLACE_ALL,
                Material.RAW_GOLD, 16, null, 0, 64));
        assertEquals(0, FurnaceLoaderRules.inserted(InventoryAction.PLACE_ALL,
                null, 0, null, 0, 64));
        for (InventoryAction action : new InventoryAction[] {InventoryAction.PICKUP_ALL,
                InventoryAction.PICKUP_HALF, InventoryAction.COLLECT_TO_CURSOR,
                InventoryAction.DROP_ALL_SLOT, InventoryAction.CLONE_STACK, InventoryAction.NOTHING}) {
            assertEquals(0, FurnaceLoaderRules.inserted(action, Material.RAW_IRON, 16, null, 0, 64),
                    action.name());
        }
    }

    @Test
    @DisplayName("a drag counts what it adds to the input slot")
    void drag() {
        assertEquals(8, FurnaceLoaderRules.dragged(Material.RAW_IRON, 8, null, 0));
        assertEquals(3, FurnaceLoaderRules.dragged(Material.RAW_IRON, 13, Material.RAW_IRON, 10));
        assertEquals(0, FurnaceLoaderRules.dragged(Material.RAW_GOLD, 13, null, 0));
        assertEquals(0, FurnaceLoaderRules.dragged(Material.RAW_IRON, 10, Material.RAW_IRON, 10));
    }
}
