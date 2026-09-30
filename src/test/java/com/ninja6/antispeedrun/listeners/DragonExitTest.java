package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class DragonExitTest {

    @Test
    void inactivityReleasesOnlyAfterTheConfiguredQuietPeriod() {
        ResummonFight fight = new ResummonFight(UUID.randomUUID());
        fight.playerDamagedDragon(1_000L);
        assertFalse(fight.releaseExitAfter(1_000L + 9 * 60_000L, 10));
        assertTrue(fight.releaseExitAfter(1_000L + 10 * 60_000L, 10));
        assertTrue(fight.exitReleased());
        assertFalse(fight.releaseExitAfter(1_000L + 11 * 60_000L, 10));
    }

    @Test
    void furtherDamageResetsTheEscapeTimerAndZeroDisablesIt() {
        ResummonFight fight = new ResummonFight(UUID.randomUUID());
        fight.playerDamagedDragon(1_000L);
        fight.playerDamagedDragon(1_000L + 9 * 60_000L);
        assertFalse(fight.releaseExitAfter(1_000L + 10 * 60_000L, 10));
        assertFalse(fight.releaseExitAfter(Long.MAX_VALUE, 0));
        assertTrue(fight.releaseExitAfter(1_000L + 19 * 60_000L, 10));
    }

    @Test
    void anEndedFightCannotReleaseAgain() {
        UUID primary = UUID.randomUUID();
        ResummonFight fight = new ResummonFight(primary);
        assertTrue(fight.end(primary));
        assertFalse(fight.releaseExitAfter(Long.MAX_VALUE, 10));
    }

    @Test
    void headChanceIncludesTheLowerBoundAndExcludesTheUpperBound() {
        assertFalse(DragonExitRules.dropsHead(0.0D, 0.0D));
        assertTrue(DragonExitRules.dropsHead(0.05D, 0.0D));
        assertTrue(DragonExitRules.dropsHead(0.05D, 0.049D));
        assertFalse(DragonExitRules.dropsHead(0.05D, 0.05D));
        assertTrue(DragonExitRules.dropsHead(1.0D, 0.999D));
    }

    @Test
    void basinCoversPortalOpeningButNotItsBedrockRim() {
        assertTrue(DragonExitRules.inBasin(0, 0));
        assertTrue(DragonExitRules.inBasin(2, 1));
        assertFalse(DragonExitRules.inBasin(2, 2));
        assertFalse(DragonExitRules.inBasin(3, 0));
    }
}
