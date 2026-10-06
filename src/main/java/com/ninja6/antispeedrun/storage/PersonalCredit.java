package com.ninja6.antispeedrun.storage;

import java.util.Optional;

/**
 * A personal-action credit: something the player did themselves, recorded so that a gate on a
 * possession-triggered advancement can ask whether the player did it rather than whether they hold
 * the item (#210). The definitions are the Amendment of {@code docs/provenance-model.md}.
 *
 * <p>{@code story/smelt_iron} is two credits here, {@link #MINED_IRON} and {@link #SMELTED_IRON},
 * because the Amendment defines it as two sub-credits earned separately and in either order. Every
 * other credit maps to exactly one advancement.
 */
public enum PersonalCredit {

    /** {@code story/mine_stone}: a natural stone-family block mined with a pickaxe that drops it. */
    MINE_STONE("mine-stone", "minecraft:story/mine_stone", false),

    /** The mined-iron sub-credit of {@code story/smelt_iron}; loot can earn it. */
    MINED_IRON("mined-iron", "minecraft:story/smelt_iron", true),

    /** The smelted-iron sub-credit of {@code story/smelt_iron}; only a hand-loaded furnace earns it. */
    SMELTED_IRON("smelted-iron", "minecraft:story/smelt_iron", false),

    /** {@code story/iron_tools}: an iron pickaxe crafted in a crafting grid. */
    IRON_TOOLS("iron-tools", "minecraft:story/iron_tools", false),

    /** {@code story/upgrade_tools}: a stone pickaxe crafted in a crafting grid. */
    UPGRADE_TOOLS("upgrade-tools", "minecraft:story/upgrade_tools", false),

    /** {@code story/mine_diamond}: natural diamond ore mined; loot can earn it. */
    MINE_DIAMOND("mine-diamond", "minecraft:story/mine_diamond", true),

    /** {@code nether/obtain_blaze_rod}: the player killed a blaze. */
    OBTAIN_BLAZE_ROD("obtain-blaze-rod", "minecraft:nether/obtain_blaze_rod", false);

    private final String id;
    private final String advancement;
    private final boolean lootable;

    PersonalCredit(String id, String advancement, boolean lootable) {
        this.id = id;
        this.advancement = advancement;
        this.lootable = lootable;
    }

    /** The name the credit is stored under. Never changes once shipped. */
    public String id() {
        return id;
    }

    /** The advancement key this credit stands in for. */
    public String advancement() {
        return advancement;
    }

    /**
     * Whether {@code item-progression.count-structure-loot} lets a {@link CreditSource#LOOT} record
     * earn this credit. Only finding iron and diamonds has a loot path.
     */
    public boolean lootable() {
        return lootable;
    }

    /** The credit stored as {@code id}, if any. */
    public static Optional<PersonalCredit> fromId(String id) {
        for (PersonalCredit credit : values()) {
            if (credit.id.equals(id)) {
                return Optional.of(credit);
            }
        }
        return Optional.empty();
    }
}
