package com.ninja6.antispeedrun.progression;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.ninja6.antispeedrun.config.PluginConfig.DimensionGate;
import com.ninja6.antispeedrun.config.PluginConfig.ItemTier;
import com.ninja6.antispeedrun.storage.PersonalCredit;

/**
 * What a player must have done to clear one milestone.
 *
 * <p>{@code config.yml} declares advancement and time requirements in two places — under
 * {@code dimension-gates.<dimension>} and under {@code item-progression.gated-items.<tier>} — and
 * both mean exactly the same thing. Item tiers can also require explicit personal credits.
 * This record is the shared shape, so
 * {@link MilestoneEvaluator} is written once rather than once per configuration section.
 *
 * <p>Immutable, like everything reachable from a configuration snapshot; safe to publish to any
 * thread.
 *
 * @param advancements   namespaced advancement keys, <em>all</em> of which must be earned. Canonical
 *                       — {@code ConfigReader} trims them, supplies the implicit {@code minecraft:}
 *                       namespace and rejects the whole document for any key the server's parser
 *                       would not accept — so these are the same strings the gate compiler compared
 *                       and the same ones {@link AdvancementLookup} is handed. Whether the key names
 *                       an advancement that exists <em>on this server</em> is still the lookup's
 *                       problem, and still fails open
 * @param credits        personal credits, all of which must be earned while personal credits apply
 * @param playtimeHours  hours of {@code Statistic.PLAY_ONE_MINUTE} required; {@code 0.0} or less
 *                       means no playtime requirement
 * @param accountAgeDays days since first join to <em>this server</em> required; {@code 0} or less
 *                       means no age requirement. See {@link PlayerProgressionSnapshot} for why
 *                       this is a tenure requirement and not an account-age one
 */
public record MilestoneRequirement(List<String> advancements,
                                   List<PersonalCredit> credits,
                                   double playtimeHours, int accountAgeDays) {

    public MilestoneRequirement(List<String> advancements, double playtimeHours, int accountAgeDays) {
        this(advancements, List.of(), playtimeHours, accountAgeDays);
    }

    /** All keys captured by the shared snapshot; credit keys never use vanilla resolution. */
    public List<String> lookupKeys() {
        var keys = new ArrayList<>(advancements);
        credits.forEach(credit -> keys.add(credit.requirementKey()));
        return List.copyOf(keys);
    }

    private static final MilestoneRequirement NONE = new MilestoneRequirement(List.of(), 0.0D, 0);

    public MilestoneRequirement {
        credits = List.copyOf(Objects.requireNonNull(credits, "credits"));
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
                tier.requireAdvancements(), tier.requireCredits(),
                tier.requirePlaytimeHours(), tier.requireAccountAgeDays());
    }

    /** Whether this milestone demands nothing at all, in which case evaluation can be skipped. */
    public boolean isEmpty() {
        return advancements.isEmpty() && credits.isEmpty() && playtimeHours <= 0.0D && accountAgeDays <= 0;
    }
}
