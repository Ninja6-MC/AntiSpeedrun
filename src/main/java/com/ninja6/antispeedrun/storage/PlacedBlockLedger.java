package com.ninja6.antispeedrun.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The placed-block registry's bookkeeping, apart from Bukkit (#212).
 *
 * <p>Each chunk holds one sorted {@code int[]} of the cells inside it that a player placed. A cell
 * packs the block's chunk-local coordinates as {@code (y << 8) | (localZ << 4) | localX}: four bits
 * each for the local {@code x} and {@code z}, and the signed world {@code y} above them, which
 * covers every height a datapack can set ({@code -2032} to {@code 2031}) with room to spare. The
 * array is sorted so a lookup is a binary search over what the container hands back, with nothing
 * decoded and nothing cached.
 *
 * <p>There is no in-memory state here at all. Every answer is read from the chunk, so there is no
 * map shared between regions, nothing to evict and nothing to save: the server writes the chunk's
 * container out with the chunk.
 *
 * @param <W> the world handle; {@code org.bukkit.World} in production, anything in tests
 */
public final class PlacedBlockLedger<W> {

    /** Reads and writes one chunk's cells. The caller guarantees it owns that chunk. */
    public interface ChunkAccess<W> {

        /** The chunk's sorted cells, or {@code null} when it holds none. */
        int[] read(W world, int chunkX, int chunkZ);

        /** Replaces the chunk's cells; an empty array removes the entry from the chunk. */
        void write(W world, int chunkX, int chunkZ, int[] cells);
    }

    /** A block position in world coordinates. */
    public record Position(int x, int y, int z) {

        /** The position {@code (dx, dy, dz)} away. */
        public Position offset(int dx, int dy, int dz) {
            return new Position(x + dx, y + dy, z + dz);
        }
    }

    private static final int[] EMPTY = new int[0];

    private final ChunkAccess<W> chunks;

    public PlacedBlockLedger(ChunkAccess<W> chunks) {
        this.chunks = Objects.requireNonNull(chunks, "chunks");
    }

    /** Whether a player placed the block at {@code at}. */
    public boolean isPlaced(W world, Position at) {
        int[] cells = chunks.read(world, at.x() >> 4, at.z() >> 4);
        return cells != null && Arrays.binarySearch(cells, cell(at)) >= 0;
    }

    /** Records the block at {@code at} as placed. */
    public void markPlaced(W world, Position at) {
        update(world, List.of(), List.of(at));
    }

    /** Forgets the block at {@code at}. */
    public void clear(W world, Position at) {
        update(world, List.of(at), List.of());
    }

    /**
     * Clears {@code removed}, then marks {@code added}, reading and writing each chunk at most once.
     *
     * <p>Removals come first, so a piston move, where one block's destination is the next block's
     * source, ends with every destination marked. A chunk whose cells come out unchanged is not
     * written.
     */
    public void update(W world, Collection<Position> removed, Collection<Position> added) {
        Objects.requireNonNull(removed, "removed");
        Objects.requireNonNull(added, "added");
        Map<Long, ChunkEdit> edits = new LinkedHashMap<>();
        for (Position at : removed) {
            edit(edits, at).removed.add(cell(at));
        }
        for (Position at : added) {
            edit(edits, at).added.add(cell(at));
        }
        for (ChunkEdit edit : edits.values()) {
            int[] before = chunks.read(world, edit.chunkX, edit.chunkZ);
            int[] after = before == null ? EMPTY : before;
            for (int cell : edit.removed) {
                after = without(after, cell);
            }
            for (int cell : edit.added) {
                after = with(after, cell);
            }
            boolean unchanged = before == null ? after.length == 0 : Arrays.equals(before, after);
            if (!unchanged) {
                chunks.write(world, edit.chunkX, edit.chunkZ, after);
            }
        }
    }

    /**
     * The edit for a piston moving {@code moved} one block along {@code (dx, dy, dz)}.
     *
     * <p>Every source is cleared, and every credit-relevant block in {@code relevant} is marked at
     * its destination whether or not it was placed before: a piston can carry a natural block into
     * a position a player chose, so the move is treated as a placement. That errs toward withholding
     * credit, never toward granting it.
     *
     * @param moved    every block the piston moves, at its source
     * @param relevant the subset of {@code moved} whose material is credit-relevant
     */
    public void move(W world, Collection<Position> moved, Collection<Position> relevant,
            int dx, int dy, int dz) {
        update(world, moved, destinations(relevant, dx, dy, dz));
    }

    /** {@code sources}, each moved by {@code (dx, dy, dz)}. */
    public static List<Position> destinations(Collection<Position> sources, int dx, int dy, int dz) {
        List<Position> out = new ArrayList<>(sources.size());
        for (Position at : sources) {
            out.add(at.offset(dx, dy, dz));
        }
        return out;
    }

    /** The packed cell for {@code at} inside its chunk. */
    static int cell(Position at) {
        return (at.y() << 8) | ((at.z() & 15) << 4) | (at.x() & 15);
    }

    private static ChunkEdit edit(Map<Long, ChunkEdit> edits, Position at) {
        int chunkX = at.x() >> 4;
        int chunkZ = at.z() >> 4;
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        return edits.computeIfAbsent(key, k -> new ChunkEdit(chunkX, chunkZ));
    }

    /** One chunk's pending removals and additions. */
    private static final class ChunkEdit {

        final int chunkX;
        final int chunkZ;
        final List<Integer> removed = new ArrayList<>();
        final List<Integer> added = new ArrayList<>();

        ChunkEdit(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    /** {@code cells} with {@code cell} inserted in order, or {@code cells} itself if present. */
    static int[] with(int[] cells, int cell) {
        int at = Arrays.binarySearch(cells, cell);
        if (at >= 0) {
            return cells;
        }
        int insert = -at - 1;
        int[] grown = new int[cells.length + 1];
        System.arraycopy(cells, 0, grown, 0, insert);
        grown[insert] = cell;
        System.arraycopy(cells, insert, grown, insert + 1, cells.length - insert);
        return grown;
    }

    /** {@code cells} without {@code cell}, or {@code cells} itself if absent. */
    static int[] without(int[] cells, int cell) {
        int at = Arrays.binarySearch(cells, cell);
        if (at < 0) {
            return cells;
        }
        int[] shrunk = new int[cells.length - 1];
        System.arraycopy(cells, 0, shrunk, 0, at);
        System.arraycopy(cells, at + 1, shrunk, at, cells.length - at - 1);
        return shrunk;
    }
}
