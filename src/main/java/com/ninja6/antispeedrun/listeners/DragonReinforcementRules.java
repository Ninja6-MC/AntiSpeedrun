package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * Section 6's reinforcement window (#37, Task 6.1.1): who counts toward the party, how many dragons
 * that party earns, and where the extra ones appear.
 *
 * <p>The Bukkit-free half of {@link BossCombatListener}, split out for the reason every rules class
 * in this package is: the listener needs a running server, and everything it decides is decided
 * here.
 *
 * <h2>The window</h2>
 *
 * The primary dragon is never held back. Vanilla activates it the moment the first player enters
 * the End, and a solo player fights exactly that one dragon. What the window adds is a count taken
 * {@code battle-prep-seconds} later: every party member standing on the main island by then is
 * counted, and the difference between the dragons that party earns and the one already flying is
 * spawned at that moment.
 */
public final class DragonReinforcementRules {

    /** Horizontal radius, in blocks from {@code (0, 0)}, inside which a player counts toward the party. */
    public static final int MAIN_ISLAND_RADIUS = 300;

    /**
     * Horizontal distance from {@code (0, 0)} at which secondary dragons appear. Inside the ring of
     * obsidian pillars, which stand 40 to 50 blocks out, so every spawn point sits in chunks the
     * party already has loaded.
     */
    public static final double SECONDARY_SPAWN_RADIUS = 32.0D;

    /** Height at which secondary dragons appear: above the pillars, where the primary circles. */
    public static final double SECONDARY_SPAWN_Y = 100.0D;

    private DragonReinforcementRules() {
    }

    /** Whether the reinforcement window runs at all: {@code boss-scaling.enabled}. */
    public static boolean armed(PluginConfig config) {
        return Objects.requireNonNull(config, "config").bossScaling().enabled();
    }

    /**
     * Whether a player standing at {@code (x, z)} is on the main island. The boundary itself is
     * inside.
     */
    public static boolean onMainIsland(double x, double z) {
        return x * x + z * z <= (double) MAIN_ISLAND_RADIUS * MAIN_ISLAND_RADIUS;
    }

    /**
     * Whether a player counts toward the party.
     *
     * @param inBattleWorld whether the player is in the End world the window was opened for
     * @param dead          whether the player is dead, and so waiting on the respawn screen
     * @param gameModeName  {@code GameMode#name()}; creative and spectator players are not fighting
     *                      and are not counted
     * @param x             the player's x
     * @param z             the player's z
     */
    public static boolean countsTowardParty(boolean inBattleWorld, boolean dead, String gameModeName,
            double x, double z) {
        if (!inBattleWorld || dead) {
            return false;
        }
        String mode = gameModeName == null ? "" : gameModeName.toUpperCase(Locale.ROOT);
        if (mode.equals("CREATIVE") || mode.equals("SPECTATOR")) {
            return false;
        }
        return onMainIsland(x, z);
    }

    /**
     * How many dragons, primary included, a party of {@code partySize} fights.
     *
     * <p>{@code max(1, min(max-dragons, round(partySize * multiplier)))}, rounded half up, with one
     * exception: a party of one (or none) always fights exactly one dragon, whatever the multiplier.
     * A multiplier above {@code 1.0} is valid, and without the exception it would give a solo player
     * more than the one dragon Task 6.1.1 promises them. The
     * configurable rounding modes are Task 6.1.2 (#38); until then {@code rounding-mode} is read by
     * nothing and every mode behaves as {@code HALF_UP}, the shipped default. With
     * {@code multi-dragon.enabled: false} the answer is always one.
     *
     * @param partySize players counted on the main island; zero or less yields one
     */
    public static int dragonCount(int partySize, PluginConfig.MultiDragon multiDragon) {
        Objects.requireNonNull(multiDragon, "multiDragon");
        if (!multiDragon.enabled() || partySize <= 1) {
            return 1;
        }
        long scaled = (long) Math.floor(partySize * multiDragon.multiplier() + 0.5D);
        return (int) Math.max(1L, Math.min(multiDragon.maxDragons(), scaled));
    }

    /** How many dragons the window spawns on top of the primary: {@link #dragonCount} less one. */
    public static int secondaryCount(int partySize, PluginConfig.MultiDragon multiDragon) {
        return dragonCount(partySize, multiDragon) - 1;
    }

    /**
     * Where each secondary dragon appears: evenly spaced on a ring of
     * {@link #SECONDARY_SPAWN_RADIUS} around {@code (0, 0)}, facing the centre.
     *
     * @param count how many points; zero yields none
     */
    public static List<SpawnPoint> spawnPoints(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative: " + count);
        }
        List<SpawnPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = 2.0D * Math.PI * i / count;
            double x = -Math.sin(angle) * SECONDARY_SPAWN_RADIUS;
            double z = Math.cos(angle) * SECONDARY_SPAWN_RADIUS;
            // Minecraft's yaw: 0 faces +z and 90 faces -x, so a point at this angle faces the centre
            // by turning half a circle.
            float yaw = (float) ((Math.toDegrees(angle) + 180.0D) % 360.0D);
            points.add(new SpawnPoint(x, SECONDARY_SPAWN_Y, z, yaw));
        }
        return List.copyOf(points);
    }

    /** One secondary dragon's spawn location in the End world, and the way it faces. */
    public record SpawnPoint(double x, double y, double z, float yaw) {

        /** The chunk x this point lies in, which names the region its spawn must run on. */
        public int chunkX() {
            return (int) Math.floor(x) >> 4;
        }

        /** The chunk z this point lies in. */
        public int chunkZ() {
            return (int) Math.floor(z) >> 4;
        }
    }
}
