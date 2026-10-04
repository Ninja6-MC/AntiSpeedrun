package com.ninja6.antispeedrun.listeners;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The exit portal crystal block's decisions (#25). The listener needs a server and is not run. */
class ExitPortalCrystalRulesTest {

    private static PluginConfig with(boolean enabled, boolean block) {
        PluginConfig base = PluginConfig.defaults();
        PluginConfig.AntiCheese a = base.antiCheese();
        return new PluginConfig(base.profile(), base.dimensionGates(), base.itemProgression(),
                base.trimProgression(), base.idleReminder(), base.progressCard(),
                base.journeyBook(), base.bossScaling(),
                new PluginConfig.AntiCheese(enabled, a.blockBedAnchorBossDamage(),
                        a.capSingleHitBossDamage(), a.maxSingleHitBossDamage(),
                        a.blockEarlyEyeThrowing(), a.earlyEyeRejectionMessage(), block,
                        a.blockGatewayPreDragon(), a.outerEndRadius(), a.outerEndPollSeconds()),
                base.villagerProgression(), List.of());
    }

    @Test
    @DisplayName("the shipped configuration leaves the block off")
    void offByDefault() {
        assertFalse(ExitPortalCrystalRules.armed(PluginConfig.defaults()));
    }

    @Test
    @DisplayName("the block needs the master switch and its own switch")
    void armedNeedsBoth() {
        assertTrue(ExitPortalCrystalRules.armed(with(true, true)));
        assertFalse(ExitPortalCrystalRules.armed(with(false, true)));
        assertFalse(ExitPortalCrystalRules.armed(with(true, false)));
    }

    @Test
    @DisplayName("only the centre column matches")
    void centreOnly() {
        assertTrue(ExitPortalCrystalRules.isCentreColumn(0, 0));
    }

    @Test
    @DisplayName("the four resummon ritual positions never match")
    void ritualPositionsAllowed() {
        assertFalse(ExitPortalCrystalRules.isCentreColumn(1, 0));
        assertFalse(ExitPortalCrystalRules.isCentreColumn(-1, 0));
        assertFalse(ExitPortalCrystalRules.isCentreColumn(0, 1));
        assertFalse(ExitPortalCrystalRules.isCentreColumn(0, -1));
    }
}
