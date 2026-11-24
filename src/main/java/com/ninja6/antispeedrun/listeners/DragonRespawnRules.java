package com.ninja6.antispeedrun.listeners;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.IntPredicate;

import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * When an entry into the End starts vanilla's dragon respawn (#271), kept apart from the server
 * types it is decided about so it can be tested without a server. {@link DragonRespawnOnEntry}
 * applies it.
 */
public final class DragonRespawnRules {

    /**
     * The four ritual positions on the exit portal's bedrock rim, as {@code {dx, dz}} from its
     * centre: where players set the four End Crystals of a resummon.
     */
    public static final List<int[]> RITUAL_OFFSETS = List.of(
            new int[] {3, 0}, new int[] {-3, 0}, new int[] {0, 3}, new int[] {0, -3});

    /** How far above and below the battle's portal height the rim is looked for. */
    public static final int RIM_SEARCH = 4;

    private DragonRespawnRules() {
    }

    /**
     * The height of the rim's top block in one column: the highest bedrock with something passable
     * above it, within {@link #RIM_SEARCH} of {@code portalY}. Empty when there is none, such as a
     * rim a player has broken or built over.
     *
     * @param bedrock  whether the block at a height is bedrock
     * @param passable whether the block at a height can hold a crystal's body
     */
    public static OptionalInt rimTop(int portalY, IntPredicate bedrock, IntPredicate passable) {
        for (int y = portalY + RIM_SEARCH; y >= portalY - RIM_SEARCH; y--) {
            if (bedrock.test(y) && passable.test(y + 1)) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Whether respawn on entry is on: {@code boss-scaling.respawn-on-entry.enabled} under the
     * {@code boss-scaling.enabled} master switch. Both ship off.
     */
    public static boolean armed(PluginConfig config) {
        PluginConfig.BossScaling scaling = Objects.requireNonNull(config, "config").bossScaling();
        return scaling.enabled() && scaling.respawnOnEntry().enabled();
    }

    /**
     * Whether a player in this game mode takes part at all: survival or adventure. Creative and
     * spectator players are staff tools; they never wake a dragon. The respawn cooldown is a
     * separate check, {@link RespawnCooldown}.
     */
    public static boolean eligible(String gameModeName) {
        String mode = gameModeName == null ? "" : gameModeName.toUpperCase(Locale.ROOT);
        return mode.equals("SURVIVAL") || mode.equals("ADVENTURE");
    }

    /**
     * Whether the battle, as read on the region that owns it, needs a respawn: it exists, its dragon
     * has been killed before, no dragon of it is alive, and no respawn is already running. Read
     * fresh each time, so the state after a restart is the battle's own rather than anything this
     * plugin remembered.
     *
     * @param respawnPhaseName {@code DragonBattle.RespawnPhase#name()}, or null without a battle
     */
    public static boolean needsRespawn(boolean battle, boolean previouslyKilled, boolean dragonAlive,
                                       String respawnPhaseName) {
        return battle && previouslyKilled && !dragonAlive && "NONE".equals(respawnPhaseName);
    }
}
