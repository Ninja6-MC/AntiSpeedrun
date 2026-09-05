package com.ninja6.antispeedrun.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, fully typed snapshot of {@code config.yml}.
 *
 * <p>Built once from a {@link ConfigSection} and never mutated. Every field is final, every nested
 * type is a record or an enum, and every collection is copied with {@code List.copyOf}, so the
 * whole graph is safely publishable to any number of Folia region threads by a single
 * {@code volatile} write. There is no setter anywhere in the graph and no mutable field is
 * reachable from a constructed instance.
 *
 * <p>Fallback policy, applied uniformly by {@link ConfigReader}: an absent key falls back silently
 * to the shipped default recorded in each accessor's javadoc below; a key of the wrong type falls
 * back to the same default and records a {@link #warnings() warning}; an unknown key is ignored
 * with a warning. Only a document that cannot be parsed at all is a
 * {@link ConfigLoadException}, and that never disables the plugin — see
 * {@link ConfigSnapshotHolder}.
 *
 * <p>Values here are modelled exactly as {@code config.yml} states them. Match patterns are kept
 * as raw strings: compiling them into material sets is Task 4.2.1's job, not this type's.
 *
 * @param profile              tuning profile the server has selected; default {@link Profile#SMP_STANDARD}
 * @param dimensionGates       section 1, dimension progression gates
 * @param itemProgression      section 2, anti-boosting and item progression gating
 * @param trimProgression      section 3, armor trim and smithing template progression
 * @param idleReminder         section 4, idle reminder engine
 * @param journeyBook          section 5, journey guide book
 * @param bossScaling          section 6, multi-dragon boss combat scaling
 * @param antiCheese           section 7, anti-cheese engine
 * @param villagerProgression  section 8, villager progression
 * @param warnings             every recoverable problem found while parsing; empty for a clean
 *                             load. Callers log these; they are not errors.
 *                             <p>The order is <strong>parse order, not document order</strong>,
 *                             and is deliberately left unspecified: within a section the
 *                             unknown-key sweep runs before the per-key reads, and sections are
 *                             visited in the order the parser calls them rather than the order
 *                             they appear in the file. It is, however, <em>deterministic</em> —
 *                             the same document always produces the same list in the same order —
 *                             because every backing section preserves document order for its own
 *                             keys. Nothing may depend on the order beyond that; neither backing
 *                             model carries line numbers, so a truthful document order is not
 *                             recoverable here.
 */
public record PluginConfig(
        Profile profile,
        DimensionGates dimensionGates,
        ItemProgression itemProgression,
        TrimProgression trimProgression,
        IdleReminder idleReminder,
        JourneyBook journeyBook,
        BossScaling bossScaling,
        AntiCheese antiCheese,
        VillagerProgression villagerProgression,
        List<String> warnings) {

    /** Canonical compact constructor: defensively copies the only collection held directly. */
    public PluginConfig {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(dimensionGates, "dimensionGates");
        Objects.requireNonNull(itemProgression, "itemProgression");
        Objects.requireNonNull(trimProgression, "trimProgression");
        Objects.requireNonNull(idleReminder, "idleReminder");
        Objects.requireNonNull(journeyBook, "journeyBook");
        Objects.requireNonNull(bossScaling, "bossScaling");
        Objects.requireNonNull(antiCheese, "antiCheese");
        Objects.requireNonNull(villagerProgression, "villagerProgression");
        warnings = List.copyOf(warnings);
    }

    // ---------------------------------------------------------------------------------------
    // Shipped defaults. These mirror src/main/resources/config.yml exactly; PluginConfigTest
    // asserts that the shipped file and these defaults agree, so drift fails the build.
    // ---------------------------------------------------------------------------------------

    private static final String DEFAULT_NETHER_REJECTION =
            "<red>🔒 The Nether is locked! Requires <yellow>Iron Gear<red> "
                    + "(Smelt an iron ingot). Type <gold>/progress";
    private static final String DEFAULT_END_REJECTION =
            "<red>🔒 The End is sealed! Complete survival progression first. "
                    + "Type <gold>/progress";
    private static final String DEFAULT_ITEM_REJECTION =
            "<red>🔒 You cannot pick up <yellow>{ITEM}<red>! Requires: <gold>{REQUIREMENT}";
    private static final String DEFAULT_IDLE_MESSAGE =
            "<yellow>💡 Next Goal: <white>{NEXT_STEP} <gray>(Run <gold>/progress<gray>)";

    /**
     * Parses a complete snapshot from {@code root}.
     *
     * @param root the document root; {@code MapConfigSection.EMPTY} yields {@link #defaults()}
     * @throws ConfigLoadException if the document root is unusable
     */
    public static PluginConfig from(ConfigSection root) throws ConfigLoadException {
        if (root == null) {
            throw new ConfigLoadException("config.yml has no root mapping");
        }
        List<String> warnings = new ArrayList<>();
        ConfigReader r = new ConfigReader(root, "", warnings);
        r.expect("profile", "dimension-gates", "item-progression", "trim-progression",
                "idle-reminder", "journey-book", "boss-scaling", "anti-cheese",
                "villager-progression");

        return new PluginConfig(
                r.enumValue("profile", Profile.class, Profile.SMP_STANDARD),
                parseDimensionGates(r.child("dimension-gates")),
                parseItemProgression(r.child("item-progression")),
                parseTrimProgression(r.child("trim-progression")),
                parseIdleReminder(r.child("idle-reminder")),
                parseJourneyBook(r.child("journey-book")),
                parseBossScaling(r.child("boss-scaling")),
                parseAntiCheese(r.child("anti-cheese")),
                parseVillagerProgression(r.child("villager-progression")),
                warnings);
    }

    /**
     * The shipped defaults, as parsed from an empty document. Used as the startup fallback when
     * {@code config.yml} cannot be read at all, so the plugin runs on known-good values rather
     * than being disabled.
     *
     * <p>This is deliberately <strong>not</strong> {@link #isClean() clean}: {@code gated-items}
     * has no code-level default, so the defaults gate no items at all, and that warning is what
     * makes the fallback state visible in the log instead of silent. Duplicating the shipped tier
     * table as Java literals would remove the warning at the price of a second copy to drift.
     */
    public static PluginConfig defaults() {
        try {
            return from(MapConfigSection.EMPTY);
        } catch (ConfigLoadException impossible) {
            throw new IllegalStateException("the empty document must always parse", impossible);
        }
    }

    /** Whether this snapshot parsed without any recoverable problem. */
    public boolean isClean() {
        return warnings.isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // Section 1 - dimension gates
    // ---------------------------------------------------------------------------------------

    private static DimensionGates parseDimensionGates(ConfigReader r) {
        r.expect("nether", "the_end");
        return new DimensionGates(
                parseDimensionGate(r.child("nether"), List.of("minecraft:story/smelt_iron"),
                        DEFAULT_NETHER_REJECTION),
                parseDimensionGate(r.child("the_end"),
                        List.of("minecraft:story/mine_diamond",
                                "minecraft:nether/obtain_blaze_rod",
                                "minecraft:nether/find_fortress"),
                        DEFAULT_END_REJECTION));
    }

    private static DimensionGate parseDimensionGate(ConfigReader r, List<String> defaultAdvancements,
                                                    String defaultRejection) {
        r.expect("enabled", "require-playtime-hours", "require-account-age-days",
                "require-advancements", "rejection-message");
        return new DimensionGate(
                r.bool("enabled", true),
                r.decimal("require-playtime-hours", 0.0D),
                r.integer("require-account-age-days", 0),
                r.strings("require-advancements", defaultAdvancements),
                r.string("rejection-message", defaultRejection));
    }

    // ---------------------------------------------------------------------------------------
    // Section 2 - item progression
    // ---------------------------------------------------------------------------------------

    private static ItemProgression parseItemProgression(ConfigReader r) {
        r.expect("enabled", "drop-recall-enabled", "gate-dispensers", "gate-nested-bundles",
                "feedback-cooldown-seconds", "rejection-message", "gated-items");

        ConfigReader tiers = r.child("gated-items");
        List<ItemTier> parsed = new ArrayList<>();
        for (String id : tiers.keys()) {
            parsed.add(parseItemTier(id, tiers.child(id)));
        }

        boolean enabled = r.bool("enabled", true);
        if (enabled && parsed.isEmpty()) {
            // gated-items is the one section with no code-level default, so this combination is
            // exactly what defaults() produces -- and defaults() is the startup fallback when
            // config.yml cannot be parsed. Without this line a single YAML typo would leave every
            // dimension gate and anti-cheese rule working while every item gate was silently
            // inert, with nothing in the log saying so.
            r.note("gated-items declares no tiers while enabled is true, so NO item is gated. "
                    + "Item progression is effectively off. This is also what the built-in "
                    + "defaults produce, so check whether config.yml failed to load.");
        }

        return new ItemProgression(
                enabled,
                r.bool("drop-recall-enabled", true),
                r.bool("gate-dispensers", true),
                r.bool("gate-nested-bundles", true),
                r.atLeast("feedback-cooldown-seconds", 3, 0),
                r.string("rejection-message", DEFAULT_ITEM_REJECTION),
                parsed);
    }

    private static ItemTier parseItemTier(String id, ConfigReader r) {
        r.expect("match-patterns", "exclude-materials", "items", "require-advancements",
                "require-playtime-hours", "require-account-age-days", "hint");
        return new ItemTier(
                id,
                r.strings("match-patterns", List.of()),
                r.strings("exclude-materials", List.of()),
                r.strings("items", List.of()),
                r.strings("require-advancements", List.of()),
                r.decimal("require-playtime-hours", 0.0D),
                r.integer("require-account-age-days", 0),
                r.string("hint", ""));
    }

    // ---------------------------------------------------------------------------------------
    // Sections 3 to 8
    // ---------------------------------------------------------------------------------------

    private static TrimProgression parseTrimProgression(ConfigReader r) {
        r.expect("enabled", "gate-natural-trim-chests", "block-unearned-template-duplication",
                "block-unearned-smithing", "block-wearing-unearned-trims");
        return new TrimProgression(
                r.bool("enabled", true),
                r.bool("gate-natural-trim-chests", false),
                r.bool("block-unearned-template-duplication", true),
                r.bool("block-unearned-smithing", true),
                r.bool("block-wearing-unearned-trims", true));
    }

    private static IdleReminder parseIdleReminder(ConfigReader r) {
        r.expect("enabled", "stand-still-seconds", "cooldown-minutes", "display-duration-seconds",
                "display-type", "message");
        return new IdleReminder(
                r.bool("enabled", true),
                r.atLeast("stand-still-seconds", 15, 1),
                r.atLeast("cooldown-minutes", 10, 0),
                r.atLeast("display-duration-seconds", 5, 1),
                r.enumValue("display-type", DisplayType.class, DisplayType.ACTIONBAR),
                r.string("message", DEFAULT_IDLE_MESSAGE));
    }

    private static JourneyBook parseJourneyBook(ConfigReader r) {
        r.expect("give-on-first-join", "title", "author");
        return new JourneyBook(
                r.bool("give-on-first-join", true),
                r.string("title", "<gold>Ninja6 Survival Guide"),
                r.string("author", "Ninja6-MC"));
    }

    private static BossScaling parseBossScaling(ConfigReader r) {
        r.expect("enabled", "scale-resummoned-dragons", "battle-prep-seconds", "multi-dragon",
                "exit-portal-egg", "skull-drop-chance", "exit-portal-lock-during-battle",
                "exit-portal-lock-release-minutes");

        ConfigReader multi = r.child("multi-dragon");
        multi.expect("enabled", "multiplier", "rounding-mode", "max-dragons", "balanced-xp");
        MultiDragon multiDragon = new MultiDragon(
                multi.bool("enabled", true),
                multi.positiveDecimal("multiplier", 0.5D),
                multi.enumValue("rounding-mode", RoundingMode.class, RoundingMode.HALF_UP),
                multi.atLeast("max-dragons", 5, 1),
                multi.bool("balanced-xp", true));

        ConfigReader egg = r.child("exit-portal-egg");
        egg.expect("enabled", "placement-mode");
        ExitPortalEgg exitPortalEgg = new ExitPortalEgg(
                egg.bool("enabled", true),
                egg.enumValue("placement-mode", PlacementMode.class, PlacementMode.TOP_PILLAR));

        return new BossScaling(
                r.bool("enabled", true),
                r.bool("scale-resummoned-dragons", true),
                r.atLeast("battle-prep-seconds", 30, 0),
                multiDragon,
                exitPortalEgg,
                r.decimal("skull-drop-chance", 0.05D),
                r.bool("exit-portal-lock-during-battle", true),
                r.atLeast("exit-portal-lock-release-minutes", 10, 0));
    }

    private static AntiCheese parseAntiCheese(ConfigReader r) {
        r.expect("enabled", "block-bed-anchor-boss-damage", "max-single-hit-boss-damage",
                "block-early-eye-throwing", "block-exit-portal-crystal-place",
                "block-gateway-pre-dragon", "outer-end-radius", "outer-end-poll-seconds");
        return new AntiCheese(
                r.bool("enabled", true),
                r.bool("block-bed-anchor-boss-damage", true),
                r.decimal("max-single-hit-boss-damage", 12.0D),
                r.bool("block-early-eye-throwing", true),
                r.bool("block-exit-portal-crystal-place", true),
                r.bool("block-gateway-pre-dragon", true),
                r.atLeast("outer-end-radius", 500, 1),
                r.atLeast("outer-end-poll-seconds", 2, 1));
    }

    private static VillagerProgression parseVillagerProgression(ConfigReader r) {
        r.expect("gate-mending-trade", "required-advancement");
        return new VillagerProgression(
                r.bool("gate-mending-trade", false),
                r.string("required-advancement", "minecraft:story/cure_zombie_villager"));
    }

    // ---------------------------------------------------------------------------------------
    // Nested model
    // ---------------------------------------------------------------------------------------

    /** Tuning profile. {@code CUSTOM} means the operator has hand-edited away from a preset. */
    public enum Profile { CASUAL, SMP_STANDARD, HARDCORE, CUSTOM }

    /** How an idle reminder reaches the player. Default {@code ACTIONBAR}. */
    public enum DisplayType { ACTIONBAR, TITLE, CHAT }

    /** How fractional dragon-scaling results are rounded. Default {@code HALF_UP}. */
    public enum RoundingMode { HALF_UP, CEIL, FLOOR }

    /** Where the bonus dragon egg is placed after a resummon. Default {@code TOP_PILLAR}. */
    public enum PlacementMode { TOP_PILLAR, ITEM_DROP, NONE }

    /**
     * Section 1. Both gates are always present; an absent block in the file means the gate runs on
     * its shipped defaults, not that it is disabled.
     */
    public record DimensionGates(DimensionGate nether, DimensionGate theEnd) {
        public DimensionGates {
            Objects.requireNonNull(nether, "nether");
            Objects.requireNonNull(theEnd, "theEnd");
        }
    }

    /**
     * One dimension gate.
     *
     * @param enabled               default {@code true}
     * @param requirePlaytimeHours  hours of {@code PLAY_ONE_MINUTE} required; default {@code 0.0},
     *                              meaning the gate is advancement-driven
     * @param requireAccountAgeDays days since first join required; default {@code 0}. See #33 R-15:
     *                              {@code getFirstPlayed()} measures first join to <em>this</em>
     *                              server, so a non-zero value seals the dimension for everyone on
     *                              a fresh world
     * @param requireAdvancements   namespaced advancement keys, all of which must be earned;
     *                              defaults per dimension as shipped
     * @param rejectionMessage      MiniMessage shown on a blocked entry attempt
     */
    public record DimensionGate(
            boolean enabled,
            double requirePlaytimeHours,
            int requireAccountAgeDays,
            List<String> requireAdvancements,
            String rejectionMessage) {
        public DimensionGate {
            requireAdvancements = List.copyOf(requireAdvancements);
            Objects.requireNonNull(rejectionMessage, "rejectionMessage");
        }
    }

    /**
     * Section 2.
     *
     * @param enabled                 default {@code true}
     * @param dropRecallEnabled       a player may always re-collect an item entity they dropped or
     *                                died with; default {@code true}
     * @param gateDispensers          default {@code true}
     * @param gateNestedBundles       default {@code true}
     * @param feedbackCooldownSeconds throttle between action-bar warnings; default {@code 3}
     * @param rejectionMessage        MiniMessage with {@code {ITEM}} and {@code {REQUIREMENT}}
     * @param gatedItems              tiers in document order. A {@code List}, not a map: order is
     *                                the tie-break input Task 4.2.1 needs for its
     *                                most-restrictive-wins precedence rule, and no
     *                                {@code java.util.HashMap} holds cross-thread state
     */
    public record ItemProgression(
            boolean enabled,
            boolean dropRecallEnabled,
            boolean gateDispensers,
            boolean gateNestedBundles,
            int feedbackCooldownSeconds,
            String rejectionMessage,
            List<ItemTier> gatedItems) {
        public ItemProgression {
            Objects.requireNonNull(rejectionMessage, "rejectionMessage");
            gatedItems = List.copyOf(gatedItems);
        }

        /** The tier with this configuration id, if declared. */
        public Optional<ItemTier> tier(String id) {
            for (ItemTier tier : gatedItems) {
                if (tier.id().equals(id)) {
                    return Optional.of(tier);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * One gated item tier, exactly as configured. Patterns are raw: {@code PREFIX} ({@code IRON_*}),
     * {@code SUFFIX} ({@code *_IRON_ORE}) and {@code CONTAINS} ({@code *_IRON_*}) forms all appear
     * in the shipped file, and compiling them into material sets belongs to Task 4.2.1 (#11).
     *
     * @param id                    the configuration key, for example {@code iron-tier}
     * @param matchPatterns         wildcard material patterns; default empty
     * @param excludeMaterials      material names removed after pattern matching; default empty
     * @param items                 explicitly named materials; default empty
     * @param requireAdvancements   advancement keys required to hold the tier; default empty
     * @param requirePlaytimeHours  default {@code 0.0}
     * @param requireAccountAgeDays default {@code 0}
     * @param hint                  player-facing text explaining how to unlock; default empty
     */
    public record ItemTier(
            String id,
            List<String> matchPatterns,
            List<String> excludeMaterials,
            List<String> items,
            List<String> requireAdvancements,
            double requirePlaytimeHours,
            int requireAccountAgeDays,
            String hint) {
        public ItemTier {
            Objects.requireNonNull(id, "id");
            matchPatterns = List.copyOf(matchPatterns);
            excludeMaterials = List.copyOf(excludeMaterials);
            items = List.copyOf(items);
            requireAdvancements = List.copyOf(requireAdvancements);
            Objects.requireNonNull(hint, "hint");
        }
    }

    /**
     * Section 3.
     *
     * @param enabled                          default {@code true}
     * @param gateNaturalTrimChests            default {@code false}, so dungeon-chest templates stay lootable
     * @param blockUnearnedTemplateDuplication default {@code true}
     * @param blockUnearnedSmithing            default {@code true}
     * @param blockWearingUnearnedTrims        default {@code true}
     */
    public record TrimProgression(
            boolean enabled,
            boolean gateNaturalTrimChests,
            boolean blockUnearnedTemplateDuplication,
            boolean blockUnearnedSmithing,
            boolean blockWearingUnearnedTrims) {
    }

    /**
     * Section 4.
     *
     * @param enabled                default {@code true}
     * @param standStillSeconds      default {@code 15}
     * @param cooldownMinutes        default {@code 10}
     * @param displayDurationSeconds default {@code 5}
     * @param displayType            default {@link DisplayType#ACTIONBAR}
     * @param message                MiniMessage with {@code {NEXT_STEP}}
     */
    public record IdleReminder(
            boolean enabled,
            int standStillSeconds,
            int cooldownMinutes,
            int displayDurationSeconds,
            DisplayType displayType,
            String message) {
        public IdleReminder {
            Objects.requireNonNull(displayType, "displayType");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Section 5.
     *
     * @param giveOnFirstJoin default {@code true}
     * @param title           default {@code "<gold>Ninja6 Survival Guide"}
     * @param author          default {@code "Ninja6-MC"}
     */
    public record JourneyBook(boolean giveOnFirstJoin, String title, String author) {
        public JourneyBook {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(author, "author");
        }
    }

    /**
     * Section 6.
     *
     * @param enabled                      default {@code true}
     * @param scaleResummonedDragons       default {@code true}
     * @param battlePrepSeconds            reinforcement window on End entry or resummon; default {@code 30}
     * @param multiDragon                  multi-dragon scaling sub-section
     * @param exitPortalEgg                exit-portal egg sub-section
     * @param skullDropChance              default {@code 0.05}
     * @param exitPortalLockDuringBattle   default {@code true}; a no-op during a world's first
     *                                     fight, when the exit portal does not exist yet
     * @param exitPortalLockReleaseMinutes escape valve after no dragon damage; default {@code 10},
     *                                     {@code 0} disables the release
     */
    public record BossScaling(
            boolean enabled,
            boolean scaleResummonedDragons,
            int battlePrepSeconds,
            MultiDragon multiDragon,
            ExitPortalEgg exitPortalEgg,
            double skullDropChance,
            boolean exitPortalLockDuringBattle,
            int exitPortalLockReleaseMinutes) {
        public BossScaling {
            Objects.requireNonNull(multiDragon, "multiDragon");
            Objects.requireNonNull(exitPortalEgg, "exitPortalEgg");
        }
    }

    /**
     * @param enabled      default {@code true}
     * @param multiplier   scaling factor, must be greater than {@code 0.0}; default {@code 0.5}
     * @param roundingMode default {@link RoundingMode#HALF_UP}
     * @param maxDragons   default {@code 5}
     * @param balancedXp   first dragon full XP, secondary dragons 1,000 XP; default {@code true}
     */
    public record MultiDragon(
            boolean enabled,
            double multiplier,
            RoundingMode roundingMode,
            int maxDragons,
            boolean balancedXp) {
        public MultiDragon {
            Objects.requireNonNull(roundingMode, "roundingMode");
        }
    }

    /**
     * @param enabled       default {@code true}
     * @param placementMode default {@link PlacementMode#TOP_PILLAR}
     */
    public record ExitPortalEgg(boolean enabled, PlacementMode placementMode) {
        public ExitPortalEgg {
            Objects.requireNonNull(placementMode, "placementMode");
        }
    }

    /**
     * Section 7.
     *
     * @param enabled                     default {@code true}
     * @param blockBedAnchorBossDamage    cancels {@code BAD_RESPAWN_POINT} against bosses; default {@code true}
     * @param maxSingleHitBossDamage      time-to-kill budget, not just an anti-one-shot guard;
     *                                    default {@code 12.0}
     * @param blockEarlyEyeThrowing       default {@code true}
     * @param blockExitPortalCrystalPlace default {@code true}
     * @param blockGatewayPreDragon       default {@code true}
     * @param outerEndRadius              enforced until the world's first dragon dies; default {@code 500}
     * @param outerEndPollSeconds         bounds poll interval; default {@code 2}
     */
    public record AntiCheese(
            boolean enabled,
            boolean blockBedAnchorBossDamage,
            double maxSingleHitBossDamage,
            boolean blockEarlyEyeThrowing,
            boolean blockExitPortalCrystalPlace,
            boolean blockGatewayPreDragon,
            int outerEndRadius,
            int outerEndPollSeconds) {
    }

    /**
     * Section 8.
     *
     * @param gateMendingTrade    default {@code false}
     * @param requiredAdvancement default {@code "minecraft:story/cure_zombie_villager"}
     */
    public record VillagerProgression(boolean gateMendingTrade, String requiredAdvancement) {
        public VillagerProgression {
            Objects.requireNonNull(requiredAdvancement, "requiredAdvancement");
        }
    }
}
