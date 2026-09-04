package com.ninja6.antispeedrun.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real parsing code end to end.
 *
 * <p>Bukkit's {@code FileConfiguration} is a {@code compileOnly} dependency and is not on the test
 * classpath, so {@link PluginConfig} parses from {@link ConfigSection}. These tests feed it genuine
 * YAML through SnakeYAML — the same parser Bukkit uses — via {@link MapConfigSection}, so the
 * production parsing path, the fallback policy and the malformed-document path are all really run
 * rather than simulated.
 */
class PluginConfigTest {

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    /** Mirrors AntiSpeedrunPlugin's file source: parse YAML, or fail with the named error. */
    private static ConfigSection yaml(String document) throws ConfigLoadException {
        return parse(new java.io.StringReader(document), "<inline document>");
    }

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

    /** The config.yml this plugin actually ships, read off the classpath. */
    private static PluginConfig shipped() throws ConfigLoadException, IOException {
        try (InputStream in = PluginConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "src/main/resources/config.yml must be on the test classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return PluginConfig.from(parse(reader, "config.yml"));
            }
        }
    }

    private static boolean mentions(List<String> warnings, String fragment) {
        return warnings.stream().anyMatch(w -> w.contains(fragment));
    }

    /** A logger that keeps every record, so the reload path's logging can be asserted. */
    private static final class CapturingLogger extends Handler {
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final Logger logger = Logger.getAnonymousLogger();

        CapturingLogger() {
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(this);
        }

        Logger logger() {
            return logger;
        }

        List<LogRecord> at(Level level) {
            List<LogRecord> matching = new ArrayList<>();
            for (LogRecord record : records) {
                if (record.getLevel().equals(level)) {
                    matching.add(record);
                }
            }
            return matching;
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the shipped config.yml")
    class Shipped {

        @Test
        @DisplayName("parses with no warnings, so every key has a typed accessor")
        void parsesClean() throws Exception {
            PluginConfig config = shipped();
            assertTrue(config.isClean(),
                    "every key in config.yml must be recognised and correctly typed; got: "
                            + config.warnings());
        }

        @Test
        @DisplayName("matches the specification values corrected in #50")
        void matchesSpecification() throws Exception {
            PluginConfig c = shipped();

            assertEquals(PluginConfig.Profile.SMP_STANDARD, c.profile());

            PluginConfig.DimensionGate nether = c.dimensionGates().nether();
            assertTrue(nether.enabled());
            assertEquals(0.0D, nether.requirePlaytimeHours(), "gates are advancement-driven");
            assertEquals(List.of("minecraft:story/smelt_iron"), nether.requireAdvancements());

            PluginConfig.DimensionGate end = c.dimensionGates().theEnd();
            assertTrue(end.enabled());
            assertEquals(0.0D, end.requirePlaytimeHours(), "was 20h before #50");
            assertEquals(0, end.requireAccountAgeDays(), "was 7d before #50");
            assertEquals(List.of("minecraft:story/mine_diamond",
                    "minecraft:nether/obtain_blaze_rod",
                    "minecraft:nether/find_fortress"), end.requireAdvancements());

            assertEquals(12.0D, c.antiCheese().maxSingleHitBossDamage(), "was 50.0 before #50");
            assertEquals(500, c.antiCheese().outerEndRadius());
            assertEquals(2, c.antiCheese().outerEndPollSeconds());
            assertTrue(c.antiCheese().blockGatewayPreDragon());

            assertTrue(c.bossScaling().scaleResummonedDragons());
            assertEquals(30, c.bossScaling().battlePrepSeconds());
            assertEquals(0.05D, c.bossScaling().skullDropChance());
            assertTrue(c.bossScaling().exitPortalLockDuringBattle());
            assertEquals(10, c.bossScaling().exitPortalLockReleaseMinutes());
            assertEquals(0.5D, c.bossScaling().multiDragon().multiplier());
            assertEquals(PluginConfig.RoundingMode.HALF_UP, c.bossScaling().multiDragon().roundingMode());
            assertEquals(5, c.bossScaling().multiDragon().maxDragons());
            assertTrue(c.bossScaling().multiDragon().balancedXp());
            assertEquals(PluginConfig.PlacementMode.TOP_PILLAR,
                    c.bossScaling().exitPortalEgg().placementMode());

            assertEquals(PluginConfig.DisplayType.ACTIONBAR, c.idleReminder().displayType());
            assertEquals(15, c.idleReminder().standStillSeconds());
            assertEquals(10, c.idleReminder().cooldownMinutes());
            assertEquals(5, c.idleReminder().displayDurationSeconds());

            assertTrue(c.journeyBook().giveOnFirstJoin());
            assertEquals("Ninja6-MC", c.journeyBook().author());

            assertFalse(c.trimProgression().gateNaturalTrimChests());
            assertTrue(c.trimProgression().blockUnearnedSmithing());

            assertFalse(c.villagerProgression().gateMendingTrade());
            assertEquals("minecraft:story/cure_zombie_villager",
                    c.villagerProgression().requiredAdvancement());
        }

        @Test
        @DisplayName("carries the five gated-item tiers in document order")
        void gatedItemTiers() throws Exception {
            PluginConfig.ItemProgression items = shipped().itemProgression();

            assertTrue(items.enabled());
            assertTrue(items.dropRecallEnabled());
            assertTrue(items.gateDispensers());
            assertTrue(items.gateNestedBundles());
            assertEquals(3, items.feedbackCooldownSeconds());

            assertEquals(List.of("iron-tier", "diamond-tier", "nether-tier", "end-tier",
                            "netherite-tier"),
                    items.gatedItems().stream().map(PluginConfig.ItemTier::id).toList());

            PluginConfig.ItemTier iron = items.tier("iron-tier").orElseThrow();
            assertEquals(List.of("IRON_*", "RAW_IRON*", "CHAINMAIL_*", "*_IRON_ORE"),
                    iron.matchPatterns(), "the suffix pattern catches DEEPSLATE_IRON_ORE");
            assertTrue(iron.excludeMaterials().contains("IRON_DOOR"));
            assertTrue(iron.items().contains("TRIAL_KEY"));
            assertEquals(List.of("minecraft:story/mine_stone"), iron.requireAdvancements());

            PluginConfig.ItemTier diamond = items.tier("diamond-tier").orElseThrow();
            assertEquals(List.of("DIAMOND_*", "*_DIAMOND_ORE"), diamond.matchPatterns());
            assertEquals(List.of("DIAMOND"), diamond.items(),
                    "DIAMOND_* has no trailing underscore, so the gem needs naming outright");

            PluginConfig.ItemTier end = items.tier("end-tier").orElseThrow();
            assertEquals(0.0D, end.requirePlaytimeHours(), "was 20h before #50");
            assertEquals(0, end.requireAccountAgeDays(), "was 7d before #50");

            PluginConfig.ItemTier netherite = items.tier("netherite-tier").orElseThrow();
            assertEquals(List.of("NETHERITE_*"), netherite.matchPatterns());
            assertTrue(netherite.items().containsAll(List.of("ANCIENT_DEBRIS", "MACE", "HEAVY_CORE")));
            assertEquals(List.of("NETHERITE_UPGRADE_SMITHING_TEMPLATE"), netherite.excludeMaterials(),
                    "the template matches NETHERITE_* but belongs to trim-progression");

            assertTrue(items.tier("no-such-tier").isEmpty());
        }

        @Test
        @DisplayName("agrees with the code-level defaults, so the two cannot drift apart")
        void agreesWithCodeDefaults() throws Exception {
            PluginConfig s = shipped();
            PluginConfig d = PluginConfig.defaults();

            assertEquals(d.profile(), s.profile());
            assertEquals(d.dimensionGates(), s.dimensionGates());
            assertEquals(d.trimProgression(), s.trimProgression());
            assertEquals(d.idleReminder(), s.idleReminder());
            assertEquals(d.journeyBook(), s.journeyBook());
            assertEquals(d.bossScaling(), s.bossScaling());
            assertEquals(d.antiCheese(), s.antiCheese());
            assertEquals(d.villagerProgression(), s.villagerProgression());

            // gated-items is the one section with no code-level default: an absent block means no
            // tiers are gated, which cannot be expressed as a duplicate of the shipped table.
            assertEquals(d.itemProgression().enabled(), s.itemProgression().enabled());
            assertEquals(d.itemProgression().dropRecallEnabled(), s.itemProgression().dropRecallEnabled());
            assertEquals(d.itemProgression().gateDispensers(), s.itemProgression().gateDispensers());
            assertEquals(d.itemProgression().gateNestedBundles(), s.itemProgression().gateNestedBundles());
            assertEquals(d.itemProgression().feedbackCooldownSeconds(),
                    s.itemProgression().feedbackCooldownSeconds());
            assertEquals(d.itemProgression().rejectionMessage(), s.itemProgression().rejectionMessage());
            assertTrue(d.itemProgression().gatedItems().isEmpty());
        }
    }

    @Nested
    @DisplayName("absent keys")
    class Absent {

        @Test
        @DisplayName("an empty document yields the shipped defaults, silently")
        void emptyDocument() throws Exception {
            PluginConfig config = PluginConfig.from(yaml("# nothing but a comment\n"));
            assertEquals(PluginConfig.defaults(), config);
            assertEquals(1, config.warnings().size(),
                    "a missing key is not a warning; the only warning here is the empty tier table: "
                            + config.warnings());
            assertTrue(mentions(config.warnings(), "gated-items declares no tiers"));
        }

        @Test
        @DisplayName("say out loud that the defaults gate nothing, rather than failing silently")
        void defaultsAnnounceThatNoItemIsGated() {
            PluginConfig defaults = PluginConfig.defaults();

            // gated-items is the only section with no code-level default, and defaults() is the
            // startup fallback when config.yml will not parse. Left silent, one YAML typo would
            // disable every item gate while every other gate kept working.
            assertTrue(defaults.itemProgression().enabled());
            assertTrue(defaults.itemProgression().gatedItems().isEmpty());
            assertFalse(defaults.isClean(), "this state must never be silent");
            assertTrue(mentions(defaults.warnings(),
                    "item-progression: gated-items declares no tiers while enabled is true"));
            assertTrue(mentions(defaults.warnings(), "check whether config.yml failed to load"));
        }

        @Test
        @DisplayName("stay quiet when item progression is switched off deliberately")
        void noComplaintWhenItemProgressionIsDisabled() throws Exception {
            PluginConfig config = PluginConfig.from(yaml("""
                    item-progression:
                      enabled: false
                    """));
            assertFalse(mentions(config.warnings(), "gated-items declares no tiers"),
                    "an operator who turned the feature off does not need to be told it is off");
        }

        @Test
        @DisplayName("a partial document keeps configured keys and defaults the rest")
        void partialDocument() throws Exception {
            PluginConfig config = PluginConfig.from(yaml("""
                    profile: HARDCORE
                    anti-cheese:
                      max-single-hit-boss-damage: 4.0
                    """));

            assertEquals(PluginConfig.Profile.HARDCORE, config.profile());
            assertEquals(4.0D, config.antiCheese().maxSingleHitBossDamage());
            assertEquals(500, config.antiCheese().outerEndRadius(), "untouched sibling defaults");
            assertEquals(PluginConfig.defaults().dimensionGates(), config.dimensionGates());
            assertEquals(List.of(), config.warnings().stream()
                            .filter(w -> !w.contains("gated-items declares no tiers")).toList(),
                    "an unconfigured key is not a warning");
        }

        @Test
        @DisplayName("an absent section is not the same as a disabled one")
        void absentSectionIsNotDisabled() throws Exception {
            PluginConfig config = PluginConfig.from(yaml("profile: CASUAL\n"));
            assertTrue(config.dimensionGates().nether().enabled());
            assertTrue(config.itemProgression().enabled());
        }
    }

    @Nested
    @DisplayName("wrong-typed and unknown keys")
    class Malformed {

        @Test
        @DisplayName("a wrong-typed scalar warns and falls back to the default")
        void wrongTypedScalars() throws Exception {
            PluginConfig config = PluginConfig.from(yaml("""
                    item-progression:
                      enabled: "yes please"
                      feedback-cooldown-seconds: "three"
                    anti-cheese:
                      max-single-hit-boss-damage: [1, 2]
                    journey-book:
                      author: {name: Ninja6}
                    """));

            assertTrue(config.itemProgression().enabled(), "default retained");
            assertEquals(3, config.itemProgression().feedbackCooldownSeconds());
            assertEquals(12.0D, config.antiCheese().maxSingleHitBossDamage());
            assertEquals("Ninja6-MC", config.journeyBook().author());

            assertTrue(mentions(config.warnings(), "item-progression.enabled: expected a boolean"));
            assertTrue(mentions(config.warnings(), "item-progression.feedback-cooldown-seconds: expected an integer"));
            assertTrue(mentions(config.warnings(), "anti-cheese.max-single-hit-boss-damage: expected a number"));
            assertTrue(mentions(config.warnings(), "journey-book.author: expected a string"));
        }

        @Test
        @DisplayName("a wrong-typed list warns and falls back; a bad element is dropped")
        void wrongTypedLists() throws Exception {
            PluginConfig config = PluginConfig.from(yaml("""
                    dimension-gates:
                      nether:
                        require-advancements: "minecraft:story/smelt_iron"
                      the_end:
                        require-advancements:
                          - "minecraft:story/mine_diamond"
                          - {oops: true}
                    """));

            assertEquals(List.of("minecraft:story/smelt_iron"),
                    config.dimensionGates().nether().requireAdvancements(),
                    "scalar where a list was expected falls back to the shipped default");
            assertEquals(List.of("minecraft:story/mine_diamond"),
                    config.dimensionGates().theEnd().requireAdvancements());

            assertTrue(mentions(config.warnings(),
                    "dimension-gates.nether.require-advancements: expected a list of strings"));
            assertTrue(mentions(config.warnings(),
                    "dimension-gates.the_end.require-advancements: dropped a list entry"));
        }

        @Test
        @DisplayName("an unrecognised enum constant warns and lists the accepted values")
        void badEnum() throws Exception {
            PluginConfig config = PluginConfig.from(yaml("""
                    profile: ULTRA_HARDCORE
                    idle-reminder:
                      display-type: HOLOGRAM
                    boss-scaling:
                      exit-portal-egg:
                        placement-mode: top-pillar
                    """));

            assertEquals(PluginConfig.Profile.SMP_STANDARD, config.profile());
            assertEquals(PluginConfig.DisplayType.ACTIONBAR, config.idleReminder().displayType());
            assertEquals(PluginConfig.PlacementMode.TOP_PILLAR,
                    config.bossScaling().exitPortalEgg().placementMode(),
                    "hyphens and case are normalised, so this one is accepted");

            assertTrue(mentions(config.warnings(), "profile: \"ULTRA_HARDCORE\" is not one of"));
            assertTrue(mentions(config.warnings(), "idle-reminder.display-type"));
        }

        @Test
        @DisplayName("a scalar where a section belongs warns and defaults the whole section")
        void scalarWhereSectionExpected() throws Exception {
            PluginConfig config = PluginConfig.from(yaml("trim-progression: true\n"));

            assertEquals(PluginConfig.defaults().trimProgression(), config.trimProgression());
            assertTrue(mentions(config.warnings(), "trim-progression: expected a section"));
        }

        @Test
        @DisplayName("an unknown key is ignored with a warning that names it")
        void unknownKey() throws Exception {
            PluginConfig config = PluginConfig.from(yaml("""
                    profile: CASUAL
                    gate-armor-stands: true
                    item-progression:
                      gate-natural-structure-chests: true
                    """));

            assertTrue(mentions(config.warnings(), "unknown key gate-armor-stands"));
            assertTrue(mentions(config.warnings(),
                    "unknown key item-progression.gate-natural-structure-chests"),
                    "the provenance keys retired in docs/provenance-model.md must warn, not parse");
        }

        @Test
        @DisplayName("a documented invariant violation warns and falls back")
        void invariantViolation() throws Exception {
            PluginConfig config = PluginConfig.from(yaml("""
                    boss-scaling:
                      multi-dragon:
                        multiplier: 0.0
                      battle-prep-seconds: -5
                    """));

            assertEquals(0.5D, config.bossScaling().multiDragon().multiplier());
            assertEquals(30, config.bossScaling().battlePrepSeconds());
            assertTrue(mentions(config.warnings(),
                    "boss-scaling.multi-dragon.multiplier: must be greater than 0.0"));
            assertTrue(mentions(config.warnings(), "boss-scaling.battle-prep-seconds: must be at least 0"));
        }

        @Test
        @DisplayName("a syntactically broken document is a ConfigLoadException, not a silent empty config")
        void brokenDocument() {
            ConfigLoadException failure = assertThrows(ConfigLoadException.class,
                    () -> yaml("""
                            profile: SMP_STANDARD
                            dimension-gates:
                              nether:
                                enabled: true
                               rejection-message: "bad indent"
                            """));
            assertTrue(failure.getMessage().contains("could not be parsed"), failure.getMessage());
        }

        @Test
        @DisplayName("a document whose root is not a mapping is rejected")
        void nonMappingRoot() {
            assertThrows(ConfigLoadException.class, () -> yaml("- just\n- a list\n"));
        }
    }

    @Nested
    @DisplayName("the snapshot itself")
    class Snapshot {

        @Test
        @DisplayName("exposes no mutable collection")
        void collectionsAreUnmodifiable() throws Exception {
            PluginConfig config = shipped();
            assertThrows(UnsupportedOperationException.class, () -> config.warnings().add("x"));
            assertThrows(UnsupportedOperationException.class,
                    () -> config.itemProgression().gatedItems().clear());
            assertThrows(UnsupportedOperationException.class,
                    () -> config.dimensionGates().nether().requireAdvancements().add("x"));
            assertThrows(UnsupportedOperationException.class,
                    () -> config.itemProgression().gatedItems().get(0).matchPatterns().add("x"));
        }

        @Test
        @DisplayName("does not alias the caller's collections")
        void doesNotAliasCallerCollections() {
            List<String> mutable = new ArrayList<>(List.of("a"));
            PluginConfig.ItemTier tier = new PluginConfig.ItemTier("t", mutable, List.of(),
                    List.of(), List.of(), 0.0D, 0, "");
            mutable.add("b");
            assertEquals(List.of("a"), tier.matchPatterns());
        }

        @Test
        @DisplayName("has value semantics, so two loads of the same document are equal")
        void valueSemantics() throws Exception {
            assertEquals(PluginConfig.from(yaml("profile: CASUAL\n")),
                    PluginConfig.from(yaml("profile: CASUAL\n")));
        }
    }

    @Nested
    @DisplayName("the reload swap")
    class Reload {

        @Test
        @DisplayName("swaps the whole snapshot when the new document parses")
        void swapsOnSuccess() throws Exception {
            CapturingLogger log = new CapturingLogger();
            ConfigSnapshotHolder holder = new ConfigSnapshotHolder(log.logger(), PluginConfig.defaults());

            assertTrue(holder.reload(() -> yaml("profile: HARDCORE\n")));
            assertEquals(PluginConfig.Profile.HARDCORE, holder.get().profile());
        }

        @Test
        @DisplayName("keeps the previous snapshot live and logs a named error when the file is malformed")
        void keepsPreviousSnapshotOnFailure() throws Exception {
            CapturingLogger log = new CapturingLogger();
            ConfigSnapshotHolder holder = new ConfigSnapshotHolder(log.logger(), PluginConfig.defaults());
            assertTrue(holder.reload(() -> yaml("profile: HARDCORE\n")));
            PluginConfig live = holder.get();

            assertFalse(holder.reload(() -> yaml("profile: HARDCORE\n  bad: indent\n")),
                    "a malformed document must be rejected");

            assertSame(live, holder.get(), "the previous snapshot must still be live");
            assertEquals(PluginConfig.Profile.HARDCORE, holder.get().profile());

            List<LogRecord> severe = log.at(Level.SEVERE);
            assertEquals(1, severe.size(), "exactly one named error");
            assertTrue(severe.get(0).getMessage().startsWith("ConfigLoadException:"),
                    severe.get(0).getMessage());
            assertTrue(severe.get(0).getMessage().contains("has NOT been applied"));
            assertTrue(severe.get(0).getThrown() instanceof ConfigLoadException);
        }

        @Test
        @DisplayName("an unexpected runtime failure in the source is also survivable")
        void survivesRuntimeFailure() {
            CapturingLogger log = new CapturingLogger();
            PluginConfig initial = PluginConfig.defaults();
            ConfigSnapshotHolder holder = new ConfigSnapshotHolder(log.logger(), initial);

            assertFalse(holder.reload(() -> {
                throw new IllegalStateException("disk gremlin");
            }));
            assertSame(initial, holder.get());
            assertEquals(1, log.at(Level.SEVERE).size());
        }

        @Test
        @DisplayName("state derived from the candidate is built before the candidate is published")
        void derivedStateIsBuiltBeforePublication() {
            CapturingLogger log = new CapturingLogger();
            ConfigSnapshotHolder holder = new ConfigSnapshotHolder(log.logger(), PluginConfig.defaults());
            AtomicReference<PluginConfig> seenByBinding = new AtomicReference<>();

            java.util.Optional<String> derived = holder.reload(() -> yaml("profile: HARDCORE\n"),
                    candidate -> {
                        // The binding must see the candidate, and the holder must still be serving
                        // the previous snapshot while it runs -- that ordering is the whole point.
                        seenByBinding.set(candidate);
                        assertEquals(PluginConfig.Profile.SMP_STANDARD, holder.get().profile(),
                                "the candidate must not be live yet");
                        return "derived from " + candidate.profile();
                    });

            assertEquals("derived from HARDCORE", derived.orElseThrow());
            assertEquals(PluginConfig.Profile.HARDCORE, holder.get().profile());
            assertEquals(PluginConfig.Profile.HARDCORE, seenByBinding.get().profile());
        }

        @Test
        @DisplayName("a binding that rejects the candidate leaves the previous snapshot live")
        void rejectedDerivedStateChangesNothing() {
            // Task 4.2.1's item gate table is compiled through this path. Compiling it after the
            // swap would leave the operator's new tiers live beside the old material assignments,
            // and a listener reading both would enforce requirements from two different files.
            CapturingLogger log = new CapturingLogger();
            ConfigSnapshotHolder holder = new ConfigSnapshotHolder(log.logger(), PluginConfig.defaults());
            assertTrue(holder.reload(() -> yaml("profile: CASUAL\n")));
            PluginConfig live = holder.get();

            java.util.Optional<String> derived = holder.reload(() -> yaml("profile: HARDCORE\n"),
                    candidate -> {
                        throw new IllegalStateException("tiers collide over MACE");
                    });

            assertTrue(derived.isEmpty());
            assertSame(live, holder.get(), "the rejected candidate must not have been published");
            assertEquals(PluginConfig.Profile.CASUAL, holder.get().profile());

            List<LogRecord> severe = log.at(Level.SEVERE);
            assertEquals(1, severe.size());
            assertTrue(severe.get(0).getMessage().contains("was NOT applied"),
                    severe.get(0).getMessage());
            assertTrue(severe.get(0).getMessage().contains("tiers collide over MACE"),
                    "the cause must name itself: " + severe.get(0).getMessage());
        }

        @Test
        @DisplayName("a binding is not consulted at all when the document will not parse")
        void bindingIsSkippedWhenParsingFails() {
            CapturingLogger log = new CapturingLogger();
            ConfigSnapshotHolder holder = new ConfigSnapshotHolder(log.logger(), PluginConfig.defaults());
            AtomicBoolean called = new AtomicBoolean();

            assertTrue(holder.reload(() -> yaml("profile: HARDCORE\n  bad: indent\n"),
                    candidate -> {
                        called.set(true);
                        return candidate;
                    }).isEmpty());
            assertFalse(called.get());
        }

        @Test
        @DisplayName("logs each recoverable warning after a successful swap")
        void logsWarnings() {
            CapturingLogger log = new CapturingLogger();
            ConfigSnapshotHolder holder = new ConfigSnapshotHolder(log.logger(), PluginConfig.defaults());

            assertTrue(holder.reload(() -> yaml("nonsense-key: 1\nprofile: 12345\n")));
            assertFalse(holder.get().isClean());
            assertEquals(holder.get().warnings().size(), log.at(Level.WARNING).size());
            assertTrue(log.at(Level.SEVERE).isEmpty(), "a warning is not an error");
        }

        @Test
        @DisplayName("a concurrent reader never observes a half-applied configuration")
        void readerNeverSeesAMixedSnapshot() throws Exception {
            CapturingLogger log = new CapturingLogger();
            ConfigSnapshotHolder holder = new ConfigSnapshotHolder(log.logger(), PluginConfig.defaults());

            String casual = "profile: CASUAL\nanti-cheese:\n  max-single-hit-boss-damage: 40.0\n"
                    + "  outer-end-radius: 100\n";
            String hardcore = "profile: HARDCORE\nanti-cheese:\n  max-single-hit-boss-damage: 4.0\n"
                    + "  outer-end-radius: 900\n";

            int readers = 6;
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean stop = new AtomicBoolean(false);
            AtomicReference<String> mixed = new AtomicReference<>();
            List<Thread> threads = new ArrayList<>();

            for (int i = 0; i < readers; i++) {
                Thread reader = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    while (!stop.get()) {
                        // Exactly what a handler does: one read, then decide from that local.
                        PluginConfig snapshot = holder.get();
                        boolean consistent = switch (snapshot.profile()) {
                            case CASUAL -> snapshot.antiCheese().maxSingleHitBossDamage() == 40.0D
                                    && snapshot.antiCheese().outerEndRadius() == 100;
                            case HARDCORE -> snapshot.antiCheese().maxSingleHitBossDamage() == 4.0D
                                    && snapshot.antiCheese().outerEndRadius() == 900;
                            default -> snapshot.equals(PluginConfig.defaults());
                        };
                        if (!consistent) {
                            mixed.compareAndSet(null, "observed " + snapshot.profile() + " with "
                                    + snapshot.antiCheese());
                            return;
                        }
                    }
                });
                threads.add(reader);
                reader.start();
            }

            start.countDown();
            for (int i = 0; i < 300; i++) {
                String document = (i % 2 == 0) ? casual : hardcore;
                assertTrue(holder.reload(() -> yaml(document)));
            }
            stop.set(true);
            for (Thread reader : threads) {
                reader.join(TimeUnit.SECONDS.toMillis(10));
            }

            assertNull(mixed.get(), "a reader saw a half-applied configuration");
        }
    }

    @Nested
    @DisplayName("MapConfigSection")
    class Sections {

        @Test
        @DisplayName("copies its backing map so a later mutation cannot reach the section")
        void defensivelyCopies() {
            Map<String, Object> backing = new java.util.LinkedHashMap<>();
            backing.put("profile", "CASUAL");
            ConfigSection section = MapConfigSection.of(backing);
            backing.put("profile", "HARDCORE");

            assertEquals("CASUAL", section.get("profile"));
            assertThrows(UnsupportedOperationException.class, () -> section.keys().add("extra"));
        }

        @Test
        @DisplayName("reports a non-mapping child as absent rather than throwing")
        void nonMappingChild() {
            ConfigSection section = MapConfigSection.of(Map.of("a", "scalar"));
            assertNull(section.section("a"));
            assertTrue(section.contains("a"));
            assertFalse(section.contains("b"));
        }
    }
}
