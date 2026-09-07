package com.ninja6.antispeedrun.listeners;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import com.ninja6.antispeedrun.config.ConfigLoadException;
import com.ninja6.antispeedrun.config.ConfigSection;
import com.ninja6.antispeedrun.config.MapConfigSection;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.MilestoneEvaluator;
import com.ninja6.antispeedrun.progression.MilestoneRequirement;
import com.ninja6.antispeedrun.progression.PlayerProgressionSnapshot;
import com.ninja6.antispeedrun.storage.DimensionUnlock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dimension gate's decision, exercised without a server.
 *
 * <p>Everything here runs against a real {@link PluginConfig} parsed from real YAML — including the
 * {@code config.yml} the plugin actually ships — so the gate is tested against the configuration
 * operators will have, not against a fixture invented for the test.
 *
 * <h2>Why there is not a single hour or day literal below — finding R-02</h2>
 *
 * #34's acceptance criteria quote "playtime &lt; 2h" and "&lt; 20h, age &lt; 7d". The shipped file
 * sets both to {@code 0}. A test asserting the criteria as written would fail on the configuration
 * that ships, so these assert the <em>mechanism</em>: that the gate reads
 * {@code dimension-gates.*.require-*}, and that with every threshold at zero the gate is decided by
 * advancements alone. {@link ShippedConfiguration} pins that the shipped file really is all-zeroes,
 * so this stays honest if the defaults ever move.
 */
class DimensionGateRulesTest {

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private static ConfigSection parse(Reader reader, String name) throws ConfigLoadException {
        Object root;
        try {
            root = new Yaml().load(reader);
        } catch (YAMLException failure) {
            throw new ConfigLoadException(name + " could not be parsed: " + failure.getMessage(), failure);
        }
        if (root == null) {
            return MapConfigSection.EMPTY;
        }
        if (!(root instanceof Map<?, ?> mapping)) {
            throw new ConfigLoadException(name + " root is not a mapping");
        }
        return MapConfigSection.of(mapping);
    }

    private static PluginConfig yaml(String document) throws ConfigLoadException {
        return PluginConfig.from(parse(new StringReader(document), "<inline document>"));
    }

