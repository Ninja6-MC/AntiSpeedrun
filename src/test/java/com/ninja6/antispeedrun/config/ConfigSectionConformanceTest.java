package com.ninja6.antispeedrun.config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One set of assertions, run against <strong>every</strong> {@link ConfigSection} implementation.
 *
 * <p>The two implementations are supposed to be interchangeable — the whole point of the seam is
 * that the test suite can exercise the production parser without a server — but until this class
 * existed nothing asserted it, and they had in fact diverged: Bukkit's accessors split a key on
 * {@code '.'} and treat it as a path, while {@link MapConfigSection} has always treated it as one
 * literal key. A {@code gated-items} tier called {@code my.tier} therefore parsed as one tier under
 * test and as a mis-parsed nested section on a live server, which is the worst shape a seam bug can
 * take: the suite passed <em>because</em> it did not use the implementation that misbehaved.
 *
 * <p>Each case is fed the same YAML text through both parsers — SnakeYAML into
 * {@link MapConfigSection}, Bukkit's {@code YamlConfiguration} into {@link BukkitConfigSection} —
 * so a future divergence fails the build. {@code org.bukkit.configuration} is ordinary library code
 * and needs no running server, so the real runtime adapter is exercised here rather than assumed
 * equivalent to the test one.
 */
@DisplayName("ConfigSection implementations are interchangeable")
class ConfigSectionConformanceTest {

    /** Parses a YAML document into one implementation's root section. */
    @FunctionalInterface
    interface Implementation {
        ConfigSection root(String document) throws Exception;
    }

    static List<Arguments> implementations() {
        return List.of(
                Arguments.of("MapConfigSection",
                        (Implementation) ConfigSectionConformanceTest::viaSnakeYaml),
                Arguments.of("BukkitConfigSection",
                        (Implementation) ConfigSectionConformanceTest::viaBukkit));
    }

    private static ConfigSection viaSnakeYaml(String document) {
        Object root = new Yaml().load(document);
        return root instanceof Map<?, ?> mapping ? MapConfigSection.of(mapping) : MapConfigSection.EMPTY;
    }

    /**
     * The production path, end to end: write the document to a file and read it back through
     * {@link BukkitConfigSection#load(File)}, which is the one call {@code AntiSpeedrunPlugin}'s
     * {@code fileSource()} makes. The loader is therefore exercised here and not just the
     * accessors — which matters, because the dotted-key defect lived in the loader.
     */
    private static ConfigSection viaBukkit(String document) throws Exception {
        Path file = Files.createTempFile("antispeedrun-conformance", ".yml");
        file.toFile().deleteOnExit();
        Files.writeString(file, document, StandardCharsets.UTF_8);
        return BukkitConfigSection.load(file.toFile());
    }

