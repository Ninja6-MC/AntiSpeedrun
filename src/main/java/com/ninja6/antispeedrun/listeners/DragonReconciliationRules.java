package com.ninja6.antispeedrun.listeners;

import java.util.Locale;

/**
 * Single-battle reconciliation (#56, Task 6.1.5): the Bukkit-free decisions that keep vanilla's
 * one-dragon victory sequence from firing while a multi-dragon fight is still going.
 *
 * <h2>Why the primary is held, not the secondaries</h2>
 *
 * Vanilla's {@code EndDragonFight} tracks one dragon by UUID. Its victory sequence (exit portal, egg,
 * gateway, {@code previouslyKilled = true}) runs from {@code setDragonKilled}, which acts only for
 * that tracked dragon, and the 12,000 XP first-kill award is paid only by a dragon the fight is
 * attached to. A secondary spawned by the plugin is neither, so its own death ends nothing. The
 * dragon that <em>does</em> end the fight is the primary, and a party that kills it first would open
 * the portal over four living secondaries. So the primary is the one refused: while any secondary
 * lives, its death is cancelled at one health and its {@code DYING} phase is refused. When the last
 * secondary falls the primary can die, and vanilla then runs its victory sequence exactly once, at
 * the right moment, with its own portal, egg and XP.
 *
 * <h2>Crystals</h2>
 *
 * Secondaries do not heal from End crystals. The pillar crystals are sized for one dragon; letting
 * every secondary draw on them makes each crystal a party destroys worth several dragons' healing,
 * so the fight would get easier per dragon as the party grows. Without crystal healing each
 * secondary is a fixed 200 health however the crystal race goes, and the crystals stay the
 * primary's mechanic. The crystal beam may still be drawn to a secondary by the client; it heals
 * nothing.
 */
public final class DragonReconciliationRules {

    /** Vanilla's XP for a dragon killed after the first: {@code EnderDragon#getExpReward}'s 500. */
    public static final int REPEAT_KILL_EXPERIENCE = 500;

    /** Balanced XP for each secondary dragon when configured. */
    public static final int BALANCED_SECONDARY_EXPERIENCE = 1_000;

    private DragonReconciliationRules() {
    }

    /**
     * Whether a dragon's death must be refused.
     *
     * @param secondary         whether the dragon carries the secondary tag
     * @param livingSecondaries secondaries in the same world still known to be alive
     */
    public static boolean refusesDeath(boolean secondary, int livingSecondaries) {
        return !secondary && livingSecondaries > 0;
    }

    /**
     * The XP a dying secondary drops. Balanced XP awards 1,000; otherwise the existing 500 cap
     * remains. A secondary the server attached to the battle must not pay the 12,000 first-kill
     * award before the fight is over; one with {@code doMobLoot} off still drops nothing.
     */
    public static int secondaryExperience(int vanilla, boolean balancedXp) {
        if (vanilla <= 0) {
            return 0;
        }
        return balancedXp ? BALANCED_SECONDARY_EXPERIENCE
                : Math.min(vanilla, REPEAT_KILL_EXPERIENCE);
    }

    /**
     * Whether a regain of health must be cancelled.
     *
     * @param secondary  whether the dragon carries the secondary tag
     * @param reasonName {@code RegainReason#name()}
     */
    public static boolean refusesRegain(boolean secondary, String reasonName) {
        return secondary && reasonName != null
                && reasonName.toUpperCase(Locale.ROOT).equals("ENDER_CRYSTAL");
    }

    /**
     * Whether a secondary leaving the world is still alive. An unloaded chunk or a logged-out rider
     * keeps it saved in the world; every other cause is the end of it.
     *
     * @param causeName {@code EntityRemoveEvent.Cause#name()}
     */
    public static boolean survivesRemoval(String causeName) {
        String cause = causeName == null ? "" : causeName.toUpperCase(Locale.ROOT);
        return cause.equals("UNLOAD") || cause.equals("PLAYER_QUIT");
    }
}
