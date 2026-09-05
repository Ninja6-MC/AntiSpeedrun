package com.ninja6.antispeedrun.progression;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PluginConfig.ItemTier;

/**
 * A named thing a player can unlock: a dimension gate, or an item tier.
 *
 * <p>Requirement evaluation does not care which of those it is looking at, but unlock announcements
 * do — a player needs to be told <em>"The Nether is now open"</em>, not the id of a configuration
 * key. This record pairs the two.
 *
 * @param id          stable identifier, unique across every milestone the plugin tracks. Used as
 *                    the key for "has this player already been congratulated", so it must not
 *                    change between reloads for the same underlying gate
 * @param displayName player-facing name, substituted into the unlock announcement
 * @param requirement what must be satisfied
 */
public record Milestone(String id, String displayName, MilestoneRequirement requirement) {

    /** Identifier of the Nether dimension gate. */
    public static final String NETHER_ID = "dimension:nether";

    /** Identifier of the End dimension gate. */
    public static final String END_ID = "dimension:the_end";

    public Milestone {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(requirement, "requirement");
    }

    /**
     * The dimension gates that are switched on in {@code config}, in fixed order.
     *
     * <p>A disabled gate is omitted rather than included with empty requirements: a gate that is
     * off has not been "unlocked", and announcing that it has would be nonsense.
     */
    public static List<Milestone> dimensionGates(PluginConfig config) {
        Objects.requireNonNull(config, "config");
        List<Milestone> milestones = new ArrayList<>(2);
        if (config.dimensionGates().nether().enabled()) {
            milestones.add(new Milestone(NETHER_ID, "The Nether",
                    MilestoneRequirement.of(config.dimensionGates().nether())));
        }
        if (config.dimensionGates().theEnd().enabled()) {
            milestones.add(new Milestone(END_ID, "The End",
                    MilestoneRequirement.of(config.dimensionGates().theEnd())));
        }
        return List.copyOf(milestones);
    }

    /**
     * Every advancement key any part of {@code config} can require, in document order.
     *
     * <p>This is the set a snapshot capture queries. Gathering it from the whole configuration
     * rather than from the milestone being evaluated is what lets one capture answer every
     * question asked of a player for the next minute, including questions from the item-gating
     * workstream that this class knows nothing about.
     */
    public static Set<String> allRequiredAdvancements(PluginConfig config) {
        Objects.requireNonNull(config, "config");
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(config.dimensionGates().nether().requireAdvancements());
        keys.addAll(config.dimensionGates().theEnd().requireAdvancements());
        for (ItemTier tier : config.itemProgression().gatedItems()) {
            keys.addAll(tier.requireAdvancements());
        }
        // Section 8 gates one villager trade on a single advancement; it is read on the same hot
        // path as everything else, so it belongs in the same capture -- but only when the gate is
        // actually on. See villagerTradeAdvancement.
        villagerTradeAdvancement(config).ifPresent(keys::add);
        return Set.copyOf(keys);
    }

    /**
     * The advancement the mending-trade gate requires, if it requires one at all.
     *
     * <p>The single place two configuration facts are turned into an answer, so that the capture
     * path and the villager gate itself cannot disagree about them:
     *
     * <ul>
     *   <li><strong>The gate is off.</strong> {@code gate-mending-trade} defaults to {@code false},
     *       and with it off {@code required-advancement} describes a feature nobody asked for.
     *       Including it in {@link #allRequiredAdvancements} made every capture on every server pay
     *       a {@code NamespacedKey} resolution and a {@code Bukkit.getAdvancement} for it, and made
     *       a server whose key was wrong log an unresolvable-key warning about a feature it had
     *       switched off. Empty.</li>
     *   <li><strong>The gate is on but the key is blank.</strong> {@code ConfigReader.string}
     *       returns {@code ""} without complaint, so an operator who empties the key reaches here,
     *       and {@code NamespacedKey.fromString("")} returns {@code null} — which
     *       {@link BukkitAdvancementLookup} would report as a malformed key. That warning would be
     *       right about the syntax and wrong about the intent: a key cleared to nothing reads as
     *       "gate the trade, but do not require an advancement for it", not as a typo. It is
     *       therefore empty here too, and the gate imposes no advancement requirement — the same
     *       fail-open direction every other unevaluable requirement in this package takes.</li>
     * </ul>
     *
     * @return the configured key, or empty when no advancement is required of the trade gate
     */
    public static Optional<String> villagerTradeAdvancement(PluginConfig config) {
        Objects.requireNonNull(config, "config");
        if (!config.villagerProgression().gateMendingTrade()) {
            return Optional.empty();
        }
        String key = config.villagerProgression().requiredAdvancement();
        return key.isBlank() ? Optional.empty() : Optional.of(key);
    }
}
