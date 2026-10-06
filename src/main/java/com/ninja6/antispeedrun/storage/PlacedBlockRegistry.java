package com.ninja6.antispeedrun.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import com.ninja6.antispeedrun.storage.PlacedBlockLedger.Position;

/**
 * Which credit-relevant blocks a player placed, so mining them earns no mining credit (#212).
 *
 * <p>The record is {@code antispeedrun:placed-blocks} in each chunk's
 * {@code PersistentDataContainer}, an {@code INTEGER_ARRAY} of packed chunk-local cells in
 * ascending order; {@link PlacedBlockLedger} owns the encoding. It is the register entry in
 * {@code docs/provenance-model.md}. Only {@link #isCreditRelevant credit-relevant} materials are
 * ever recorded, so a builder's chunk stays small, and a chunk with nothing recorded carries no key.
 *
 * <h2>Threading</h2>
 *
 * A chunk's container belongs to the region that owns the chunk. Call these from an event for a
 * block in that region, which is how {@code PlacedBlockListener} and any credit recorder reach it.
 * A write for a chunk the current thread does not own is not attempted inline: it goes to the
 * {@code RegionScheduler} for that chunk. A read for such a chunk answers placed, which errs toward
 * withholding credit rather than toward granting it.
 */
public final class PlacedBlockRegistry {

    /**
     * The materials whose mining earns a credit in the Amendment of the provenance record: the stone
     * family and its cobbled forms ({@code story/mine_stone}), iron ore (the mined-iron sub-credit of
     * {@code story/smelt_iron}) and diamond ore ({@code story/mine_diamond}).
     */
    private static final Set<Material> CREDIT_RELEVANT = EnumSet.of(
            Material.STONE, Material.COBBLESTONE,
            Material.DEEPSLATE, Material.COBBLED_DEEPSLATE,
            Material.BLACKSTONE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE);

    private final Plugin plugin;
    private final NamespacedKey key;
    private final PlacedBlockLedger<World> ledger;

    public PlacedBlockRegistry(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.key = new NamespacedKey(plugin, "placed-blocks");
        this.ledger = new PlacedBlockLedger<>(new PdcChunks(key));
    }

    /** Whether breaking a block of {@code material} can earn a mining credit. */
    public static boolean isCreditRelevant(Material material) {
        return material != null && CREDIT_RELEVANT.contains(material);
    }

    /**
     * Whether a player placed {@code block}, or a piston moved it.
     *
     * <p>A natural block answers {@code false}. A block whose chunk the current thread does not own
     * answers {@code true}; see the class notes.
     */
    public boolean isPlaced(Block block) {
        Objects.requireNonNull(block, "block");
        World world = block.getWorld();
        if (!owned(world, block.getX() >> 4, block.getZ() >> 4)) {
            return true;
        }
        return ledger.isPlaced(world, position(block));
    }

    /** Records {@code block} as placed. */
    public void markPlaced(Block block) {
        update(Objects.requireNonNull(block, "block").getWorld(), List.of(), List.of(position(block)));
    }

    /** Forgets {@code block}. */
    public void clear(Block block) {
        update(Objects.requireNonNull(block, "block").getWorld(), List.of(position(block)), List.of());
    }

    /**
     * Clears {@code removed} and then marks {@code added}, in one pass per chunk.
     *
     * <p>Chunks the current thread owns are edited now. The rest are handed to their own region;
     * the order within each chunk is kept, which is the only order that matters, since a chunk's
     * entry depends on nothing outside it.
     */
    public void update(World world, Collection<Position> removed, Collection<Position> added) {
        Objects.requireNonNull(world, "world");
        Map<Long, List<Position>> foreignRemoved = new LinkedHashMap<>();
        Map<Long, List<Position>> foreignAdded = new LinkedHashMap<>();
        List<Position> localRemoved = split(world, removed, foreignRemoved);
        List<Position> localAdded = split(world, added, foreignAdded);
        ledger.update(world, localRemoved, localAdded);

        Set<Long> foreign = new LinkedHashSet<>(foreignRemoved.keySet());
        foreign.addAll(foreignAdded.keySet());
        for (long chunk : foreign) {
            List<Position> chunkRemoved = foreignRemoved.getOrDefault(chunk, List.of());
            List<Position> chunkAdded = foreignAdded.getOrDefault(chunk, List.of());
            plugin.getServer().getRegionScheduler().execute(plugin, world,
                    (int) (chunk >> 32), (int) chunk,
                    () -> ledger.update(world, chunkRemoved, chunkAdded));
        }
    }

    /**
     * Records a piston moving {@code moved} one block along {@code direction}: every source is
     * cleared and every credit-relevant block is marked placed at its destination. See
     * {@link PlacedBlockLedger#move}.
     */
    public void move(World world, List<Block> moved, BlockFace direction) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(direction, "direction");
        List<Position> sources = new ArrayList<>(moved.size());
        List<Position> relevant = new ArrayList<>(moved.size());
        for (Block block : moved) {
            Position at = position(block);
            sources.add(at);
            if (isCreditRelevant(block.getType())) {
                relevant.add(at);
            }
        }
        update(world, sources, PlacedBlockLedger.destinations(relevant,
                direction.getModX(), direction.getModY(), direction.getModZ()));
    }

    /** The world position of {@code block}. */
    public static Position position(Block block) {
        return new Position(block.getX(), block.getY(), block.getZ());
    }

    private List<Position> split(World world, Collection<Position> positions,
            Map<Long, List<Position>> foreign) {
        List<Position> local = new ArrayList<>(positions.size());
        for (Position at : positions) {
            int chunkX = at.x() >> 4;
            int chunkZ = at.z() >> 4;
            if (owned(world, chunkX, chunkZ)) {
                local.add(at);
            } else {
                long chunk = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
                foreign.computeIfAbsent(chunk, k -> new ArrayList<>()).add(at);
            }
        }
        return local;
    }

    private boolean owned(World world, int chunkX, int chunkZ) {
        return plugin.getServer().isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    /** The chunk PDC behind the ledger. Only ever called for a chunk the thread owns. */
    private record PdcChunks(NamespacedKey key) implements PlacedBlockLedger.ChunkAccess<World> {

        @Override
        public int[] read(World world, int chunkX, int chunkZ) {
            return container(world, chunkX, chunkZ).get(key, PersistentDataType.INTEGER_ARRAY);
        }

        @Override
        public void write(World world, int chunkX, int chunkZ, int[] cells) {
            PersistentDataContainer container = container(world, chunkX, chunkZ);
            if (cells.length == 0) {
                container.remove(key);
            } else {
                container.set(key, PersistentDataType.INTEGER_ARRAY, cells);
            }
        }

        private static PersistentDataContainer container(World world, int chunkX, int chunkZ) {
            return world.getChunkAt(chunkX, chunkZ).getPersistentDataContainer();
        }
    }
}
