package com.ninja6.antispeedrun.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.storage.PlacedBlockLedger.Position;

/** The placed-block registry's bookkeeping (#212), over an in-memory stand-in for chunk PDC. */
class PlacedBlockLedgerTest {

    private static final String WORLD = "world";

    /** One map entry per chunk that holds cells, as the chunk PDC holds one key or none. */
    private static final class FakeChunks implements PlacedBlockLedger.ChunkAccess<String> {

        final Map<String, int[]> stored = new HashMap<>();
        int reads;
        int writes;

        private static String key(String world, int chunkX, int chunkZ) {
            return world + "/" + chunkX + "/" + chunkZ;
        }

        @Override
        public int[] read(String world, int chunkX, int chunkZ) {
            reads++;
            int[] cells = stored.get(key(world, chunkX, chunkZ));
            return cells == null ? null : cells.clone();
        }

        @Override
        public void write(String world, int chunkX, int chunkZ, int[] cells) {
            writes++;
            if (cells.length == 0) {
                stored.remove(key(world, chunkX, chunkZ));
            } else {
                stored.put(key(world, chunkX, chunkZ), cells.clone());
            }
        }
    }

    private final FakeChunks chunks = new FakeChunks();
    private final PlacedBlockLedger<String> ledger = new PlacedBlockLedger<>(chunks);

    private static Position at(int x, int y, int z) {
        return new Position(x, y, z);
    }

    @Test
    @DisplayName("a naturally generated block is reported as natural")
    void naturalIsNatural() {
        assertFalse(ledger.isPlaced(WORLD, at(3, 12, 7)));
        ledger.markPlaced(WORLD, at(3, 13, 7));
        assertFalse(ledger.isPlaced(WORLD, at(3, 12, 7)), "a neighbour's entry is not this block's");
        assertFalse(ledger.isPlaced("other", at(3, 13, 7)), "the same position in another world");
    }

    @Test
    @DisplayName("a placed block is reported as placed until it is broken")
    void placeThenBreak() {
        Position stone = at(100, 64, -200);
        ledger.markPlaced(WORLD, stone);
        assertTrue(ledger.isPlaced(WORLD, stone));

        ledger.clear(WORLD, stone);
        assertFalse(ledger.isPlaced(WORLD, stone));
        assertTrue(chunks.stored.isEmpty(), "a chunk with nothing recorded carries no key");
    }

    @Test
    @DisplayName("a placed block pushed by a piston is reported as placed at its new position")
    void pistonCarriesPlacement() {
        Position source = at(5, 70, 5);
        ledger.markPlaced(WORLD, source);

        ledger.move(WORLD, List.of(source), List.of(source), 1, 0, 0);

        assertTrue(ledger.isPlaced(WORLD, at(6, 70, 5)));
        assertFalse(ledger.isPlaced(WORLD, source), "the source is empty after the push");
    }

    @Test
    @DisplayName("a natural block moved by a piston is marked placed, conservatively")
    void pistonMarksNaturalBlocks() {
        Position natural = at(-1, 30, -1);
        ledger.move(WORLD, List.of(natural), List.of(natural), 0, 1, 0);
        assertTrue(ledger.isPlaced(WORLD, at(-1, 31, -1)));
    }

    @Test
    @DisplayName("a chain of pushed blocks ends with every destination marked, even across chunks")
    void pistonChain() {
        // Three stone in a row pushed east over the chunk boundary between x = 15 and x = 16.
        List<Position> row = List.of(at(14, 40, 2), at(15, 40, 2), at(16, 40, 2));
        for (Position p : row) {
            ledger.markPlaced(WORLD, p);
        }

        ledger.move(WORLD, row, row, 1, 0, 0);

        assertFalse(ledger.isPlaced(WORLD, at(14, 40, 2)));
        assertTrue(ledger.isPlaced(WORLD, at(15, 40, 2)));
        assertTrue(ledger.isPlaced(WORLD, at(16, 40, 2)));
        assertTrue(ledger.isPlaced(WORLD, at(17, 40, 2)));
    }

    @Test
    @DisplayName("a moved block that is not credit-relevant clears its source but is not recorded")
    void pistonSkipsIrrelevant() {
        Position stale = at(8, 50, 8);
        ledger.markPlaced(WORLD, stale);

        ledger.move(WORLD, List.of(stale), List.of(), 0, 0, 1);

        assertFalse(ledger.isPlaced(WORLD, stale));
        assertFalse(ledger.isPlaced(WORLD, at(8, 50, 9)));
    }

