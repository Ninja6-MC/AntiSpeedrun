package com.ninja6.antispeedrun.listeners;

import java.util.Locale;

/**
 * Secondary dragon AI and boss bars (#21, Task 6.1.4): the Bukkit-free decisions.
 *
 * <h2>Why a secondary has to be told to fight</h2>
 *
 * Every Ender Dragon starts in the {@code HOVER} phase, which holds it in place and never leaves on
 * its own. Vanilla's battle moves the dragon it creates into the holding pattern ({@code CIRCLING})
 * itself; a dragon spawned by a plugin, like one spawned by {@code /summon}, is not the battle's and
 * would hover where it appeared for ever. A secondary is therefore put into {@code CIRCLING} when it
 * spawns, and again when it loads still hovering (one spawned before this change). From there the
 * vanilla phase graph runs unchanged: it circles the pillars around the podium, strafes the nearest
 * player with fireballs, lands on the podium to breathe and charge, and takes off again. Every one of
 * those phases already copes with a dragon that no battle owns, which is what {@code /summon} relies
 * on; with no battle it simply sees no crystals.
 *
 * <h2>Why every viewer has bars of their own</h2>
 *
 * Vanilla's boss bar belongs to the battle and follows the battle's dragon only, so secondaries have
 * none. A shared bar per secondary would be written from the dragon's region (its health) and from
 * each viewer's region (showing and hiding it), and the server's bar keeps its viewers in a plain
 * set: on Folia that is a concurrent modification. Instead each dragon publishes its health on its
 * own region to a {@link SecondaryBarBoard}, and each viewer keeps a {@link ViewerBars} of their own
 * that only their own region ever touches.
 */
public final class SecondaryDragonRules {

    /** Vanilla's {@code EndDragonFight} shows its bar to players within this many blocks of the centre. */
    public static final double BOSS_BAR_RANGE = 192.0D;

    /** The height of the centre vanilla measures that range from. */
    public static final double BOSS_BAR_CENTRE_Y = 128.0D;

    /** The phase a secondary is moved into to start fighting: vanilla's holding pattern. */
    public static final String FIGHTING_PHASE = "CIRCLING";

    /** How often, in ticks, a secondary publishes its health and a viewer refreshes their bars. */
    public static final long BAR_INTERVAL_TICKS = 5L;

    private SecondaryDragonRules() {
    }

    /**
     * Whether a secondary in this phase must be moved into {@link #FIGHTING_PHASE}. Only
     * {@code HOVER} is: it is the phase every dragon starts in and never leaves by itself. Every
     * other phase, {@code DYING} included, is the vanilla fight in progress and is left alone.
     *
     * @param phaseName {@code EnderDragon.Phase#name()}, or {@code null} if unknown
     */
    public static boolean needsFightingPhase(String phaseName) {
        return phaseName != null && phaseName.toUpperCase(Locale.ROOT).equals("HOVER");
    }

    /**
     * Whether a player sees the secondaries' boss bars: alive, in the End the dragons are in, and
     * within {@link #BOSS_BAR_RANGE} of {@code (0, 128, 0)}, which is vanilla's rule for its own bar.
     */
    public static boolean seesBossBars(boolean inFightWorld, boolean dead, double x, double y, double z) {
        if (!inFightWorld || dead) {
            return false;
        }
        double dy = y - BOSS_BAR_CENTRE_Y;
        return x * x + dy * dy + z * z <= BOSS_BAR_RANGE * BOSS_BAR_RANGE;
    }

    /** A bar's fill for {@code health} of {@code maxHealth}, clamped to {@code [0, 1]}. */
    public static float progress(double health, double maxHealth) {
        if (!(maxHealth > 0.0D) || !(health > 0.0D)) {
            return 0.0F;
        }
        return (float) Math.min(1.0D, health / maxHealth);
    }
}
