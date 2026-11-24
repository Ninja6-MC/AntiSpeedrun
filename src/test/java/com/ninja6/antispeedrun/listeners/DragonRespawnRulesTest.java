package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;

/** When an End entry starts vanilla's dragon respawn (#271). */
class DragonRespawnRulesTest {

    private static PluginConfig with(boolean scaling, boolean respawn) {
        PluginConfig base = PluginConfig.defaults();
        PluginConfig.BossScaling b = base.bossScaling();
        return new PluginConfig(base.profile(), base.dimensionGates(), base.itemProgression(),
                base.trimProgression(), base.idleReminder(), base.progressCard(),
                base.journeyBook(),
                new PluginConfig.BossScaling(scaling, b.scaleResummonedDragons(), b.battlePrepSeconds(),
                        b.multiDragon(), b.exitPortalEgg(), b.skullDropChance(),
                        b.exitPortalLockDuringBattle(), b.exitPortalLockReleaseMinutes(),
                        new PluginConfig.RespawnOnEntry(respawn)),
                base.antiCheese(), base.villagerProgression(), List.of());
    }

    @Test
    @DisplayName("ships off, and needs both its own switch and boss-scaling.enabled")
    void armed() {
        assertFalse(PluginConfig.defaults().bossScaling().respawnOnEntry().enabled());
        assertFalse(DragonRespawnRules.armed(PluginConfig.defaults()));
        assertTrue(DragonRespawnRules.armed(with(true, true)));
        assertFalse(DragonRespawnRules.armed(with(true, false)));
        assertFalse(DragonRespawnRules.armed(with(false, true)));
    }

    @Test
    @DisplayName("survival and adventure players trigger it; creative, spectator and cooling-down players do not")
    void triggers() {
        assertTrue(DragonRespawnRules.triggers("SURVIVAL", false));
        assertTrue(DragonRespawnRules.triggers("adventure", false));
        assertFalse(DragonRespawnRules.triggers("CREATIVE", false));
        assertFalse(DragonRespawnRules.triggers("SPECTATOR", false));
        assertFalse(DragonRespawnRules.triggers(null, false));
        assertFalse(DragonRespawnRules.triggers("SURVIVAL", true));
    }

    @Test
    @DisplayName("only a battle won before, with no dragon alive and no respawn running, needs one")
    void needsRespawn() {
        assertTrue(DragonRespawnRules.needsRespawn(true, true, false, "NONE"));
        assertFalse(DragonRespawnRules.needsRespawn(false, true, false, "NONE"), "no battle");
        assertFalse(DragonRespawnRules.needsRespawn(true, false, false, "NONE"), "first dragon never killed");
        assertFalse(DragonRespawnRules.needsRespawn(true, true, true, "NONE"), "a dragon is alive");
        for (String running : List.of("START", "PREPARING_TO_SUMMON_PILLARS", "SUMMONING_PILLARS",
                "SUMMONING_DRAGON", "END")) {
            assertFalse(DragonRespawnRules.needsRespawn(true, true, false, running), running);
        }
        assertFalse(DragonRespawnRules.needsRespawn(true, true, false, null));
    }

    @Test
    @DisplayName("the ritual positions are the four rim blocks three from the portal's centre")
    void ritualOffsets() {
        assertEquals(4, DragonRespawnRules.RITUAL_OFFSETS.size());
        for (int[] offset : DragonRespawnRules.RITUAL_OFFSETS) {
            assertEquals(3, Math.abs(offset[0]) + Math.abs(offset[1]));
            assertTrue(offset[0] == 0 || offset[1] == 0);
        }
    }

    @Test
    @DisplayName("the rim top is the highest bedrock near the portal with room above it")
    void rimTop() {
        // Bedrock at 60 and 61, air from 62.
        assertEquals(java.util.OptionalInt.of(61),
                DragonRespawnRules.rimTop(61, y -> y == 60 || y == 61, y -> y >= 62));
        // Built over: bedrock with a block on top is not a ritual position.
        assertEquals(java.util.OptionalInt.empty(),
                DragonRespawnRules.rimTop(61, y -> y == 61, y -> y >= 70));
        // Broken away, or too far from the portal height.
        assertEquals(java.util.OptionalInt.empty(), DragonRespawnRules.rimTop(61, y -> false, y -> true));
        assertEquals(java.util.OptionalInt.empty(),
                DragonRespawnRules.rimTop(61, y -> y == 40, y -> y == 41));
    }

    @Test
    @DisplayName("with no cooldown, nobody is ever waiting")
    void noCooldown() {
        assertEquals(java.util.Optional.empty(),
                RespawnCooldown.NONE.remaining(UUID.randomUUID(), System.currentTimeMillis()));
    }
}
