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
    @DisplayName("a rejection the binding caused without colliding takes the survivable arm")
    void anUnclassifiableBindingFailureIsSurvivable() {
        // The holder reports nothing when the binding is what failed, so the classifier sees null.
        // Nothing has established that the file describes an unenforceable gate, so it must not be
        // treated as one.
        assertEquals(ReloadOutcome.CONFIG_REJECTED, AntiSpeedrunPlugin.rejectionOutcome(false, null));
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
