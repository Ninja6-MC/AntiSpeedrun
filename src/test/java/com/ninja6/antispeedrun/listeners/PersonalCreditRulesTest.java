package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.storage.PersonalCredit;
import com.ninja6.antispeedrun.storage.PlacedBlockRegistry;

/** Each credit rule of the Amendment in {@code docs/provenance-model.md} (#213). */
class PersonalCreditRulesTest {

    @Test
    @DisplayName("mine_stone: the stone family and cobbled forms, natural, with a pickaxe that drops")
    void mineStone() {
        for (Material stone : List.of(Material.STONE, Material.COBBLESTONE, Material.DEEPSLATE,
                Material.COBBLED_DEEPSLATE, Material.BLACKSTONE)) {
            assertEquals(Optional.of(PersonalCredit.MINE_STONE), PersonalCreditRules.minable(stone),
                    stone.name());
        }
        PersonalCredit credit = PersonalCredit.MINE_STONE;
        assertTrue(PersonalCreditRules.minedEarns(credit, false, true, true));
        assertFalse(PersonalCreditRules.minedEarns(credit, true, true, true), "placed");
        assertFalse(PersonalCreditRules.minedEarns(credit, false, false, true), "no pickaxe");
        assertFalse(PersonalCreditRules.minedEarns(credit, false, true, false), "no drops");
        assertTrue(PersonalCreditRules.needsPickaxeDrop(credit));
    }

    @Test
    @DisplayName("blocks outside the definitions earn nothing when mined")
    void otherBlocks() {
        for (Material other : List.of(Material.GRANITE, Material.ANDESITE, Material.TUFF,
                Material.STONE_BRICKS, Material.POLISHED_BLACKSTONE, Material.IRON_BLOCK,
                Material.RAW_IRON_BLOCK, Material.DIAMOND_BLOCK, Material.NETHERRACK,
                Material.COAL_ORE, Material.DIRT)) {
            assertEquals(Optional.empty(), PersonalCreditRules.minable(other), other.name());
        }
    }

    @Test
    @DisplayName("every minable block is one the placed-block registry tracks")
    void minableIsTracked() {
        for (Material material : Material.values()) {
            if (material.isLegacy()) {
                continue;
            }
            if (PersonalCreditRules.minable(material).isPresent()) {
                assertTrue(PlacedBlockRegistry.isCreditRelevant(material),
                        material.name());
            }
        }
    }

    @Test
    @DisplayName("mined iron: iron ore, natural, with a pickaxe that drops it")
    void minedIron() {
        assertEquals(Optional.of(PersonalCredit.MINED_IRON), PersonalCreditRules.minable(Material.IRON_ORE));
        assertEquals(Optional.of(PersonalCredit.MINED_IRON),
                PersonalCreditRules.minable(Material.DEEPSLATE_IRON_ORE));
        PersonalCredit credit = PersonalCredit.MINED_IRON;
        assertTrue(PersonalCreditRules.minedEarns(credit, false, true, true));
        assertFalse(PersonalCreditRules.minedEarns(credit, true, true, true), "placed");
        assertFalse(PersonalCreditRules.minedEarns(credit, false, false, false), "no pickaxe");
        assertFalse(PersonalCreditRules.minedEarns(credit, false, true, false), "wooden pickaxe drops nothing");
    }

    @Test
    @DisplayName("mine_diamond: diamond ore that is not in the registry")
    void mineDiamond() {
        assertEquals(Optional.of(PersonalCredit.MINE_DIAMOND),
                PersonalCreditRules.minable(Material.DIAMOND_ORE));
        assertEquals(Optional.of(PersonalCredit.MINE_DIAMOND),
                PersonalCreditRules.minable(Material.DEEPSLATE_DIAMOND_ORE));
        PersonalCredit credit = PersonalCredit.MINE_DIAMOND;
        assertTrue(PersonalCreditRules.minedEarns(credit, false, true, true));
        assertTrue(PersonalCreditRules.minedEarns(credit, false, false, false));
        assertFalse(PersonalCreditRules.minedEarns(credit, true, true, true), "placed");
        assertFalse(PersonalCreditRules.needsPickaxeDrop(credit));
    }

