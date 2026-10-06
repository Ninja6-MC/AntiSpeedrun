package com.ninja6.antispeedrun.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The vanilla advancements a player completes merely by holding an item, and which of them
 * {@code item-progression.require-personal-credit} answers from personal credits instead (#210).
 *
 * <p>A gate on one of these opens for whoever is handed the item. The six in {@link #CREDITED} are
 * answered from what the player did themselves (the Amendment of {@code docs/provenance-model.md});
 * every other key in {@link #POSSESSION_TRIGGERED} is not, and a gate an operator puts on one is
 * reported when the configuration is compiled, because nothing at runtime can say the gate leaks.
 *
 * <p>Stated here rather than derived from {@code storage.PersonalCredit} because {@code config}
 * depends on nothing but {@code logging}. {@code PossessionAdvancementsTest} asserts that
 * {@link #CREDITED} is exactly the set of advancements the credits stand in for.
 */
public final class PossessionAdvancements {

    /**
     * Every vanilla advancement whose criteria are all {@code minecraft:inventory_changed}, read
     * from the 1.21.4 and 1.21.11 server data, which agree. {@code adventure/salvage_sherd} also
     * has an {@code inventory_changed} criterion but requires brushing as well, so it is absent.
     */
    public static final Set<String> POSSESSION_TRIGGERED = Set.of(
            "minecraft:story/root",
            "minecraft:story/mine_stone",
            "minecraft:story/upgrade_tools",
            "minecraft:story/smelt_iron",
            "minecraft:story/obtain_armor",
            "minecraft:story/lava_bucket",
            "minecraft:story/iron_tools",
            "minecraft:story/form_obsidian",
            "minecraft:story/mine_diamond",
            "minecraft:story/shiny_gear",
            "minecraft:nether/obtain_ancient_debris",
            "minecraft:nether/obtain_crying_obsidian",
            "minecraft:nether/obtain_blaze_rod",
            "minecraft:nether/get_wither_skull",
            "minecraft:nether/netherite_armor",
            "minecraft:end/dragon_egg",
            "minecraft:end/elytra",
            "minecraft:end/dragon_breath",
            "minecraft:husbandry/obtain_netherite_hoe",
            "minecraft:husbandry/obtain_sniffer_egg",
            "minecraft:husbandry/froglights");

    /** The possession-triggered advancements a personal credit answers. */
    public static final Set<String> CREDITED = Set.of(
            "minecraft:story/mine_stone",
            "minecraft:story/upgrade_tools",
            "minecraft:story/smelt_iron",
            "minecraft:story/iron_tools",
            "minecraft:story/mine_diamond",
            "minecraft:nether/obtain_blaze_rod");

    private PossessionAdvancements() {
    }

    /** Whether a gate on {@code key} opens for a player handed the item, with credits in force. */
    public static boolean uncredited(String key) {
        return POSSESSION_TRIGGERED.contains(key) && !CREDITED.contains(key);
    }

    /**
     * One warning per gate that is switched on and requires a possession-triggered advancement no
     * credit answers. Empty while {@code require-personal-credit} is off: every gate then reads
     * vanilla advancements as it always has, and saying six of them leak and the rest do not would
     * be wrong.
     */
    static List<String> uncreditedWarnings(PluginConfig.DimensionGates dimensions,
                                           PluginConfig.ItemProgression items,
                                           PluginConfig.VillagerProgression villagers) {
        List<String> warnings = new ArrayList<>();
        if (!items.requirePersonalCredit()) {
            return warnings;
        }
        if (dimensions.nether().enabled()) {
            check("dimension-gates.nether.require-advancements",
                    dimensions.nether().requireAdvancements(), warnings);
        }
        if (dimensions.theEnd().enabled()) {
            check("dimension-gates.the_end.require-advancements",
                    dimensions.theEnd().requireAdvancements(), warnings);
        }
        if (items.enabled()) {
            for (PluginConfig.ItemTier tier : items.gatedItems()) {
                check("item-progression.gated-items." + tier.id() + ".require-advancements",
                        tier.requireAdvancements(), warnings);
            }
        }
        if (villagers.gateMendingTrade()) {
            check("villager-progression.required-advancement",
                    List.of(villagers.requiredAdvancement()), warnings);
        }
        return warnings;
    }

    private static void check(String path, List<String> keys, List<String> warnings) {
        for (String key : keys) {
            if (uncredited(key)) {
                warnings.add(path + ": \"" + key + "\" is completed by vanilla as soon as the player "
                        + "holds the item, however it arrived, and no personal credit answers it, so "
                        + "a gift or a chest withdrawal still satisfies this gate. Credits answer "
                        + String.join(", ", CREDITED.stream().sorted().toList())
                        + "; see docs/provenance-model.md.");
            }
        }
    }
}
