package com.ninja6.antispeedrun.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How a player is told what a missing credited advancement asks of them (#216).
 *
 * <p>While {@code item-progression.require-personal-credit} is on, the six keys in
 * {@link PossessionAdvancements#CREDITED} are earned by doing something, not by holding an item, so
 * naming the vanilla advancement would point the player at the wrong thing: its toast fires on a
 * gift that no longer counts. Every generated player-facing line that lists what a gate is waiting
 * on — {@code /progress}, the idle reminder, the Journey Guide Book and an item refusal with no
 * hint — names the action through this class instead. With the toggle off every key is named as
 * before, because the vanilla advancement is then what the gate reads.
 *
 * <p>The structure-loot alternative is named only while {@code count-structure-loot} is on, and
 * always with the condition that makes it count: the chest has to be one the player opens first.
 */
public final class CreditedActions {

    /** The Journey Guide Book's note on loot, shown while loot can stand in for mining. */
    public static final String LOOT_NOTE = "Loot counts only from a chest you open first. "
            + "If a friend opens it, it counts for nobody.";

    private CreditedActions() {
    }

    /**
     * The personal action that earns {@code key}, lower-case so it reads inside a sentence.
     *
     * @return empty when credits are off or {@code key} is not credited, in which case the caller
     *         names the advancement as it always has
     */
    public static Optional<String> action(String key, PluginConfig.ItemProgression items) {
        Objects.requireNonNull(items, "items");
        if (key == null || !items.requirePersonalCredit()) {
            return Optional.empty();
        }
        boolean loot = items.countStructureLoot();
        return Optional.ofNullable(switch (key) {
            case "minecraft:story/mine_stone" -> "mine natural stone with a pickaxe";
            case "minecraft:story/upgrade_tools" -> "craft a stone pickaxe yourself";
            case "minecraft:story/smelt_iron" -> loot
                    ? "mine iron ore (or loot iron from a chest you open first) and smelt iron in a "
                            + "furnace you loaded yourself"
                    : "mine iron ore and smelt iron in a furnace you loaded yourself";
            case "minecraft:story/iron_tools" -> "craft an iron pickaxe yourself";
            case "minecraft:story/mine_diamond" -> loot
                    ? "mine diamond ore (or loot a diamond from a chest you open first)"
                    : "mine diamond ore";
            case "minecraft:nether/obtain_blaze_rod" -> "kill a blaze yourself";
            default -> null;
        });
    }

    /** {@link #action}, with its first letter capitalised, for a line of its own. */
    public static Optional<String> sentence(String key, PluginConfig.ItemProgression items) {
        return action(key, items).map(text -> Character.toUpperCase(text.charAt(0)) + text.substring(1));
    }

    /** Whether the Journey Guide Book should carry {@link #LOOT_NOTE}. */
    public static boolean lootCounts(PluginConfig.ItemProgression items) {
        return items.requirePersonalCredit() && items.countStructureLoot();
    }

    /**
     * Missing advancement keys split into the actions that earn the credited ones, in the order
     * given, and the keys left to be named as advancements.
     */
    public static Split split(List<String> missing, PluginConfig.ItemProgression items) {
        List<String> actions = new ArrayList<>();
        List<String> advancements = new ArrayList<>();
        for (String key : missing) {
            action(key, items).ifPresentOrElse(actions::add, () -> advancements.add(key));
        }
        return new Split(List.copyOf(actions), List.copyOf(advancements));
    }

    /**
     * @param actions      one phrase per credited key, from {@link #action}
     * @param advancements the uncredited keys, unchanged
     */
    public record Split(List<String> actions, List<String> advancements) {
    }
}
