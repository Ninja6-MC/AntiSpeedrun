package com.ninja6.antispeedrun.commands;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.ninja6.antispeedrun.storage.CreditSource;
import com.ninja6.antispeedrun.storage.PersonalCredit;
import com.ninja6.antispeedrun.storage.PersonalCreditStore;

/**
 * What {@code /asr credit <grant|revoke> <player> <credit|smelt-iron|all>} was asked to do (#217).
 *
 * <p>Bukkit-free for the reason {@link UnlockArgument} gives: the decision is the part worth
 * testing, and {@code paper-api} is {@code compileOnly}. Like that type, every word is checked and
 * none defaults: {@code /asr credit revoke Steve mine-stnoe} names no credit, so it is refused
 * rather than read as {@code all}.
 *
 * <h2>Credit names</h2>
 *
 * A credit is named by its {@link PersonalCredit#id() stored id}, the names in the Amendment of
 * {@code docs/provenance-model.md}; underscores are read as hyphens, so the advancement spelling
 * {@code mine_stone} works too. Two words name more than one credit: {@code smelt-iron} is both
 * sub-credits of {@code story/smelt_iron}, the only form in which an advancement can be granted or
 * revoked whole, and {@code all} is every credit.
 *
 * <p>A grant records the credit with {@link CreditSource#ACTION}, so it counts whatever
 * {@code item-progression.count-structure-loot} says. A revoke removes the credit through every
 * source, loot included.
 */
public sealed interface CreditArgument {

    /** The word naming every credit at once. */
    String ALL = "all";

    /** The word naming both sub-credits of {@code story/smelt_iron}. */
    String SMELT_IRON = "smelt-iron";

    /** Whether a {@link Change} adds credits or takes them away. */
    enum Action {

        GRANT("grant"),
        REVOKE("revoke");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        /** The word an operator types. */
        public String label() {
            return label;
        }

        /** Every label, in declaration order. */
        public static List<String> labels() {
            return List.of(GRANT.label, REVOKE.label);
        }

        static Optional<Action> parse(String token) {
            String normalised = token.trim().toLowerCase(Locale.ROOT);
            for (Action action : values()) {
                if (action.label.equals(normalised)) {
                    return Optional.of(action);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Grant or revoke {@code credits} for the player named by {@code target}.
     *
     * @param target  the player word as typed: a name or a UUID, resolved by the caller
     * @param credits never empty
     */
    record Change(Action action, String target, Set<PersonalCredit> credits) implements CreditArgument {

        public Change {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(target, "target");
            if (credits.isEmpty()) {
                throw new IllegalArgumentException("a change names at least one credit");
            }
            credits = Set.copyOf(credits);
        }

        /**
         * Applies this change to {@code player}'s record. The store publishes each change before
         * returning, so every gate reading through it sees the result at once.
         *
         * @return the credits that actually changed, in declaration order; empty when the player
         *         already held (or already lacked) all of them
         */
        public List<PersonalCredit> applyTo(PersonalCreditStore store, UUID player) {
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(player, "player");
            List<PersonalCredit> changed = new ArrayList<>();
            for (PersonalCredit credit : PersonalCredit.values()) {
                if (!credits.contains(credit)) {
                    continue;
                }
                boolean did = action == Action.GRANT
                        ? store.record(player, credit, CreditSource.ACTION)
                        : store.revoke(player, credit);
                if (did) {
                    changed.add(credit);
                }
            }
            return List.copyOf(changed);
        }
    }

    /**
     * The arguments do not name a change.
     *
     * @param reason what was wrong
     * @param token  the offending word, or empty when there was none to point at
     */
    record Invalid(Reason reason, String token) implements CreditArgument {
    }

    /** Why a parse failed. Each maps to its own message. */
    enum Reason {

        /** Nothing after {@code credit}. */
        MISSING_ACTION,

        /** The second word is neither {@code grant} nor {@code revoke}. */
        UNKNOWN_ACTION,

        /** No player word. */
        MISSING_PLAYER,

        /** No credit word. */
        MISSING_CREDIT,

        /** The fourth word names no credit. */
        UNKNOWN_CREDIT,

        /** A fifth word, which is not assumed to mean anything. */
        EXTRA_ARGUMENT
    }

    /**
     * Parses the whole argument array, {@code args[0]} being the {@code credit} label itself.
     *
     * @return a {@link Change} or an {@link Invalid}; never {@code null}
     */
    static CreditArgument parse(String[] args) {
        if (args == null || args.length < 2 || args[1].isBlank()) {
            return new Invalid(Reason.MISSING_ACTION, "");
        }
        Optional<Action> action = Action.parse(args[1]);
        if (action.isEmpty()) {
            return new Invalid(Reason.UNKNOWN_ACTION, args[1]);
        }
        if (args.length < 3 || args[2].isBlank()) {
            return new Invalid(Reason.MISSING_PLAYER, "");
        }
        if (args.length < 4 || args[3].isBlank()) {
            return new Invalid(Reason.MISSING_CREDIT, "");
        }
        Optional<Set<PersonalCredit>> credits = credits(args[3]);
        if (credits.isEmpty()) {
            return new Invalid(Reason.UNKNOWN_CREDIT, args[3]);
        }
        if (args.length > 4) {
            return new Invalid(Reason.EXTRA_ARGUMENT, args[4]);
        }
        return new Change(action.get(), args[2].trim(), credits.get());
    }

    /** The credits {@code token} names, if it names any. */
    static Optional<Set<PersonalCredit>> credits(String token) {
        String normalised = token.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (ALL.equals(normalised)) {
            return Optional.of(EnumSet.allOf(PersonalCredit.class));
        }
        if (SMELT_IRON.equals(normalised)) {
            return Optional.of(EnumSet.of(PersonalCredit.MINED_IRON, PersonalCredit.SMELTED_IRON));
        }
        return PersonalCredit.fromId(normalised).map(EnumSet::of);
    }

    /** Every credit word, in the order completion offers them. */
    static List<String> creditWords() {
        List<String> words = new ArrayList<>();
        for (PersonalCredit credit : PersonalCredit.values()) {
            words.add(credit.id());
        }
        words.add(SMELT_IRON);
        words.add(ALL);
        return List.copyOf(words);
    }

    /**
     * The player word read as a UUID, when it is one. A UUID always names the player it is, whether
     * or not they have joined, so it is how an operator reaches someone the server has no cached
     * name for.
     */
    static Optional<UUID> asUuid(String target) {
        String trimmed = target.trim();
        // UUID.fromString accepts short forms such as "1-2-3-4-5"; only the canonical spelling is
        // taken as a UUID, so nothing that could be a name is misread as one.
        if (trimmed.length() != 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(trimmed));
        } catch (IllegalArgumentException notUuid) {
            return Optional.empty();
        }
    }
}
