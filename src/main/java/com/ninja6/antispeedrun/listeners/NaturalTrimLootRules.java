package com.ninja6.antispeedrun.listeners;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.Material;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.TrimProgressionManager;

/**
 * The decisions behind {@link NaturalTrimLootListener} (#198), kept free of events and item stacks
 * so they can be tested without a server.
 *
 * <p>A gated template is any material {@link TrimProgressionManager#forTemplate} maps to a
 * structure: Silence, Ward, Snout, Spire and the netherite upgrade template. Every other entry in
 * the loot is left alone.
 */
public final class NaturalTrimLootRules {

    /** Who the loot is being generated for, as far as the lock can tell. */
    public enum Looter {
        /** A player owned by the current region: evaluated live. */
        LIVE,
        /** A player owned by another region: judged from the explored structure record. */
        RECORDED,
        /** No player: a hopper, a hopper minecart, a non-player breaking the container. */
        NONE
    }

    private NaturalTrimLootRules() {
    }

    /** Whether the lock is on: section 3 active and {@code gate-natural-trim-chests} set. */
    public static boolean locked(PluginConfig config) {
        return TrimProgressionManager.isActive(config)
                && config.trimProgression().gateNaturalTrimChests();
    }

    /**
     * Whether the looter has earned a structure.
     *
     * <p>{@link Looter#NONE} earns nothing, the same rule as a Crafter with no recorded owner.
     *
     * @param player   the looting player, or {@code null} for {@link Looter#NONE}
     * @param live     the live evaluation, asked only for {@link Looter#LIVE}
     * @param explored the persisted record, {@code (player, milestone id) -> explored}, asked only
     *                 for {@link Looter#RECORDED}
     */
    public static Predicate<Milestone> earned(Looter looter, UUID player, Predicate<Milestone> live,
                                              BiPredicate<UUID, String> explored) {
        Objects.requireNonNull(looter, "looter");
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(explored, "explored");
        return switch (looter) {
            case LIVE -> live;
            case RECORDED -> milestone -> explored.test(Objects.requireNonNull(player, "player"), milestone.id());
            case NONE -> milestone -> false;
        };
    }

    /**
     * Removes from {@code loot} every gated template whose structure {@code earned} rejects.
     *
     * <p>{@code earned} is asked at most once per structure, so a chest holding Ward and Silence
     * evaluates the Ancient City once.
     *
     * @param loot   the generated loot, edited in place; {@code null} entries are kept
     * @param typeOf the material of one entry
     * @param earned whether the looter has explored the structure a milestone stands for
     * @return how many entries were removed
     */
    public static <T> int strip(List<T> loot, Function<? super T, Material> typeOf,
                                Predicate<Milestone> earned) {
        Objects.requireNonNull(loot, "loot");
        Objects.requireNonNull(typeOf, "typeOf");
        Objects.requireNonNull(earned, "earned");
        Map<String, Boolean> answers = new HashMap<>();
        int removed = 0;
        for (Iterator<T> it = loot.iterator(); it.hasNext(); ) {
            T entry = it.next();
            if (entry == null) {
                continue;
            }
            Material type = typeOf.apply(entry);
            // forTemplate, not TemplateDuplicationRules.gate: Material#isAir needs a running
            // server, and air maps to nothing here anyway.
            Optional<Milestone> gate = type == null ? Optional.empty() : TrimProgressionManager.forTemplate(type);
            if (gate.isEmpty()) {
                continue;
            }
            Milestone milestone = gate.get();
            if (!answers.computeIfAbsent(milestone.id(), id -> earned.test(milestone))) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }
}
