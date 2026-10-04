package com.ninja6.antispeedrun.listeners;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bed and Respawn Anchor boss damage block's decisions (#39). The listener needs a server and is not run. */
class BedAnchorDamageRulesTest {

    private static PluginConfig with(boolean enabled, boolean block) {
        PluginConfig base = PluginConfig.defaults();
        PluginConfig.AntiCheese a = base.antiCheese();
        return new PluginConfig(base.profile(), base.dimensionGates(), base.itemProgression(),
                base.trimProgression(), base.idleReminder(), base.progressCard(),
                base.journeyBook(), base.bossScaling(),
                new PluginConfig.AntiCheese(enabled, block,
                        a.capSingleHitBossDamage(), a.maxSingleHitBossDamage(),
                        a.blockEarlyEyeThrowing(), a.earlyEyeRejectionMessage(), a.blockExitPortalCrystalPlace(),
                        a.blockGatewayPreDragon(), a.outerEndRadius(), a.outerEndPollSeconds()),
                base.villagerProgression(), List.of());
    }

    @Test
    @DisplayName("the shipped configuration leaves the block off")
    void offByDefault() {
        assertFalse(BedAnchorDamageRules.armed(PluginConfig.defaults()));
    }

    @Test
    @DisplayName("the block needs the master switch and its own switch")
    void armedNeedsBoth() {
        assertTrue(BedAnchorDamageRules.armed(with(true, true)));
        assertFalse(BedAnchorDamageRules.armed(with(false, true)));
        assertFalse(BedAnchorDamageRules.armed(with(true, false)));
    }
}
