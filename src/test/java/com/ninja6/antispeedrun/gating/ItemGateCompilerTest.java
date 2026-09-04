package com.ninja6.antispeedrun.gating;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.ninja6.antispeedrun.config.ConfigLoadException;
import com.ninja6.antispeedrun.config.ConfigSection;
import com.ninja6.antispeedrun.config.MapConfigSection;
import com.ninja6.antispeedrun.config.PluginConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real compiler over the real shipped {@code config.yml}, against a material universe
 * whose constants carry the genuine 1.21 names.
 */
class ItemGateCompilerTest {

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private final List<String> warnings = new ArrayList<>();

    private static ConfigSection parse(Reader reader, String name) throws ConfigLoadException {
        Object root = new Yaml().load(reader);
        if (root == null) {
            return MapConfigSection.EMPTY;
        }
        if (!(root instanceof Map<?, ?> mapping)) {
            throw new ConfigLoadException(name + " root is not a mapping");
        }
        return MapConfigSection.of(mapping);
    }

    private static PluginConfig yaml(String document) throws ConfigLoadException {
        return PluginConfig.from(parse(new java.io.StringReader(document), "<inline document>"));
    }

    /** The config.yml this plugin actually ships, read off the classpath. */
    private static PluginConfig shipped() throws Exception {
        try (InputStream in = ItemGateCompilerTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "src/main/resources/config.yml must be on the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return PluginConfig.from(parse(reader, "config.yml"));
            }
        }
    }

    private ItemGateTable<TestMaterial> compile(PluginConfig config) throws GateCollisionException {
        return ItemGateCompiler.compile(TestMaterial.class, TestMaterial.UNIVERSE,
                config.itemProgression().gatedItems(), warnings);
    }

    private String tierOf(ItemGateTable<TestMaterial> table, TestMaterial material) {
        PluginConfig.ItemTier tier = table.tierFor(material);
        return tier == null ? null : tier.id();
    }

    private boolean warned(String fragment) {
        return warnings.stream().anyMatch(w -> w.contains(fragment));
    }

    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the shipped config.yml")
    class Shipped {

        @Test
        @DisplayName("compiles with no warnings at all")
        void compilesClean() throws Exception {
            compile(shipped());
            assertEquals(List.of(), warnings,
                    "every pattern must match something and every named material must exist");
        }

        @Test
        @DisplayName("IRON_* gates iron gear while the decorative exclusions stay ungated")
        void ironTier() throws Exception {
            ItemGateTable<TestMaterial> gates = compile(shipped());

            for (TestMaterial gear : List.of(TestMaterial.IRON_INGOT, TestMaterial.IRON_ORE,
                    TestMaterial.IRON_BLOCK, TestMaterial.IRON_SWORD, TestMaterial.IRON_PICKAXE,
                    TestMaterial.IRON_HELMET, TestMaterial.IRON_NUGGET, TestMaterial.RAW_IRON,
                    TestMaterial.RAW_IRON_BLOCK, TestMaterial.CHAINMAIL_HELMET)) {
                assertEquals("iron-tier", tierOf(gates, gear), gear.name());
            }

            for (TestMaterial decorative : List.of(TestMaterial.IRON_DOOR, TestMaterial.IRON_TRAPDOOR,
                    TestMaterial.IRON_BARS, TestMaterial.HEAVY_WEIGHTED_PRESSURE_PLATE,
                    TestMaterial.CHAIN, TestMaterial.LANTERN, TestMaterial.SOUL_LANTERN)) {
                assertFalse(gates.isGated(decorative), decorative.name() + " must stay ungated");
            }

            for (TestMaterial named : List.of(TestMaterial.SHIELD, TestMaterial.BUCKET,
                    TestMaterial.SHEARS, TestMaterial.FLINT_AND_STEEL, TestMaterial.CROSSBOW,
                    TestMaterial.TRIAL_KEY, TestMaterial.OMINOUS_TRIAL_KEY,
                    TestMaterial.OMINOUS_BOTTLE)) {
                assertEquals("iron-tier", tierOf(gates, named), named.name());
            }
        }

        @Test
        @DisplayName("the deepslate ore variants resolve to their tiers (C-11)")
        void deepslateOres() throws Exception {
            ItemGateTable<TestMaterial> gates = compile(shipped());

            // The whole point of the suffix mode: prefix matching catches IRON_ORE but not
            // DEEPSLATE_IRON_ORE, so below Y=0 silk-touched ore used to be ungated.
            assertEquals("iron-tier", tierOf(gates, TestMaterial.DEEPSLATE_IRON_ORE));
            assertEquals("diamond-tier", tierOf(gates, TestMaterial.DEEPSLATE_DIAMOND_ORE));
            assertEquals("iron-tier", tierOf(gates, TestMaterial.IRON_ORE));
            assertEquals("diamond-tier", tierOf(gates, TestMaterial.DIAMOND_ORE));

            // A deepslate ore of an ungated resource must not be swept up by the suffix pattern.
            assertFalse(gates.isGated(TestMaterial.DEEPSLATE_COAL_ORE));
            assertFalse(gates.isGated(TestMaterial.COAL_ORE));
        }

        @Test
        @DisplayName("DIAMOND itself is gated, not only the tools named after it")
        void bareDiamond() throws Exception {
            // DIAMOND_* has no trailing underscore to match against DIAMOND, so the gem escaped
            // every pattern while DIAMOND_SWORD did not.
            assertEquals("diamond-tier", tierOf(compile(shipped()), TestMaterial.DIAMOND));
        }

        @Test
        @DisplayName("the 1.21 items are gated (C-11)")
        void oneTwentyOneItems() throws Exception {
            ItemGateTable<TestMaterial> gates = compile(shipped());

            assertEquals("netherite-tier", tierOf(gates, TestMaterial.MACE));
            assertEquals("netherite-tier", tierOf(gates, TestMaterial.HEAVY_CORE));
            assertEquals("nether-tier", tierOf(gates, TestMaterial.BREEZE_ROD));
            assertEquals("nether-tier", tierOf(gates, TestMaterial.WIND_CHARGE));
            assertEquals("iron-tier", tierOf(gates, TestMaterial.TRIAL_KEY));
            assertEquals("iron-tier", tierOf(gates, TestMaterial.OMINOUS_TRIAL_KEY));
            assertEquals("iron-tier", tierOf(gates, TestMaterial.OMINOUS_BOTTLE));
        }

        @Test
        @DisplayName("NETHERITE_UPGRADE_SMITHING_TEMPLATE is excluded, not collided over")
        void smithingTemplateIsExcludedNotCollided() throws Exception {
            ItemGateTable<TestMaterial> gates = compile(shipped());

            assertFalse(gates.isGated(TestMaterial.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                    "it matches NETHERITE_* but belongs to trim-progression in section 3");
            assertNull(gates.tierFor(TestMaterial.NETHERITE_UPGRADE_SMITHING_TEMPLATE));

            assertEquals("netherite-tier", tierOf(gates, TestMaterial.NETHERITE_INGOT));
            assertEquals("netherite-tier", tierOf(gates, TestMaterial.NETHERITE_SWORD));
            assertEquals("netherite-tier", tierOf(gates, TestMaterial.ANCIENT_DEBRIS));
        }

        @Test
        @DisplayName("gates nothing it was not asked to")
        void leavesTheRestAlone() throws Exception {
            ItemGateTable<TestMaterial> gates = compile(shipped());
            for (TestMaterial ordinary : List.of(TestMaterial.DIRT, TestMaterial.STONE,
                    TestMaterial.COBBLESTONE, TestMaterial.OAK_LOG, TestMaterial.GOLD_INGOT,
                    TestMaterial.WHEAT, TestMaterial.APPLE, TestMaterial.TORCH,
                    TestMaterial.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE)) {
                assertFalse(gates.isGated(ordinary), ordinary.name());
            }
        }

        @Test
        @DisplayName("compiles to an empty table when item progression is switched off")
        void disabledCompilesToNothing() throws Exception {
            // MaterialGates short-circuits on enabled == false; the compiler itself is unaware of
            // the flag, so the two are asserted separately.
            PluginConfig off = yaml("item-progression:\n  enabled: false\n");
            assertTrue(compile(off).isEmpty());
        }
    }

    @Nested
    @DisplayName("match modes")
    class Modes {

        @Test
        @DisplayName("prefix, suffix, contains and exact are all parsed")
        void allFourModes() {
            assertEquals(MaterialPattern.Mode.PREFIX, MaterialPattern.parse("IRON_*").mode());
            assertEquals(MaterialPattern.Mode.SUFFIX, MaterialPattern.parse("*_IRON_ORE").mode());
            assertEquals(MaterialPattern.Mode.CONTAINS, MaterialPattern.parse("*_DIAMOND_*").mode());
            assertEquals(MaterialPattern.Mode.EXACT, MaterialPattern.parse("SHIELD").mode());
            assertEquals(MaterialPattern.Mode.ALL, MaterialPattern.parse("*").mode());

            assertEquals("IRON_", MaterialPattern.parse("iron_*").literal(), "folded to upper case");
        }

        @Test
        @DisplayName("*_SMITHING_TEMPLATE compiles correctly, which is what Epic 5 needs")
        void smithingTemplateSuffix() throws Exception {
            ItemGateTable<TestMaterial> gates = compileTier(tier("trim-tier",
                    List.of("*_SMITHING_TEMPLATE"), List.of(), List.of()));

            assertEquals("trim-tier", tierOf(gates, TestMaterial.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE));
            assertEquals("trim-tier", tierOf(gates, TestMaterial.VEX_ARMOR_TRIM_SMITHING_TEMPLATE));
            assertEquals("trim-tier", tierOf(gates, TestMaterial.WARD_ARMOR_TRIM_SMITHING_TEMPLATE));
            assertEquals("trim-tier",
                    tierOf(gates, TestMaterial.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
            assertFalse(gates.isGated(TestMaterial.NETHERITE_INGOT));
        }

        @Test
        @DisplayName("a contains pattern reaches the middle of a name")
        void containsMode() throws Exception {
            ItemGateTable<TestMaterial> gates = compileTier(tier("ore-tier",
                    List.of("*_DIAMOND_*"), List.of(), List.of()));

            assertTrue(gates.isGated(TestMaterial.DEEPSLATE_DIAMOND_ORE));
            assertFalse(gates.isGated(TestMaterial.DIAMOND_ORE), "no leading underscore");
        }

        @Test
        @DisplayName("an exclusion beats both a pattern and an explicit item entry")
        void exclusionWins() throws Exception {
            ItemGateTable<TestMaterial> gates = compileTier(tier("t",
                    List.of("IRON_*"), List.of("SHIELD", "IRON_DOOR"), List.of("IRON_DOOR", "SHIELD")));

            assertFalse(gates.isGated(TestMaterial.IRON_DOOR));
            assertFalse(gates.isGated(TestMaterial.SHIELD));
            assertTrue(gates.isGated(TestMaterial.IRON_INGOT));
            assertTrue(warned("SHIELD is in both items and exclude-materials"));
        }

        @Test
        @DisplayName("a wildcard in the middle is refused rather than guessed at")
        void interiorWildcardIsRefused() throws Exception {
            assertNull(MaterialPattern.parse("IRON_*_ORE"));
            assertNull(MaterialPattern.parse(""));
            assertNull(MaterialPattern.parse("   "));

            ItemGateTable<TestMaterial> gates = compileTier(tier("t",
                    List.of("IRON_*_ORE"), List.of(), List.of()));

            // Reading it as the prefix IRON_ would gate every iron item on a line that asked for
            // one ore, so it gates nothing and says so.
            assertTrue(gates.isEmpty());
            assertTrue(warned("\"IRON_*_ORE\" is not a usable pattern"));
        }

        @Test
        @DisplayName("a bare * is applied but flagged, because it gates the whole game")
        void bareWildcardIsFlagged() throws Exception {
            ItemGateTable<TestMaterial> gates = compileTier(tier("t", List.of("*"), List.of(), List.of()));
            assertEquals(TestMaterial.UNIVERSE.length, gates.size());
            assertTrue(warned("gates every material in the game"));
        }

        @Test
        @DisplayName("a pattern that matches nothing is reported as dead")
        void deadPattern() throws Exception {
            compileTier(tier("t", List.of("COPPER_*"), List.of(), List.of()));
            assertTrue(warned("\"COPPER_*\" matched no material and gates nothing"));
        }

        @Test
        @DisplayName("a pattern whose every hit is excluded is not reported as dead")
        void fullyExcludedPatternIsNotDead() throws Exception {
            compileTier(tier("t", List.of("CHAINMAIL_*"), List.of(),
                    List.of("CHAINMAIL_HELMET", "CHAINMAIL_CHESTPLATE")));
            assertFalse(warned("matched no material"),
                    "the operator excluded them deliberately; the pattern is not a dead line");
        }

        @Test
        @DisplayName("a material name that does not exist is named and ignored")
        void unknownMaterialName() throws Exception {
            ItemGateTable<TestMaterial> gates = compileTier(tier("t",
                    List.of(), List.of("IRON_INGOT", "COPPER_MACE"), List.of("NOT_A_THING")));

            assertTrue(gates.isGated(TestMaterial.IRON_INGOT));
            assertTrue(warned("\"COPPER_MACE\" is not a material this server knows about"));
            assertTrue(warned("\"NOT_A_THING\" is not a material this server knows about"));
        }

        @Test
        @DisplayName("a real material that this tier never matches is left alone silently")
        void unmatchedExclusionIsNotAWarning() throws Exception {
            // CHAIN and LANTERN are exactly this in the shipped file: defensive exclusions that
            // keep working if IRON_* is ever widened. Warning about them would train operators to
            // delete the safety net.
            compileTier(tier("t", List.of("IRON_*"), List.of(), List.of("LANTERN", "CHAIN")));
            assertEquals(List.of(), warnings);
        }
    }

    @Nested
    @DisplayName("precedence")
    class Precedence {

        private PluginConfig.ItemTier requiring(String id, List<String> advancements,
                                                double playtime, int accountAge, String item) {
            return new PluginConfig.ItemTier(id, List.of(), List.of(), List.of(item),
                    advancements, playtime, accountAge, "");
        }

        @Test
        @DisplayName("the strictly more restrictive tier wins, whichever order it is declared in")
        void mostRestrictiveWins() throws Exception {
            PluginConfig.ItemTier loose = requiring("loose", List.of("a"), 0.0D, 0, "MACE");
            PluginConfig.ItemTier strict = requiring("strict", List.of("a", "b"), 0.0D, 0, "MACE");

            assertEquals("strict", tierOf(compileAll(List.of(loose, strict)), TestMaterial.MACE));
            warnings.clear();
            assertEquals("strict", tierOf(compileAll(List.of(strict, loose)), TestMaterial.MACE),
                    "document order must not be able to override dominance");
        }

        @Test
        @DisplayName("playtime and account age count as restrictiveness too")
        void nonAdvancementRequirements() throws Exception {
            PluginConfig.ItemTier quick = requiring("quick", List.of("a"), 2.0D, 0, "MACE");
            PluginConfig.ItemTier slow = requiring("slow", List.of("a"), 40.0D, 0, "MACE");
            assertEquals("slow", tierOf(compileAll(List.of(quick, slow)), TestMaterial.MACE));

            warnings.clear();
            PluginConfig.ItemTier young = requiring("young", List.of("a"), 0.0D, 1, "MACE");
            PluginConfig.ItemTier old = requiring("old", List.of("a"), 0.0D, 30, "MACE");
            assertEquals("old", tierOf(compileAll(List.of(young, old)), TestMaterial.MACE));
        }

        @Test
        @DisplayName("identical requirements fall back to document order, with a warning")
        void identicalRequirementsUseDocumentOrder() throws Exception {
            PluginConfig.ItemTier first = requiring("first", List.of("a"), 0.0D, 0, "MACE");
            PluginConfig.ItemTier second = requiring("second", List.of("a"), 0.0D, 0, "MACE");

            assertEquals("first", tierOf(compileAll(List.of(first, second)), TestMaterial.MACE));
            assertTrue(warned("identical requirements"));
            assertTrue(warned("\"first\" owns it because it is declared first"));
        }

        @Test
        @DisplayName("an incomparable pair fails with a named error rather than a coin toss")
        void genuineCollisionFails() {
            PluginConfig.ItemTier nether = requiring("nether-tier", List.of("a"), 0.0D, 0, "MACE");
            PluginConfig.ItemTier end = requiring("end-tier", List.of("b"), 0.0D, 0, "MACE");

            GateCollisionException collision = assertThrows(GateCollisionException.class,
                    () -> compileAll(List.of(nether, end)));

            assertEquals("MACE", collision.materialName());
            assertEquals("nether-tier", collision.first().id());
            assertEquals("end-tier", collision.second().id());
            assertTrue(collision.getMessage().contains("neither is more restrictive"),
                    collision.getMessage());
            assertTrue(collision.getMessage().contains("exclude-materials"),
                    "the message must say how to fix it: " + collision.getMessage());
        }

        @Test
        @DisplayName("an exclusion resolves what would otherwise be a collision")
        void exclusionResolvesACollision() throws Exception {
            PluginConfig.ItemTier nether = requiring("nether-tier", List.of("a"), 0.0D, 0, "MACE");
            PluginConfig.ItemTier end = new PluginConfig.ItemTier("end-tier", List.of(),
                    List.of("MACE"), List.of("MACE"), List.of("b"), 0.0D, 0, "");

            assertEquals("nether-tier", tierOf(compileAll(List.of(nether, end)), TestMaterial.MACE));
        }

        @Test
        @DisplayName("three tiers resolve to the same winner in every declaration order")
        void threeTiersAreOrderIndependent() throws Exception {
            // B and C are incomparable with each other; A dominates both, so A is the unique
            // maximum and the answer is unambiguous. A pairwise fold gets this wrong: declared
            // B, C, A it compares B against C first, throws, and never consults A at all.
            PluginConfig.ItemTier a = requiring("a", List.of("x", "y", "z"), 0.0D, 0, "MACE");
            PluginConfig.ItemTier b = requiring("b", List.of("x", "y"), 0.0D, 0, "MACE");
            PluginConfig.ItemTier c = requiring("c", List.of("x", "z"), 0.0D, 0, "MACE");

            for (List<PluginConfig.ItemTier> order : List.of(
                    List.of(a, b, c), List.of(a, c, b), List.of(b, a, c),
                    List.of(b, c, a), List.of(c, a, b), List.of(c, b, a))) {
                warnings.clear();
                assertEquals("a", tierOf(compileAll(order), TestMaterial.MACE),
                        "order " + order.stream().map(PluginConfig.ItemTier::id).toList());
                assertEquals(List.of(), warnings);
            }
        }

        @Test
        @DisplayName("three tiers with no maximum fail in every declaration order")
        void threeTiersWithNoMaximumAlwaysCollide() {
            // A dominates B, but C is incomparable with both, so no claimant beats every other.
            PluginConfig.ItemTier a = requiring("a", List.of("x", "y"), 0.0D, 0, "MACE");
            PluginConfig.ItemTier b = requiring("b", List.of("x"), 0.0D, 0, "MACE");
            PluginConfig.ItemTier c = requiring("c", List.of("z"), 0.0D, 0, "MACE");

            for (List<PluginConfig.ItemTier> order : List.of(
                    List.of(a, b, c), List.of(a, c, b), List.of(b, a, c),
                    List.of(b, c, a), List.of(c, a, b), List.of(c, b, a))) {
                warnings.clear();
                assertThrows(GateCollisionException.class, () -> compileAll(order),
                        "order " + order.stream().map(PluginConfig.ItemTier::id).toList());
            }
        }

        @Test
        @DisplayName("a partial-overlap advancement set is a collision, not a superset")
        void partialOverlapIsACollision() {
            PluginConfig.ItemTier a = requiring("a", List.of("x", "y"), 0.0D, 0, "MACE");
            PluginConfig.ItemTier b = requiring("b", List.of("y", "z"), 0.0D, 0, "MACE");
            assertThrows(GateCollisionException.class, () -> compileAll(List.of(a, b)));
        }

        @Test
        @DisplayName("more advancements do not win when the other tier demands more playtime")
        void mixedDirectionsAreACollision() {
            PluginConfig.ItemTier a = requiring("a", List.of("x", "y"), 0.0D, 0, "MACE");
            PluginConfig.ItemTier b = requiring("b", List.of("x"), 50.0D, 0, "MACE");
            assertThrows(GateCollisionException.class, () -> compileAll(List.of(a, b)));
        }
    }

    @Nested
    @DisplayName("the compiled table")
    class Table {

        @Test
        @DisplayName("exposes no mutable view")
        void viewsAreUnmodifiable() throws Exception {
            ItemGateTable<TestMaterial> gates = compile(shipped());
            assertThrows(UnsupportedOperationException.class,
                    () -> gates.gatedMaterials().add(TestMaterial.DIRT));
            assertThrows(UnsupportedOperationException.class, () -> gates.assignments().clear());
        }

        @Test
        @DisplayName("a recompile produces a whole new table, leaving the old one untouched")
        void recompileReplacesRatherThanMutates() throws Exception {
            // This is what /asr reload does: build a new table, publish it, drop the old reference.
            // Nothing is flushed in place, which is why the swap needs no lock.
            ItemGateTable<TestMaterial> before = compile(shipped());
            int sizeBefore = before.size();

            warnings.clear();
            ItemGateTable<TestMaterial> after = compile(yaml("""
                    item-progression:
                      gated-items:
                        only-tier:
                          items:
                            - "MACE"
                    """));

            assertEquals(1, after.size());
            assertEquals("only-tier", tierOf(after, TestMaterial.MACE));
            assertEquals(sizeBefore, before.size(), "the previous table must be untouched");
            assertEquals("netherite-tier", tierOf(before, TestMaterial.MACE));
        }
    }

    // -------------------------------------------------------------------------------------------

    private static PluginConfig.ItemTier tier(String id, List<String> patterns, List<String> items,
                                              List<String> excluded) {
        return new PluginConfig.ItemTier(id, patterns, excluded, items, List.of(), 0.0D, 0, "");
    }

    private ItemGateTable<TestMaterial> compileTier(PluginConfig.ItemTier tier)
            throws GateCollisionException {
        return compileAll(List.of(tier));
    }

    private ItemGateTable<TestMaterial> compileAll(List<PluginConfig.ItemTier> tiers)
            throws GateCollisionException {
        return ItemGateCompiler.compile(TestMaterial.class, TestMaterial.UNIVERSE, tiers, warnings);
    }
}
