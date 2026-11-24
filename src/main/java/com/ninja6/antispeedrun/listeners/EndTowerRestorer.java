package com.ninja6.antispeedrun.listeners;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;

/**
 * Rebuilds the End's pillar crystals and cages when a reinforcement window spawns extra dragons
 * (#273, Task 12.3), with {@code boss-scaling.multi-dragon.reset-towers-on-scale} on. What is placed
 * is decided by {@link EndTowerRules}.
 *
 * <h2>Folia</h2>
 *
 * Each pillar is restored by a task on the region owning its centre chunk, which reads and writes
 * nothing outside the pillar's cage space. If that space spans a region the task does not own, the
 * pillar waits like an occupied one.
 *
 * <h2>Occupied pillars</h2>
 *
 * A pillar with a player in its cage space is asked again every second. It is restored once the
 * player has left, and given up, with nothing placed, when the scaled fight has no extra dragon
 * left, when the setting or {@code boss-scaling} is turned off, or when the world unloads.
 */
final class EndTowerRestorer {

    private static final long RETRY_TICKS = 20L;

    private final AntiSpeedrunPlugin plugin;

    EndTowerRestorer(AntiSpeedrunPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts one restore of every pillar in {@code world}. Safe from any thread.
     *
     * @param fightOn whether the scaled fight still has an extra dragon; read from each pillar's
     *                region, so it must be safe from any thread
     */
    void restore(World world, BooleanSupplier fightOn) {
        UUID id = world.getUID();
        plugin.getLogger().info(() -> "Restoring the End crystals and cages in " + world.getName()
                + " for the reinforced fight.");
        for (EndTowerRules.Centre centre : EndTowerRules.pillarCentres()) {
            AtomicBoolean reportedWait = new AtomicBoolean();
            plugin.getServer().getRegionScheduler().runAtFixedRate(plugin, world, centre.chunkX(), centre.chunkZ(),
                    task -> {
                        if (plugin.getServer().getWorld(id) == null || !enabled() || !fightOn.getAsBoolean()) {
                            task.cancel();
                            if (reportedWait.get()) {
                                plugin.getLogger().info(() -> "The pillar at " + centre.x() + ", " + centre.z()
                                        + " in " + world.getName() + " was not restored: it was still occupied "
                                        + "when the fight or the setting ended.");
                            }
                            return;
                        }
                        try {
                            if (attempt(world, centre, reportedWait)) {
                                task.cancel();
                            }
                        } catch (RuntimeException failure) {
                            // One warning, not one a second: the pillar is given up for this window.
                            task.cancel();
                            plugin.getLogger().log(Level.WARNING, "End pillar at " + centre.x()
                                    + ", " + centre.z() + " in " + world.getName() + " not restored", failure);
                        }
                    }, 1L, RETRY_TICKS);
        }
    }

    private boolean enabled() {
        var bossScaling = plugin.configuration().bossScaling();
        return bossScaling.enabled() && bossScaling.multiDragon().resetTowersOnScale();
    }

    /**
     * On the region owning {@code centre}: restores the pillar if it can.
     *
     * @return true when nothing is left to do at this pillar for this window
     */
    private boolean attempt(World world, EndTowerRules.Centre centre, AtomicBoolean reportedWait) {
        EndTowerRules.Bounds space = EndTowerRules.occupiedSpace(centre, 0);
        if (!world.isChunkLoaded(centre.chunkX(), centre.chunkZ())) {
            // The pillars ring the main island, which a party fighting there keeps loaded. Loading
            // terrain for a pillar nobody is near is not attempted.
            plugin.getLogger().warning(() -> "End pillar at " + centre.x() + ", " + centre.z() + " in "
                    + world.getName() + " not restored: its chunk is not loaded.");
            return true;
        }
        if (!plugin.getServer().isOwnedByCurrentRegion(world, space.minX() >> 4, space.minZ() >> 4,
                (space.maxX() - 1) >> 4, (space.maxZ() - 1) >> 4)) {
            return false;
        }
        EndTowerRules.Plan plan = EndTowerRules.plan(new LivePillar(world, centre));
        switch (plan.outcome()) {
            case NO_PILLAR -> plugin.getLogger().info(() -> "No End pillar top found at " + centre.x() + ", "
                    + centre.z() + " in " + world.getName() + "; nothing restored there.");
            case OCCUPIED -> {
                if (reportedWait.compareAndSet(false, true)) {
                    plugin.getLogger().info(() -> "The pillar at " + centre.x() + ", " + centre.z() + " in "
                            + world.getName() + " has a player in its cage space; it is restored once they leave.");
                }
            }
            case INTACT -> {
            }
            case RESTORE -> place(world, centre, plan);
        }
        return plan.settled();
    }

    private void place(World world, EndTowerRules.Centre centre, EndTowerRules.Plan plan) {
        int height = plan.height();
        for (EndTowerRules.Bar bar : plan.bars()) {
            // MultipleFacing, not GlassPane: newer servers give iron bars a block data type of their own.
            MultipleFacing bars = (MultipleFacing) Material.IRON_BARS.createBlockData();
            bars.setFace(BlockFace.NORTH, bar.north());
            bars.setFace(BlockFace.SOUTH, bar.south());
            bars.setFace(BlockFace.EAST, bar.east());
            bars.setFace(BlockFace.WEST, bar.west());
            world.getBlockAt(centre.x() + bar.dx(), height + bar.dy(), centre.z() + bar.dz())
                    .setBlockData(bars, false);
        }
        if (plan.crystal()) {
            world.spawn(new Location(world, centre.x() + 0.5D, height + 1, centre.z() + 0.5D), EnderCrystal.class,
                    crystal -> {
                        crystal.setShowingBottom(true);
                        crystal.setBeamTarget(null);
                    });
        }
        plugin.getLogger().info(() -> "Restored the End pillar at " + centre.x() + ", " + centre.z() + " in "
                + world.getName() + ": " + (plan.crystal() ? "crystal" : "no crystal") + ", "
                + plan.bars().size() + " bar(s).");
    }

    /** One pillar as the live world shows it. Read only on the region owning its centre. */
    private static final class LivePillar implements EndTowerRules.Pillar {

        private final World world;
        private final EndTowerRules.Centre centre;

        LivePillar(World world, EndTowerRules.Centre centre) {
            this.world = world;
            this.centre = centre;
        }

        @Override
        public boolean bedrockAt(int y) {
            return world.getBlockAt(centre.x(), y, centre.z()).getType() == Material.BEDROCK;
        }

        @Override
        public boolean playerInside(int height) {
            EndTowerRules.Bounds space = EndTowerRules.occupiedSpace(centre, height);
            BoundingBox box = new BoundingBox(space.minX(), space.minY(), space.minZ(),
                    space.maxX(), space.maxY(), space.maxZ());
            return !world.getNearbyEntities(box, entity -> entity instanceof Player player
                    && player.getGameMode() != GameMode.SPECTATOR).isEmpty();
        }

        @Override
        public boolean crystalPresent(int height) {
            BoundingBox top = new BoundingBox(centre.x(), height + 1, centre.z(),
                    centre.x() + 1, height + 2, centre.z() + 1);
            return !world.getNearbyEntities(top, EnderCrystal.class::isInstance).isEmpty();
        }

        @Override
        public boolean crystalSpaceClear(int height) {
            Block base = world.getBlockAt(centre.x(), height + 1, centre.z());
            Material type = base.getType();
            boolean baseClear = type.isAir() || type == Material.FIRE || type == Material.SOUL_FIRE;
            return baseClear && world.getBlockAt(centre.x(), height + 2, centre.z()).getType().isAir();
        }

        @Override
        public boolean empty(int dx, int y, int dz) {
            return world.getBlockAt(centre.x() + dx, y, centre.z() + dz).getType().isAir();
        }
    }
}
