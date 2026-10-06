package com.ninja6.antispeedrun.progression;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.storage.PersonalCredit;
import com.ninja6.antispeedrun.storage.PersonalCreditStore;

/**
 * Answers the possession-triggered gate advancements from personal credits (#215).
 *
 * <p>Vanilla completes {@code story/mine_stone}, {@code story/smelt_iron}, {@code story/iron_tools},
 * {@code story/upgrade_tools}, {@code story/mine_diamond} and {@code nether/obtain_blaze_rod} the
 * moment the player holds the item, so a gift opens the gate. Every tier and dimension gate reads
 * through the one {@link AdvancementLookup} the plugin builds, so wrapping it here changes what those
 * keys mean without touching any gate. The semantics are the Amendment of
 * {@code docs/provenance-model.md}, "Lookup semantics":
 *
 * <ul>
 *   <li>While {@code item-progression.require-personal-credit} is on, a protected key is
 *       {@link State#EARNED} only when every credit standing in for it is recorded, and
 *       {@link State#NOT_EARNED} otherwise. The vanilla advancement is not consulted for the
 *       answer, so {@code /advancement grant} and {@code revoke} change nothing.</li>
 *   <li>{@link State#UNRESOLVABLE} from the wrapped lookup passes through unchanged: a key the
 *       server cannot resolve is waived everywhere, and a credit must not turn a waived requirement
 *       back into an enforced one.</li>
 *   <li>Every other key, and every key while the toggle is off, is the wrapped lookup's answer.</li>
 * </ul>
 *
 * <p>Both settings are read from the live snapshot on every call, so {@code /asr reload} changes the
 * answer at once. {@code count-structure-loot} decides only whether a {@code loot} record counts;
 * the record itself is kept either way, so switching it back restores the answer.
 *
 * <p>Thread-safe on the wrapped lookup's terms: the store and the snapshot are volatile reads.
 */
public final class PersonalCreditAdvancementLookup implements AdvancementLookup {

    /** Each protected key and the credits it needs, all of them. */
    private static final Map<String, Set<PersonalCredit>> REQUIRED = required();

    private final AdvancementLookup delegate;
    private final PersonalCreditStore credits;
    private final Supplier<PluginConfig> config;

    /**
     * @param delegate the server-backed lookup, consulted for every key
     * @param credits  the recorded credits
     * @param config   the live configuration snapshot
     */
    public PersonalCreditAdvancementLookup(AdvancementLookup delegate, PersonalCreditStore credits,
                                           Supplier<PluginConfig> config) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.credits = Objects.requireNonNull(credits, "credits");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** The advancement keys answered from credits while the toggle is on. */
    public static Set<String> protectedKeys() {
        return REQUIRED.keySet();
    }

    @Override
    public State state(Player player, String key) {
        State vanilla = delegate.state(player, key);
        Set<PersonalCredit> needed = REQUIRED.get(key);
        if (needed == null || vanilla == State.UNRESOLVABLE) {
            return vanilla;
        }
        PluginConfig.ItemProgression settings = config.get().itemProgression();
        if (!settings.requirePersonalCredit()) {
            return vanilla;
        }
        UUID id = player.getUniqueId();
        for (PersonalCredit credit : needed) {
            if (!credits.has(id, credit, settings.countStructureLoot())) {
                return State.NOT_EARNED;
            }
        }
        return State.EARNED;
    }

    private static Map<String, Set<PersonalCredit>> required() {
        Map<String, EnumSet<PersonalCredit>> grouped = new HashMap<>();
        for (PersonalCredit credit : PersonalCredit.values()) {
            grouped.computeIfAbsent(credit.advancement(), k -> EnumSet.noneOf(PersonalCredit.class))
                    .add(credit);
        }
        Map<String, Set<PersonalCredit>> frozen = new HashMap<>();
        grouped.forEach((key, set) -> frozen.put(key, Set.copyOf(set)));
        return Map.copyOf(frozen);
    }
}
