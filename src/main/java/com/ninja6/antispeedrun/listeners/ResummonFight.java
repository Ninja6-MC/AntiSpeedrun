package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.UUID;

/**
 * One resummoned dragon fight (#20, Task 6.1.3): the dragon the four-crystal ritual brought back,
 * the reinforcement window that scales the fight around it, and the inactivity timer for its
 * resummon-only exit lock.
 *
 * <p>The first fight's window is tied to {@code DragonBattle#hasBeenPreviouslyKilled()}, which is
 * {@code true} for every fight after the first and so cannot say whether a resummoned one is still
 * on. A resummoned fight is instead identified by its primary: it is on from the moment that dragon
 * spawns until it dies or is removed, or a later resummon replaces it.
 *
 * <h2>Folia</h2>
 *
 * The primary spawns, dies and is removed on its own region; the window's countdown and portal
 * timer run on the {@code (0, 0)} region. The end of the fight is therefore a {@code volatile} flag,
 * written by the primary's region and read by those tasks, and never a read of the entity itself.
 * Player hits and portal release synchronize on a private monitor; neither thread touches the
 * other's entity or block state.
 *
 * <h2>Across a restart</h2>
 *
 * The fight and its timer are not persisted. A resummoned primary loaded from disk after a restart
 * is not a fresh spawn and opens no window, so a fight already reinforced is never reinforced twice;
 * a restart during the countdown costs that fight its reinforcements. The exit lock has its own
 * durable marker: after a restart the listener opens that portal rather than recreating this timer.
 */
public final class ResummonFight {

    private final UUID primary;
    private final ReinforcementWindow window = new ReinforcementWindow();
    private final Object exitMonitor = new Object();
    private long lastPlayerDamageMillis = System.currentTimeMillis();
    private volatile boolean over;
    private volatile boolean exitReleased;

    /** @param primary the UUID of the dragon the ritual summoned */
    public ResummonFight(UUID primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    /** The dragon the ritual summoned. */
    public UUID primary() {
        return primary;
    }

    /** This fight's reinforcement window. It starts {@link ReinforcementWindow.Phase#IDLE}. */
    public ReinforcementWindow window() {
        return window;
    }

    /** Whether the resummoned primary is still in the fight. Legal from any thread. */
    public boolean ongoing() {
        return !over;
    }

    /** Records a successful player hit on either dragon in this fight. */
    public void playerDamagedDragon(long nowMillis) {
        synchronized (exitMonitor) {
            lastPlayerDamageMillis = nowMillis;
        }
    }

    /** Whether inactivity has unlocked this fight's exit portal. */
    public boolean releaseExitAfter(long nowMillis, int minutes) {
        synchronized (exitMonitor) {
            if (exitReleased || over || minutes <= 0
                    || nowMillis - lastPlayerDamageMillis < minutes * 60_000L) {
                return false;
            }
            exitReleased = true;
            return true;
        }
    }

    public boolean exitReleased() {
        return exitReleased;
    }

    /** Explicitly unlocks the exit when the setting is turned off during a fight. */
    public boolean releaseExit() {
        synchronized (exitMonitor) {
            if (exitReleased || over) {
                return false;
            }
            exitReleased = true;
            return true;
        }
    }

    /**
     * Records that {@code dragon} died or was removed, which ends the fight if it is this fight's
     * primary.
     *
     * @return {@code true} if this ended the fight
     */
    public boolean end(UUID dragon) {
        synchronized (exitMonitor) {
            if (!primary.equals(Objects.requireNonNull(dragon, "dragon")) || over) {
                return false;
            }
            over = true;
            return true;
        }
    }

    /** Ends the fight whatever its primary is doing: a later resummon has replaced it. */
    public void supersede() {
        synchronized (exitMonitor) {
            over = true;
        }
    }
}