    @Test
    @DisplayName("a retraction moves blocks back toward the piston")
    void pistonRetract() {
        Position pulled = at(0, 64, 2);
        ledger.markPlaced(WORLD, pulled);
        ledger.move(WORLD, List.of(pulled), List.of(pulled), 0, 0, -1);
        assertTrue(ledger.isPlaced(WORLD, at(0, 64, 1)));
        assertFalse(ledger.isPlaced(WORLD, pulled));
    }

    @Test
    @DisplayName("negative coordinates and heights resolve to the right chunk and cell")
    void negativeCoordinates() {
        Position deep = at(-17, -64, -33);
        ledger.markPlaced(WORLD, deep);
        assertTrue(ledger.isPlaced(WORLD, deep));
        assertTrue(chunks.stored.containsKey("world/-2/-3"));
        assertFalse(ledger.isPlaced(WORLD, at(-1, -64, -1)));
        assertFalse(ledger.isPlaced(WORLD, at(-17, 192, -33)), "y wraps nothing into the same cell");
    }

    @Test
    @DisplayName("every position in a chunk packs to a distinct cell across the full datapack height")
    void cellsAreDistinct() {
        Set<Integer> seen = new HashSet<>();
        for (int y = -2032; y <= 2031; y += 7) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    assertTrue(seen.add(PlacedBlockLedger.cell(at(32 + x, y, -48 + z))),
                            () -> "duplicate cell");
                }
            }
        }
        assertNotEquals(PlacedBlockLedger.cell(at(0, -1, 0)), PlacedBlockLedger.cell(at(15, -1, 15)));
    }

    @Test
    @DisplayName("the stored array stays sorted and holds each cell once")
    void storedSortedAndUnique() {
        ledger.markPlaced(WORLD, at(9, 10, 3));
        ledger.markPlaced(WORLD, at(1, -5, 1));
        ledger.markPlaced(WORLD, at(15, 300, 15));
        ledger.markPlaced(WORLD, at(9, 10, 3));

        int[] cells = chunks.stored.get("world/0/0");
        assertEquals(3, cells.length);
        int[] sorted = cells.clone();
        Arrays.sort(sorted);
        assertArrayEquals(sorted, cells);
    }

    @Test
    @DisplayName("an edit that changes nothing does not write the chunk")
    void noOpEditsDoNotWrite() {
        ledger.clear(WORLD, at(1, 1, 1));
        assertEquals(0, chunks.writes);

        ledger.markPlaced(WORLD, at(1, 1, 1));
        ledger.markPlaced(WORLD, at(1, 1, 1));
        assertEquals(1, chunks.writes);
    }

    @Test
    @DisplayName("one update reads and writes each chunk once")
    void oneReadAndWritePerChunk() {
        chunks.reads = 0;
        ledger.update(WORLD, List.of(at(0, 1, 0), at(1, 1, 0)),
                List.of(at(2, 1, 0), at(3, 1, 0), at(20, 1, 0)));
        assertEquals(2, chunks.reads);
        assertEquals(2, chunks.writes);
    }

    @Test
    @DisplayName("only the stone family, cobbled forms, blackstone, iron ore and diamond ore are recorded")
    void creditRelevantMaterials() {
        for (Material m : List.of(Material.STONE, Material.COBBLESTONE, Material.DEEPSLATE,
                Material.COBBLED_DEEPSLATE, Material.BLACKSTONE, Material.IRON_ORE,
                Material.DEEPSLATE_IRON_ORE, Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE)) {
            assertTrue(PlacedBlockRegistry.isCreditRelevant(m), m::name);
        }
        for (Material m : List.of(Material.DIRT, Material.OAK_PLANKS, Material.STONE_BRICKS,
                Material.IRON_BLOCK, Material.DIAMOND_BLOCK, Material.RAW_IRON_BLOCK,
                Material.MOSSY_COBBLESTONE, Material.GOLD_ORE, Material.AIR)) {
            assertFalse(PlacedBlockRegistry.isCreditRelevant(m), m::name);
        }
        assertFalse(PlacedBlockRegistry.isCreditRelevant(null));
    }
}