    /** The config.yml this plugin actually ships. */
    private static PluginConfig shipped() throws ConfigLoadException, IOException {
        try (InputStream in = DimensionGateRulesTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "src/main/resources/config.yml must be on the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return PluginConfig.from(parse(reader, "config.yml"));
            }
        }
    }

    /**
     * A player's captured facts: which of the queried advancements they hold, with a playtime and
     * a tenure far beyond anything {@code config.yml} can ask for — so that whatever these tests
     * observe is attributable to the advancements alone.
     */
    private static PlayerProgressionSnapshot holding(List<String> earned, List<String> queried) {
        return new PlayerProgressionSnapshot(
                Set.copyOf(queried), Set.copyOf(earned), Set.of(), 1_000.0D, 3_650L, true, 0L);
    }

    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("which gate a transit engages")
    class GatedDestination {

        @Test
        @DisplayName("arriving in the Nether engages the Nether gate")
        void netherArrival() throws Exception {
            assertEquals(Optional.of(DimensionUnlock.NETHER),
                    DimensionGateRules.gatedDestination(
                            EnvironmentKind.OVERWORLD, EnvironmentKind.NETHER, shipped()));
        }

        @Test
        @DisplayName("arriving in the End engages the End gate, from either side")
        void endArrival() throws Exception {
            PluginConfig config = shipped();
            assertEquals(Optional.of(DimensionUnlock.THE_END),
                    DimensionGateRules.gatedDestination(
                            EnvironmentKind.OVERWORLD, EnvironmentKind.THE_END, config));
            assertEquals(Optional.of(DimensionUnlock.THE_END),
                    DimensionGateRules.gatedDestination(
                            EnvironmentKind.NETHER, EnvironmentKind.THE_END, config));
        }

        /**
         * #6's second acceptance criterion, and the one whose failure would be felt by every other
         * plugin on the server rather than by this one. Asserted for every kind, explicitly, rather
         * than left to follow from the Overworld case.
         */
        @Test
        @DisplayName("an intra-dimensional teleport engages nothing, in every dimension")
        void intraDimensionalIsUntouched() throws Exception {
            PluginConfig config = shipped();
            for (EnvironmentKind kind : EnvironmentKind.values()) {
                assertEquals(Optional.empty(),
                        DimensionGateRules.gatedDestination(kind, kind, config),
                        kind + " -> " + kind + " is /spawn or an RTP, not a dimension change");
            }
        }

        @Test
        @DisplayName("leaving a gated dimension is never gated")
        void leavingIsUngated() throws Exception {
            PluginConfig config = shipped();
            assertEquals(Optional.empty(), DimensionGateRules.gatedDestination(
                    EnvironmentKind.NETHER, EnvironmentKind.OVERWORLD, config));
            assertEquals(Optional.empty(), DimensionGateRules.gatedDestination(
                    EnvironmentKind.THE_END, EnvironmentKind.OVERWORLD, config));
        }

        @Test
        @DisplayName("a custom dimension is not one of the two gates")
        void customIsUngated() throws Exception {
            assertEquals(Optional.empty(), DimensionGateRules.gatedDestination(
                    EnvironmentKind.OVERWORLD, EnvironmentKind.CUSTOM, shipped()));
        }

        @Test
        @DisplayName("a disabled gate engages nothing")
        void disabledGate() throws Exception {
            PluginConfig config = yaml("""
                    dimension-gates:
                      nether:
                        enabled: false
                      the_end:
                        enabled: true
                    """);
            assertEquals(Optional.empty(), DimensionGateRules.gatedDestination(
                    EnvironmentKind.OVERWORLD, EnvironmentKind.NETHER, config));
            assertEquals(Optional.of(DimensionUnlock.THE_END), DimensionGateRules.gatedDestination(
                    EnvironmentKind.OVERWORLD, EnvironmentKind.THE_END, config));
        }
    }

    @Nested
    @DisplayName("the shipped configuration - finding R-02")
    class ShippedConfiguration {

        /**
         * The assertion #34's amended criteria actually ask for: the file that ships gates on
         * advancements and nothing else. If someone sets a non-zero default here, this fails and
         * the criteria have to be revisited rather than quietly diverging again.
         */
        @Test
        @DisplayName("every require-* threshold is zero, so both gates are advancement-driven")
        void thresholdsAreAllZero() throws Exception {
            PluginConfig config = shipped();
            for (DimensionUnlock dimension : DimensionUnlock.values()) {
                MilestoneRequirement requirement = DimensionGateRules.requirement(dimension, config);
                assertEquals(0.0D, requirement.playtimeHours(),
                        dimension + " ships with require-playtime-hours at 0");
                assertEquals(0, requirement.accountAgeDays(),
                        dimension + " ships with require-account-age-days at 0");
                assertFalse(requirement.advancements().isEmpty(),
                        dimension + " must therefore be decided by advancements alone");
            }
        }

        @Test
        @DisplayName("the requirement is read from the config keys, not from a literal")
        void requirementTracksTheConfiguredKeys() throws Exception {
            PluginConfig config = yaml("""
                    dimension-gates:
                      nether:
                        require-playtime-hours: 2.5
                        require-account-age-days: 7
                        require-advancements:
                          - "story/smelt_iron"
                    """);
            MilestoneRequirement requirement =
                    DimensionGateRules.requirement(DimensionUnlock.NETHER, config);
            assertEquals(2.5D, requirement.playtimeHours());
            assertEquals(7, requirement.accountAgeDays());
            assertEquals(List.of("minecraft:story/smelt_iron"), requirement.advancements());
        }

        /**
         * The gate on the shipped, all-zeroes configuration: earning the advancement is the whole
         * of it, and a player who has not is blocked regardless of how long they have played.
         */
        @Test
        @DisplayName("on the shipped config the Nether gate turns on the advancement alone")
        void advancementDrivesTheShippedNetherGate() throws Exception {
            MilestoneRequirement nether =
                    DimensionGateRules.requirement(DimensionUnlock.NETHER, shipped());
            List<String> queried = nether.advancements();

            EligibilityResult without =
                    MilestoneEvaluator.evaluate(nether, holding(List.of(), queried));
            assertFalse(without.eligible(), "a fresh player is refused the Nether");
            assertEquals(queried, without.missingAdvancements());

            EligibilityResult with = MilestoneEvaluator.evaluate(nether, holding(queried, queried));
            assertTrue(with.eligible(), "smelting iron is the whole of the shipped Nether gate");
        }

        @Test
        @DisplayName("on the shipped config the End gate needs every configured advancement")
        void everyEndAdvancementIsRequired() throws Exception {
            MilestoneRequirement end =
                    DimensionGateRules.requirement(DimensionUnlock.THE_END, shipped());
            List<String> queried = end.advancements();
            assertTrue(queried.size() > 1, "the shipped End gate lists several advancements");

            for (int i = 0; i < queried.size(); i++) {
                List<String> allButOne = new java.util.ArrayList<>(queried);
                String withheld = allButOne.remove(i);
                assertFalse(MilestoneEvaluator.evaluate(end, holding(allButOne, queried)).eligible(),
                        "the End must stay sealed while " + withheld + " is outstanding");
            }
            assertTrue(MilestoneEvaluator.evaluate(end, holding(queried, queried)).eligible());
        }
    }

    @Nested
    @DisplayName("waivers")
    class Waivers {

        @Test
        @DisplayName("no waiver at all leaves the gate in force")
        void noneWaives() {
            assertFalse(DimensionGateRules.waived(false, false, false));
        }

        @Test
        @DisplayName("each of the three waivers is sufficient on its own")
        void anyOneWaives() {
            assertTrue(DimensionGateRules.waived(true, false, false), "the bypass permission");
            assertTrue(DimensionGateRules.waived(false, true, false), "a timed /asr bypass grant");
            assertTrue(DimensionGateRules.waived(false, false, true), "an /asr unlock override");
        }
    }

    @Nested
    @DisplayName("the rejection a player is shown")
    class Rejection {

        @Test
        @DisplayName("is the operator's configured message, verbatim")
        void usesTheConfiguredMessage() throws Exception {
            PluginConfig config = yaml("""
                    dimension-gates:
                      nether:
                        rejection-message: "<red>Not yet."
                    """);
            assertEquals("<red>Not yet.",
                    DimensionGateRules.gate(DimensionUnlock.NETHER, config).rejectionMessage());
        }

        @Test
        @DisplayName("carries the fail-open hint when a requirement could not be evaluated")
        void surfacesTheFallbackHint() {
            MilestoneRequirement requirement =
                    new MilestoneRequirement(List.of("minecraft:story/smelt_iron"), 0.0D, 0);
            PlayerProgressionSnapshot unresolvable = new PlayerProgressionSnapshot(
                    Set.of("minecraft:story/smelt_iron"), Set.of(),
                    Set.of("minecraft:story/smelt_iron"), 1.0D, 1L, true, 0L);

            EligibilityResult result = MilestoneEvaluator.evaluate(requirement, unresolvable);
            assertTrue(result.eligible(), "an unresolvable advancement fails open");
            assertTrue(DimensionGateRules.fallbackHint(result).isPresent(),
                    "and says so, rather than passing silently");
        }
    }

    @Nested
    @DisplayName("translating Bukkit's environment names")
    class Environments {

        @Test
        @DisplayName("the three vanilla constants map to the three vanilla kinds")
        void vanillaNames() {
            assertEquals(EnvironmentKind.OVERWORLD, EnvironmentKind.of("NORMAL"));
            assertEquals(EnvironmentKind.NETHER, EnvironmentKind.of("NETHER"));
            assertEquals(EnvironmentKind.THE_END, EnvironmentKind.of("THE_END"));
        }

        @Test
        @DisplayName("anything else, including nothing at all, is CUSTOM and ungated")
        void unknownNames() {
            assertEquals(EnvironmentKind.CUSTOM, EnvironmentKind.of("CUSTOM"));
            assertEquals(EnvironmentKind.CUSTOM, EnvironmentKind.of("A_DATAPACK_DIMENSION"));
            assertEquals(EnvironmentKind.CUSTOM, EnvironmentKind.of(null));
            assertFalse(EnvironmentKind.CUSTOM.isGatable());
            assertFalse(EnvironmentKind.OVERWORLD.isGatable());
            assertTrue(EnvironmentKind.NETHER.isGatable());
            assertTrue(EnvironmentKind.THE_END.isGatable());
        }
    }
}
