package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import com.ninja6.antispeedrun.storage.PlacedBlockLedger.Position;
import com.ninja6.antispeedrun.storage.PlacedBlockRegistry;

/**
 * Keeps the placed-block registry (#212) in step with the world.
 *
 * <p>Every handler runs at {@code MONITOR} and skips cancelled events, so only a placement, break
 * or move that actually happened is recorded. A credit recorder that asks whether a broken block
 * was placed must therefore listen to {@code BlockBreakEvent} below {@code MONITOR}: by the time
 * this listener sees the break, the entry is gone.
 *
 * <p>Each event fires on the region that owns the blocks it names, so every read and write here is
 * on the owning thread; {@link PlacedBlockRegistry} hands anything else to that chunk's region.
 *
 * <p>A placed block removed some other way (a wither, an enderman, a fluid) can leave its entry
 * behind. A stale entry only withholds credit for that one position, which is the acceptable
 * direction to be wrong in.
 */
public final class PlacedBlockListener implements Listener {

    private final PlacedBlockRegistry registry;

    public PlacedBlockListener(PlacedBlockRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * A placement, including the {@code BlockMultiPlaceEvent} subclass, which Bukkit delivers to
     * {@code BlockPlaceEvent} handlers. The world already holds the placed blocks at {@code MONITOR},
     * so each position is judged by what is now there.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent multi) {
            List<Position> placed = new ArrayList<>();
            for (BlockState replaced : multi.getReplacedBlockStates()) {
                Block block = replaced.getBlock();
                if (PlacedBlockRegistry.isCreditRelevant(block.getType())) {
                    placed.add(PlacedBlockRegistry.position(block));
                }
            }
            if (!placed.isEmpty()) {
                registry.update(event.getBlock().getWorld(), List.of(), placed);
            }
            return;
        }
        Block block = event.getBlockPlaced();
        if (PlacedBlockRegistry.isCreditRelevant(block.getType())) {
            registry.markPlaced(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (PlacedBlockRegistry.isCreditRelevant(block.getType())) {
            registry.clear(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!event.getBlocks().isEmpty()) {
            registry.move(event.getBlock().getWorld(), event.getBlocks(), event.getDirection());
        }
    }

    /**
     * A sticky piston pulling blocks back. Bukkit reports the direction the blocks travel for a
     * retraction as well as for an extension, so the destination is one step along it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!event.getBlocks().isEmpty()) {
            registry.move(event.getBlock().getWorld(), event.getBlocks(), event.getDirection());
        }
    }

    /** Explosions remove blocks without a break event; clearing them keeps the entries honest. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        clearDestroyed(event.getLocation().getWorld(), event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        clearDestroyed(event.getBlock().getWorld(), event.blockList());
    }

    private void clearDestroyed(World world, List<Block> destroyed) {
        if (world == null || destroyed.isEmpty()) {
            return;
        }
        List<Position> cleared = new ArrayList<>();
        for (Block block : destroyed) {
            if (PlacedBlockRegistry.isCreditRelevant(block.getType())) {
                cleared.add(PlacedBlockRegistry.position(block));
            }
        }
        if (!cleared.isEmpty()) {
            registry.update(world, cleared, List.of());
        }
    }
}
