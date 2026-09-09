package com.ninja6.antispeedrun;

import java.io.StringReader;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin.ReloadOutcome;
import com.ninja6.antispeedrun.config.ConfigLoadException;
import com.ninja6.antispeedrun.config.MapConfigSection;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.UnenforceableGateException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The #91 decision, asserted where it is actually made.
 *
 * <p>{@code onEnable} itself needs a running server, so what is exercised here is the pair the
 * decision is expressed in and which {@code onEnable} does nothing but consult: the classification
 * of a rejected reload, and whether that outcome lets the plugin keep running on
 * {@link PluginConfig#defaults()}. The one thing left untested is the two lines of
 * {@code onEnable} that call them.
 *
 * <p>The principle: the plugin refuses to start when {@code config.yml} describes gating it could
 * not enforce, and falls back to the shipped defaults only when the file describes no gating at
 * all. Both halves matter — dropping the first turns one typo into a server with item gating off
 * (the failure #83 exists to prevent), and dropping the second refuses to boot an operator's server
 * over a stray character (the failure audit finding R-11 objected to).
 */
class StartupConfigPolicyTest {

    private static PluginConfig parse(String document) throws ConfigLoadException {
        Object root = new Yaml().load(new StringReader(document));
        if (!(root instanceof Map<?, ?> map)) {
            throw new ConfigLoadException("root is not a mapping");
        }
        return PluginConfig.from(MapConfigSection.of(map));
    }

    @Test
    @DisplayName("a file naming a gate this server cannot enforce stops the plugin")
    void anUnenforceableGateStopsStartup() {
        ReloadOutcome outcome = AntiSpeedrunPlugin.rejectionOutcome(false,
                new UnenforceableGateException("dimension-gates.nether.require-advancements: ..."));

        assertEquals(ReloadOutcome.GATE_UNENFORCEABLE, outcome);
        assertTrue(outcome.stopsStartup());
        assertTrue(outcome.startupRefusal().contains("will not start"),
                outcome.startupRefusal());
    }

    @Test
    @DisplayName("a file that cannot be parsed at all still boots, on the shipped defaults")
    void anUnreadableFileKeepsTheFallback() {
        ReloadOutcome outcome = AntiSpeedrunPlugin.rejectionOutcome(false,
                new ConfigLoadException("config.yml could not be read: bad indentation"));

        assertEquals(ReloadOutcome.CONFIG_REJECTED, outcome);
        assertFalse(outcome.stopsStartup(), "R-11's landing zone must survive");
        assertThrows(IllegalStateException.class, outcome::startupRefusal);
    }

    @Test
    @DisplayName("a tier collision still stops the plugin, whatever else was rejected")
    void aCollisionStopsStartup() {
        ReloadOutcome outcome = AntiSpeedrunPlugin.rejectionOutcome(true, null);

        assertEquals(ReloadOutcome.GATES_REJECTED, outcome);
        assertTrue(outcome.stopsStartup());
        assertTrue(outcome.startupRefusal().contains("tier collision"), outcome.startupRefusal());
    }

    @Test
    @DisplayName("a binding failure that is not a collision still stops the plugin")
    void anUnclassifiableBindingFailureStopsStartup() {
        // The holder reports nothing when the binding is what failed, so the classifier sees null.
        // That is not "nothing is known": the document parsed, which is precisely the establishment
        // that it describes gating. Falling back to defaults that declare no tiers would turn item
        // gating off server-wide for a file that asked for it -- the #91 outcome, by the least
        // understood of the three routes.
        ReloadOutcome outcome = AntiSpeedrunPlugin.rejectionOutcome(false, null);

        assertEquals(ReloadOutcome.GATES_UNBUILDABLE, outcome);
        assertTrue(outcome.stopsStartup(), "an unclassifiable failure must not boot wide open");
        assertTrue(outcome.startupRefusal().contains("will not start"), outcome.startupRefusal());
    }

    @Test
    @DisplayName("a bad key in a disabled dimension gate does not stop the plugin")
    void aDisabledDimensionGateToleratesABadKey() throws ConfigLoadException {
        PluginConfig config = parse("""
                dimension-gates:
                  nether:
                    enabled: false
                    require-advancements:
                      - "story/smelt Iron"
                """);

        assertFalse(config.dimensionGates().nether().enabled());
        assertTrue(config.dimensionGates().nether().requireAdvancements().isEmpty());
        assertTrue(config.warnings().stream().anyMatch(w -> w.contains("switched off")),
                config.warnings().toString());
    }

    @Test
    @DisplayName("a bad key in a tier does not stop the plugin while item-progression is off")
    void disabledItemProgressionToleratesABadKey() throws ConfigLoadException {
        PluginConfig config = parse("""
                item-progression:
                  enabled: false
                  gated-items:
                    iron-tier:
                      items:
                        - "IRON_INGOT"
                      require-advancements:
                        - "story/smelt Iron"
                """);

        assertFalse(config.itemProgression().enabled());
        assertTrue(config.warnings().stream().anyMatch(w -> w.contains("switched off")),
                config.warnings().toString());
    }

    @Test
    @DisplayName("a malformed required-advancement does not stop the plugin while the trade gate "
            + "is off")
    void aDisabledMendingGateToleratesABadKey() throws ConfigLoadException {
        PluginConfig config = parse("""
                villager-progression:
                  gate-mending-trade: false
                  required-advancement: "story/cure Zombie_villager"
                """);

        assertFalse(config.villagerProgression().gateMendingTrade());
        assertEquals("", config.villagerProgression().requiredAdvancement());
        assertTrue(config.warnings().stream().anyMatch(w -> w.contains("switched off")),
                config.warnings().toString());
    }

    @Test
    @DisplayName("switching the same gate back on makes the same key fatal again")
    void enablingTheGateRestoresTheRefusal() {
        ConfigLoadException rejection = assertThrows(UnenforceableGateException.class, () -> parse("""
                dimension-gates:
                  nether:
                    enabled: true
                    require-advancements:
                      - "story/smelt Iron"
                """));

        assertTrue(AntiSpeedrunPlugin.rejectionOutcome(false, rejection).stopsStartup(),
                rejection.getMessage());
    }

    @Test
    @DisplayName("end to end: one typo'd advancement key in a real document stops the plugin")
    void aTypoInARealDocumentStopsStartup() {
        // The exact shape #91 was filed over: a document that parses cleanly, is wrong in one
        // character, and used to start a server with every item ungated.
        ConfigLoadException rejection = assertThrows(ConfigLoadException.class, () -> parse("""
                dimension-gates:
                  nether:
                    enabled: true
                    require-advancements:
                      - "story/smelt Iron"
                item-progression:
                  enabled: true
                  gated-items:
                    iron-tier:
                      items:
                        - "IRON_INGOT"
                      require-advancements:
                        - "story/smelt_iron"
                """));

        assertTrue(AntiSpeedrunPlugin.rejectionOutcome(false, rejection).stopsStartup(),
                rejection.getMessage());
        // And the fallback it would otherwise have run on is exactly the one that gates nothing.
        assertTrue(PluginConfig.defaults().itemProgression().gatedItems().isEmpty(),
                "if the defaults ever gain tiers, the reasoning above needs rereading");
    }
}