    @Test
    @DisplayName("loot earns mined iron for raw iron or an ingot and mine_diamond for a diamond, nothing else")
    void loot() {
        assertEquals(Set.of(PersonalCredit.MINED_IRON), PersonalCreditRules.looted(List.of(Material.RAW_IRON)));
        assertEquals(Set.of(PersonalCredit.MINED_IRON), PersonalCreditRules.looted(List.of(Material.IRON_INGOT)));
        assertEquals(Set.of(PersonalCredit.MINE_DIAMOND), PersonalCreditRules.looted(List.of(Material.DIAMOND)));
        assertEquals(Set.of(PersonalCredit.MINED_IRON, PersonalCredit.MINE_DIAMOND),
                PersonalCreditRules.looted(List.of(Material.BREAD, Material.DIAMOND, Material.RAW_IRON)));
        assertEquals(Set.of(), PersonalCreditRules.looted(List.of(
                Material.STONE, Material.COBBLESTONE, Material.IRON_PICKAXE, Material.STONE_PICKAXE,
                Material.IRON_NUGGET, Material.IRON_BLOCK, Material.DIAMOND_BLOCK, Material.BLAZE_ROD,
                Material.DIAMOND_ORE, Material.IRON_ORE)));
        assertEquals(Set.of(), PersonalCreditRules.looted(List.of()));
    }

    @Test
    @DisplayName("only a vault counts among blocks that dispense loot")
    void vaultOnly() {
        assertTrue(PersonalCreditRules.lootingBlock(Material.VAULT));
        assertFalse(PersonalCreditRules.lootingBlock(Material.TRIAL_SPAWNER));
        assertFalse(PersonalCreditRules.lootingBlock(Material.DISPENSER));
    }

    @Test
    @DisplayName("iron_tools and upgrade_tools: the pickaxe crafted in a crafting grid, never a Crafter")
    void crafting() {
        assertEquals(Optional.of(PersonalCredit.IRON_TOOLS),
                PersonalCreditRules.crafted(Material.IRON_PICKAXE, true));
        assertEquals(Optional.of(PersonalCredit.UPGRADE_TOOLS),
                PersonalCreditRules.crafted(Material.STONE_PICKAXE, true));
        for (Material other : List.of(Material.IRON_AXE, Material.IRON_INGOT, Material.STONE_AXE,
                Material.WOODEN_PICKAXE, Material.DIAMOND_PICKAXE, Material.IRON_BLOCK)) {
            assertEquals(Optional.empty(), PersonalCreditRules.crafted(other, true), other.name());
        }
        assertEquals(Optional.empty(), PersonalCreditRules.crafted(Material.IRON_PICKAXE, false), "Crafter");
        assertEquals(Optional.empty(), PersonalCreditRules.crafted(Material.STONE_PICKAXE, false), "Crafter");
    }

    @Test
    @DisplayName("obtain_blaze_rod: the killer of a blaze, of nothing else")
    void blaze() {
        assertTrue(PersonalCreditRules.blazeKill(EntityType.BLAZE));
        assertFalse(PersonalCreditRules.blazeKill(EntityType.WITHER_SKELETON));
        assertFalse(PersonalCreditRules.blazeKill(EntityType.MAGMA_CUBE));
    }

    @Test
    @DisplayName("only real players are credited; NPC-marked players and other entities are not")
    void realPlayersOnly() {
        assertTrue(PersonalCreditRules.creditable(true, false));
        assertFalse(PersonalCreditRules.creditable(true, true));
        assertFalse(PersonalCreditRules.creditable(false, false));
    }

    @Test
    @DisplayName("the break verdict reaches MONITOR once, per event, and is never left behind")
    void breakVerdicts() {
        BreakVerdicts verdicts = new BreakVerdicts();
        Object outer = new Object();
        Object nested = new Object();
        verdicts.put(outer, false);
        verdicts.put(nested, true);
        assertEquals(Boolean.TRUE, verdicts.take(nested));
        assertEquals(Boolean.FALSE, verdicts.take(outer));
        assertNull(verdicts.take(outer), "taken once");
        assertNull(verdicts.take(new Object()), "never held");
        assertEquals(0, verdicts.heldOnThisThread());
    }

    @Test
    @DisplayName("break verdicts on one thread are invisible to another")
    void breakVerdictsPerThread() throws InterruptedException {
        BreakVerdicts verdicts = new BreakVerdicts();
        Object event = new Object();
        verdicts.put(event, false);
        Boolean[] seen = {Boolean.TRUE};
        Thread other = new Thread(() -> seen[0] = verdicts.take(event));
        other.start();
        other.join();
        assertNull(seen[0]);
        assertEquals(Boolean.FALSE, verdicts.take(event));
    }
}
