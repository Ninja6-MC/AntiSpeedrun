package com.ninja6.antispeedrun.progression;

import java.util.List;
import java.util.Objects;

import com.ninja6.antispeedrun.config.PluginConfig.DimensionGate;
import com.ninja6.antispeedrun.config.PluginConfig.ItemTier;

/**
 * What a player must have done to clear one milestone.
 *
 * <p>{@code config.yml} declares the same three requirement keys in two unrelated places — under
 * {@code dimension-gates.<dimension>} and under {@code item-progression.gated-items.<tier>} — and
 * both mean exactly the same thing. This record is the shared shape, so
 * {@link MilestoneEvaluator} is written once rather than once per configuration section.
 *
 * <p>Immutable, like everything reachable from a configuration snapshot; safe to publish to any
 * thread.
 *
 * @param advancements   namespaced advancement keys, <em>all</em> of which must be earned. Raw
 *                       strings exactly as configured; resolution and its failure modes are
 *                       {@link AdvancementLookup}'s problem
 * @param playtimeHours  hours of {@code Statistic.PLAY_ONE_MINUTE} required; {@code 0.0} or less
 *                       means no playtime requirement
 * @param accountAgeDays days since first join to <em>this server</em> required; {@code 0} or less
 *                       means no age requirement. See {@link PlayerProgressionSnapshot} for why
 *                       this is a tenure requirement and not an account-age one
 */
public record MilestoneRequirement(List<String> advancements, double playtimeHours, int accountAgeDays) {

    private static final MilestoneRequirement NONE = new MilestoneRequirement(List.of(), 0.0D, 0);

    public MilestoneRequirement {
        advancements = List.copyOf(Objects.requireNonNull(advancements, "advancements"));
    }

    /** A milestone nothing is required for; always eligible. */
    public static MilestoneRequirement none() {
        return NONE;
    }

    /** The requirements of a dimension gate, as configured. */
    public static MilestoneRequirement of(DimensionGate gate) {
        Objects.requireNonNull(gate, "gate");
        return new MilestoneRequirement(
                gate.requireAdvancements(), gate.requirePlaytimeHours(), gate.requireAccountAgeDays());
    }

    /**
     * The requirements of an item tier, as configured. Provided so the item-gating workstream
     * (#11, #55) evaluates tiers through the same cache rather than re-querying Bukkit per pickup.
     */
    public static MilestoneRequirement of(ItemTier tier) {
        Objects.requireNonNull(tier, "tier");
        return new MilestoneRequirement(
                tier.requireAdvancements(), tier.requirePlaytimeHours(), tier.requireAccountAgeDays());
    }

    /** Whether this milestone demands nothing at all, in which case evaluation can be skipped. */
    public boolean isEmpty() {
        return advancements.isEmpty() && playtimeHours <= 0.0D && accountAgeDays <= 0;
    }
}
