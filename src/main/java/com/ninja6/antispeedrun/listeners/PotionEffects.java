package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import com.ninja6.antispeedrun.config.BrewingEffects;

/** Base and custom effects are evaluated together, with stable effect keys. */
final class PotionEffects {
    private PotionEffects() { }
    static List<PotionEffect> effects(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof PotionMeta meta)) return List.of();
        List<PotionEffect> effects = new ArrayList<>();
        if (meta.getBasePotionType() != null) effects.addAll(meta.getBasePotionType().getPotionEffects());
        for (PotionEffect custom : meta.getCustomEffects()) {
            effects.removeIf(base -> base.getType().equals(custom.getType()));
            effects.add(custom);
        }
        return effects;
    }
    static Set<String> ids(ItemStack item) {
        Set<String> ids = new HashSet<>();
        for (PotionEffect effect : effects(item)) {
            String id = BrewingEffects.canonical(effect.getType().getKey().getKey());
            if (id != null) ids.add(id);
        }
        return ids;
    }
    static boolean ingredient(Material type) {
        return switch (type) {
            case NETHER_WART, REDSTONE, GLOWSTONE_DUST, FERMENTED_SPIDER_EYE, GUNPOWDER,
                 DRAGON_BREATH, SUGAR, RABBIT_FOOT, GLISTERING_MELON_SLICE, SPIDER_EYE,
                 PUFFERFISH, MAGMA_CREAM, GOLDEN_CARROT, TURTLE_HELMET, PHANTOM_MEMBRANE,
                 STONE, SLIME_BLOCK, COBWEB, BREEZE_ROD -> true;
            default -> false;
        };
    }
}