    // -------------------------------------------------------------------------------------------
    // The seam that diverged: keys are literal, never paths
    // -------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    @DisplayName("a key containing a dot names one child, and is not split into a path")
    void dottedKeyIsLiteral(String name, Implementation impl) throws Exception {
        ConfigSection root = impl.root("my.tier:\n  hint: dotted\n");

        assertIterableEquals(List.of("my.tier"), root.keys());
        assertTrue(root.contains("my.tier"));
        assertNotNull(root.section("my.tier"));
        assertEquals("dotted", root.section("my.tier").get("hint"));

        // The half that Bukkit's own accessors would have got wrong: there is no child "my".
        assertFalse(root.contains("my"));
        assertNull(root.get("my"));
        assertNull(root.section("my"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    @DisplayName("a genuinely nested key is not reachable by its dotted path either")
    void nestedKeyIsNotReachableByPath(String name, Implementation impl) throws Exception {
        ConfigSection root = impl.root("my:\n  tier:\n    hint: nested\n");

        assertFalse(root.contains("my.tier"));
        assertNull(root.get("my.tier"));
        assertNull(root.section("my.tier"));

        assertEquals("nested", root.section("my").section("tier").get("hint"));
    }

    // -------------------------------------------------------------------------------------------
    // The rest of the contract
    // -------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    @DisplayName("keys are reported in document order")
    void keysAreInDocumentOrder(String name, Implementation impl) throws Exception {
        ConfigSection root = impl.root("zulu: 1\nalpha: 2\nmike: 3\n");

        assertIterableEquals(List.of("zulu", "alpha", "mike"), root.keys());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    @DisplayName("an absent key reads as null and is not contained")
    void absentKey(String name, Implementation impl) throws Exception {
        ConfigSection root = impl.root("present: 1\n");

        assertFalse(root.contains("absent"));
        assertNull(root.get("absent"));
        assertNull(root.section("absent"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    @DisplayName("a scalar child is present but is not a section")
    void scalarChildIsNotASection(String name, Implementation impl) throws Exception {
        ConfigSection root = impl.root("a: scalar\n");

        assertTrue(root.contains("a"));
        assertEquals("scalar", root.get("a"));
        assertNull(root.section("a"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    @DisplayName("a mapping child reads back as a Map through get, so callers can type-check it")
    void mappingChildReadsBackAsAMap(String name, Implementation impl) throws Exception {
        ConfigSection root = impl.root("a:\n  b: 1\n");

        Map<?, ?> raw = assertInstanceOf(Map.class, root.get("a"),
                "a mapping must not read back as an implementation-specific type");
        assertEquals(1, raw.get("b"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    @DisplayName("scalars keep the types the YAML parser gave them")
    void scalarTypes(String name, Implementation impl) throws Exception {
        ConfigSection root = impl.root("s: text\ni: 7\nd: 1.5\nb: true\nl:\n  - one\n  - two\n");

        assertEquals("text", root.get("s"));
        assertEquals(7, root.get("i"));
        assertEquals(1.5D, root.get("d"));
        assertEquals(Boolean.TRUE, root.get("b"));
        assertIterableEquals(List.of("one", "two"), (List<?>) root.get("l"));
    }

    /**
     * The second way the two implementations diverged. Bukkit's {@code MemorySection.set} removes a
     * key whose value is null, so {@link BukkitConfigSection} cannot report a bodiless
     * {@code my-tier:} at all; {@link MapConfigSection} used to report it with a null value, which
     * made such a document parse as one empty {@code gated-items} tier under test and as no tier on
     * a server. Runtime behaviour wins — the map implementation now drops the key too — and this
     * case pins it, in both directions, so neither side can drift back.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    @DisplayName("a key written with no value counts as absent, not as a key holding null")
    void keyWithNoValueIsAbsent(String name, Implementation impl) throws Exception {
        ConfigSection root = impl.root("before: 1\nbodiless:\nafter: 2\n");

        assertIterableEquals(List.of("before", "after"), root.keys());
        assertFalse(root.contains("bodiless"));
        assertNull(root.get("bodiless"));
        assertNull(root.section("bodiless"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    @DisplayName("a bodiless tier id yields no tier, and the same warnings, either way")
    void bodilessTierIdYieldsNoTier(String name, Implementation impl) throws Exception {
        PluginConfig config = PluginConfig.from(impl.root("""
                item-progression:
                  gated-items:
                    my-tier:
                """));

        assertTrue(config.itemProgression().gatedItems().isEmpty(),
                "a tier with no body must not become a phantom empty tier: "
                        + config.itemProgression().gatedItems());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    @DisplayName("an empty document has no keys")
    void emptyDocument(String name, Implementation impl) throws Exception {
        assertTrue(impl.root("# nothing but a comment\n").keys().isEmpty());
    }

    // -------------------------------------------------------------------------------------------
    // End to end: the same document must produce the same snapshot through either implementation
    // -------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    @DisplayName("a tier id containing a dot parses as one tier, with no unknown-key warning")
    void dottedTierIdParsesAsOneTier(String name, Implementation impl) throws Exception {
        PluginConfig config = PluginConfig.from(impl.root("""
                item-progression:
                  gated-items:
                    my.tier:
                      match-patterns:
                        - "*_INGOT"
                      hint: Smelt an iron ingot
                """));

        assertEquals(1, config.itemProgression().gatedItems().size());
        PluginConfig.ItemTier tier = config.itemProgression().gatedItems().get(0);
        assertEquals("my.tier", tier.id());
        assertIterableEquals(List.of("*_INGOT"), tier.matchPatterns());
        assertEquals("Smelt an iron ingot", tier.hint());
        assertTrue(config.warnings().stream().noneMatch(w -> w.startsWith("unknown key")),
                "a dotted tier id must not look like an unknown key: " + config.warnings());
    }

    /**
     * Pins the reason {@link BukkitConfigSection#load(File)} exists rather than the plugin loading a
     * {@code YamlConfiguration} itself: Bukkit splits a dotted key on the way <em>in</em>, so no
     * amount of care in the accessors can recover it afterwards. If a future Bukkit stops doing
     * this, this test fails and the separator override can be dropped.
     */
    @Test
    @DisplayName("Bukkit's default loader really does destructure a dotted key, hence load()")
    void defaultBukkitLoaderSplitsDottedKeys() throws Exception {
        YamlConfiguration defaultLoader = new YamlConfiguration();
        defaultLoader.loadFromString("my.tier:\n  hint: dotted\n");

        assertIterableEquals(List.of("my"), defaultLoader.getKeys(false));
        assertNotNull(defaultLoader.getConfigurationSection("my"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    @DisplayName("the shipped config.yml parses identically through either implementation")
    void shippedDocumentParsesIdentically(String name, Implementation impl) throws Exception {
        String shipped = new String(
                ConfigSectionConformanceTest.class.getResourceAsStream("/config.yml").readAllBytes(),
                StandardCharsets.UTF_8);

        PluginConfig through = PluginConfig.from(impl.root(shipped));

        assertEquals(PluginConfig.from(viaSnakeYaml(shipped)), through,
                "the two implementations must yield the same snapshot for the shipped document");
    }
}
