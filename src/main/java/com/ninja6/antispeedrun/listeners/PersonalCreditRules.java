package com.ninja6.antispeedrun.listeners;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import com.ninja6.antispeedrun.storage.PersonalCredit;

/**
 * Which personal credits an action earns (#213), apart from Bukkit's events so each rule is
 * testable. The definitions are the Amendment of {@code docs/provenance-model.md}; this class is
 * them, one method per row.
 */
public final class PersonalCreditRules {

    /**
     * The stone family of {@code story/mine_stone}: stone, deepslate, blackstone and the cobbled
     * forms. Every one is also in the placed-block registry's credit-relevant set.
     */
    private static final Set<Material> STONE = EnumSet.of(
            Material.STONE, Material.COBBLESTONE,
            Material.DEEPSLATE, Material.COBBLED_DEEPSLATE,
            Material.BLACKSTONE);

    private static final Set<Material> IRON_ORE = EnumSet.of(
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE);

    private static final Set<Material> DIAMOND_ORE = EnumSet.of(
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE);

    private PersonalCreditRules() {
    }

    /** The credit breaking a block of {@code material} can earn, if any. */
    public static Optional<PersonalCredit> minable(Material material) {
        if (STONE.contains(material)) {
            return Optional.of(PersonalCredit.MINE_STONE);
        }
        if (IRON_ORE.contains(material)) {
            return Optional.of(PersonalCredit.MINED_IRON);
        }
        if (DIAMOND_ORE.contains(material)) {
            return Optional.of(PersonalCredit.MINE_DIAMOND);
        }
        return Optional.empty();
    }

    /**
     * Whether a break earns {@code credit}, which {@link #minable} named for the block.
     *
     * <p>A block in the placed-block registry never earns. Stone and iron ore also need a pickaxe
     * and a break that drops items. Diamond ore needs a break that would yield a diamond, as vanilla
     * grants {@code story/mine_diamond} on obtaining one; any tool that yields it will do.
     *
     * @param placed  whether the registry held the block when it was broken
     * @param pickaxe whether the breaking tool is a pickaxe
     * @param drops   whether the break drops what {@link #dropsEarn} asks of {@code credit}
     */
    public static boolean minedEarns(PersonalCredit credit, boolean placed, boolean pickaxe, boolean drops) {
        if (placed) {
            return false;
        }
        return switch (credit) {
            case MINE_STONE, MINED_IRON -> pickaxe && drops;
            case MINE_DIAMOND -> drops;
            default -> false;
        };
    }

    /**
     * Whether the items a break would drop are the ones {@code credit} asks for: a diamond for
     * {@code mine_diamond}, so silk touch (the ore block), a stone or wooden pickaxe (nothing) and a
     * break with drops disabled earn nothing; anything at all for stone and iron ore.
     *
     * @param drops the item types the block drops for the breaking tool; empty for a break whose
     *              drops are disabled or that drops nothing
     */
    public static boolean dropsEarn(PersonalCredit credit, Collection<Material> drops) {
        if (credit == PersonalCredit.MINE_DIAMOND) {
            return drops.contains(Material.DIAMOND);
        }
        return !drops.isEmpty();
    }

    /**
     * The credits generated loot holding {@code items} earns, stored with source {@code loot}.
     * Only finding iron (raw iron or an ingot) and diamonds has a loot path; looted stone or a
     * looted pickaxe earns nothing.
     */
    public static Set<PersonalCredit> looted(Iterable<Material> items) {
        EnumSet<PersonalCredit> earned = EnumSet.noneOf(PersonalCredit.class);
        for (Material item : items) {
            if (item == Material.RAW_IRON || item == Material.IRON_INGOT) {
                earned.add(PersonalCredit.MINED_IRON);
            } else if (item == Material.DIAMOND) {
                earned.add(PersonalCredit.MINE_DIAMOND);
            }
        }
        return earned;
    }

    /** Whether a vault dispensed the loot, the only dispensing block that counts. */
    public static boolean lootingBlock(Material block) {
        return block == Material.VAULT;
    }

    /**
     * The credit crafting {@code result} earns, if any. Only a crafting grid counts (the player's
     * own 2x2 or a crafting table); a Crafter block never does.
     *
     * @param craftingGrid whether the result was taken from a crafting grid
     */
    public static Optional<PersonalCredit> crafted(Material result, boolean craftingGrid) {
        if (!craftingGrid) {
            return Optional.empty();
        }
        if (result == Material.IRON_PICKAXE) {
            return Optional.of(PersonalCredit.IRON_TOOLS);
        }
        if (result == Material.STONE_PICKAXE) {
            return Optional.of(PersonalCredit.UPGRADE_TOOLS);
        }
        return Optional.empty();
    }

    /** Whether killing an entity of {@code type} earns {@code obtain_blaze_rod}. */
    public static boolean blazeKill(EntityType type) {
        return type == EntityType.BLAZE;
    }

    /**
     * Whether an actor may be credited at all: a real player, not an entity an NPC plugin marks
     * with {@code NPC} metadata.
     */
    public static boolean creditable(boolean player, boolean npc) {
        return player && !npc;
    }
}
