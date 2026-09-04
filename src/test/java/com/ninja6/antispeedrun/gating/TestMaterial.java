package com.ninja6.antispeedrun.gating;

/**
 * A stand-in for {@code org.bukkit.Material}, because {@code paper-api} is a {@code compileOnly}
 * dependency and is deliberately not on the test classpath — the same reason
 * {@code PluginConfig} parses from {@code ConfigSection} rather than from
 * {@code FileConfiguration}.
 *
 * <p>Every constant here is spelled exactly as Bukkit spells it in 1.21.4, and the set is chosen to
 * cover the cases the two issues turn on rather than to be exhaustive: the deepslate ore variants
 * that prefix matching misses, the 1.21 trial-chamber and Breeze items, the decorative iron the
 * exclusions exist for, {@code NETHERITE_UPGRADE_SMITHING_TEMPLATE} and the trim templates it
 * belongs with, and a handful of materials no tier should ever claim.
 *
 * <p>Ordinals are meaningless to the compiler — it walks the array it is handed and keys on
 * {@code name()} — so a real {@code Material} behaves identically to this fixture, only larger.
 */
enum TestMaterial {

    // Iron tier: patterns IRON_*, RAW_IRON*, CHAINMAIL_*, *_IRON_ORE
    IRON_INGOT,
    IRON_ORE,
    IRON_BLOCK,
    IRON_NUGGET,
    IRON_SWORD,
    IRON_PICKAXE,
    IRON_HELMET,
    IRON_CHESTPLATE,
    IRON_HORSE_ARMOR,
    RAW_IRON,
    RAW_IRON_BLOCK,
    DEEPSLATE_IRON_ORE,
    CHAINMAIL_HELMET,
    CHAINMAIL_CHESTPLATE,

    // Iron tier exclusions: decorative or utility iron that must stay ungated
    IRON_DOOR,
    IRON_TRAPDOOR,
    IRON_BARS,
    HEAVY_WEIGHTED_PRESSURE_PLATE,
    CHAIN,
    LANTERN,
    SOUL_LANTERN,

    // Iron tier, named outright
    SHIELD,
    BUCKET,
    SHEARS,
    FLINT_AND_STEEL,
    CROSSBOW,
    TRIAL_KEY,
    OMINOUS_TRIAL_KEY,
    OMINOUS_BOTTLE,

    // Diamond tier: DIAMOND_*, *_DIAMOND_ORE, plus DIAMOND itself
    DIAMOND,
    DIAMOND_ORE,
    DEEPSLATE_DIAMOND_ORE,
    DIAMOND_BLOCK,
    DIAMOND_SWORD,
    DIAMOND_PICKAXE,
    DIAMOND_HORSE_ARMOR,

    // Nether tier
    BLAZE_ROD,
    BLAZE_POWDER,
    NETHER_WART,
    BREWING_STAND,
    GHAST_TEAR,
    MAGMA_CREAM,
    WITHER_SKELETON_SKULL,
    BREEZE_ROD,
    WIND_CHARGE,

    // End tier
    ENDER_EYE,
    END_CRYSTAL,
    SHULKER_SHELL,
    SHULKER_BOX,
    ELYTRA,
    DRAGON_BREATH,
    DRAGON_HEAD,
    DRAGON_EGG,
    TOTEM_OF_UNDYING,

    // Netherite tier: NETHERITE_*, minus the smithing template, plus the 1.21 mace pair
    NETHERITE_INGOT,
    NETHERITE_SCRAP,
    NETHERITE_BLOCK,
    NETHERITE_SWORD,
    NETHERITE_PICKAXE,
    NETHERITE_UPGRADE_SMITHING_TEMPLATE,
    ANCIENT_DEBRIS,
    MACE,
    HEAVY_CORE,

    // Epic 5's surface: *_SMITHING_TEMPLATE must be expressible
    SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE,
    VEX_ARMOR_TRIM_SMITHING_TEMPLATE,
    WARD_ARMOR_TRIM_SMITHING_TEMPLATE,

    // Never gated by anything in the shipped file
    DIRT,
    STONE,
    COBBLESTONE,
    OAK_LOG,
    COAL_ORE,
    DEEPSLATE_COAL_ORE,
    GOLD_INGOT,
    WHEAT,
    APPLE,
    TORCH;

    /** Cached once, exactly as {@code MaterialGates} caches {@code Material.values()}. */
    static final TestMaterial[] UNIVERSE = values();
}
