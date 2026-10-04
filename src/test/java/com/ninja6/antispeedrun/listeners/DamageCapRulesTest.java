package com.ninja6.antispeedrun.listeners;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The single-hit boss damage cap's decisions (#24). The listener needs a server and is not run. */
class DamageCapRulesTest {

    private static PluginConfig with(boolean enabled, boolean cap, double max) {
        PluginConfig base = PluginConfig.defaults();
        PluginConfig.AntiCheese a = base.antiCheese();
        return new PluginConfig(base.profile(), base.dimensionGates(), base.itemProgression(),
                base.trimProgression(), base.idleReminder(), base.progressCard(),
                base.journeyBook(), base.bossScaling(),
                new PluginConfig.AntiCheese(enabled, a.blockBedAnchorBossDamage(), cap, max,
                        a.blockEarlyEyeThrowing(), a.earlyEyeRejectionMessage(),
                        a.blockExitPortalCrystalPlace(), a.blockGatewayPreDragon(),
                        a.outerEndRadius(), a.outerEndPollSeconds()),
                base.villagerProgression(), List.of());
    }

    @Test
    @DisplayName("the shipped configuration leaves the cap off")
    void offByDefault() {
        assertFalse(DamageCapRules.armed(PluginConfig.defaults()));
    }

    @Test
    @DisplayName("the cap needs the master switch, its own switch and a positive limit")
    void armedNeedsAll() {
        assertTrue(DamageCapRules.armed(with(true, true, 12.0D)));
        assertFalse(DamageCapRules.armed(with(false, true, 12.0D)));
        assertFalse(DamageCapRules.armed(with(true, false, 12.0D)));
        assertFalse(DamageCapRules.armed(with(true, true, 0.0D)));
    }

    @Test
    @DisplayName("a hit at or under the cap is not touched")
    void atCapNotExceeded() {
        assertFalse(DamageCapRules.exceeds(12.0D, 12.0D));
        assertFalse(DamageCapRules.exceeds(3.0D, 12.0D));
        assertTrue(DamageCapRules.exceeds(12.5D, 12.0D));
    }

    @Test
    @DisplayName("scaling the base lands the final damage on the cap, not above it")
    void scalesAgainstFinal() {
        // A 400 point blast reduced to 100 final by resistance: base 400, final 100, cap 12.
        double base = DamageCapRules.scaledBase(400.0D, 100.0D, 12.0D);
        assertEquals(48.0D, base, 1e-9);
        assertEquals(12.0D, base * (100.0D / 400.0D), 1e-9);
    }

    @Test
    @DisplayName("a 50 TNT minecart stack cannot do more than the cap")
    void tntStack() {
        double base = DamageCapRules.scaledBase(50 * 70.0D, 50 * 70.0D, 12.0D);
        assertEquals(12.0D, base, 1e-9);
    }

    @Test
    @DisplayName("a non-positive final damage leaves the base alone")
    void zeroFinal() {
        assertEquals(5.0D, DamageCapRules.scaledBase(5.0D, 0.0D, 12.0D));
    }
}
