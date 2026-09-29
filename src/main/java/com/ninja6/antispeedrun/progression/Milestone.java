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
        // Section 7's eye-throw rule, on the same terms: read on the interact path, so captured
        // with everything else, and only while the rule is on. See earlyEyeThrowAdvancement.
        earlyEyeThrowAdvancement(config).ifPresent(keys::add);
        return Set.copyOf(keys);
    }

    /**
     * The advancement an Eye of Ender throw waits for (#7): finding a Nether Fortress.
     *
     * <p>A constant rather than a configured key. Section 7 configures the rule as an on/off switch,
     * and #7 defines it by this one advancement, so an operator who wants a different requirement is
     * asking for a new key rather than a different value here — the same position
     * {@code MendingTradeRules.MENDING_KEY} takes. It is also a requirement strictly downstream of
     * Nether access and upstream of the blaze powder every eye is crafted from, so a player
     * following natural progression never meets the refusal.
     */
    public static final String EARLY_EYE_ADVANCEMENT = "minecraft:nether/find_fortress";

    /**
     * The advancement the eye-throw rule requires, if the rule is on at all.
     *
     * <p>Empty while {@code anti-cheese.enabled} or {@code anti-cheese.block-early-eye-throwing} is
     * false, so a server that switched the rule off does not pay to capture the key. The single
     * place that decision is made, so {@link #allRequiredAdvancements} and the listener cannot
     * disagree about it: a key the listener required but the capture never queried would be waived
     * as uncovered on every throw.
     */
    public static Optional<String> earlyEyeThrowAdvancement(PluginConfig config) {
        Objects.requireNonNull(config, "config");
        if (!config.antiCheese().enabled() || !config.antiCheese().blockEarlyEyeThrowing()) {
            return Optional.empty();
        }
        return Optional.of(EARLY_EYE_ADVANCEMENT);
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
     *   <li><strong>The gate is on but the key is blank.</strong> <em>No {@code config.yml} can
     *       produce this any more.</em> #92 made a blank {@code required-advancement} beside
     *       {@code gate-mending-trade: true} an {@code UnenforceableGateException} at the read
     *       site, precisely because it is a gate that reports itself armed and admits everyone; and
     *       with the gate off the branch above returns first. The branch is kept for a
     *       {@code PluginConfig} built in code — {@code VillagerProgression} is a public record —
     *       where nothing has been through the read site. It answers "no requirement" rather than
     *       requiring {@code ""}, so {@link BukkitAdvancementLookup} is never handed the empty key
     *       from this path. Any non-blank key arriving from configuration is already canonical and
     *       already known to resolve.</li>
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
