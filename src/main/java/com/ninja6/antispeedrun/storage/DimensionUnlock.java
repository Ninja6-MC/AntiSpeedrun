package com.ninja6.antispeedrun.storage;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.ninja6.antispeedrun.progression.Milestone;

/**
 * A dimension whose gate an operator can open server-wide with {@code /asr unlock}.
 *
 * <p>{@link #milestoneId()} is deliberately the same identifier
 * {@link com.ninja6.antispeedrun.progression.Milestone} uses, so the dimension-entry listeners
 * (#34, #35) can ask {@link DimensionUnlockStore} about the gate they are already evaluating
 * without a second mapping table to keep in step.
 *
 * <h2>Two spellings, on purpose</h2>
 *
 * {@link #storageKey()} is {@code the_end}, matching {@code dimension-gates.the_end} in
 * {@code config.yml} and the milestone id, because the persisted file and the configuration should
 * read the same. The command argument is {@code end}, because that is what Task 8.1.1 specifies and
 * what an operator types. {@link #parse(String)} accepts either, so neither spelling is a trap.
 */
public enum DimensionUnlock {

    /** The Nether. */
    NETHER("nether", "nether", "The Nether", Milestone.NETHER_ID),

    /** The End. */
    THE_END("the_end", "end", "The End", Milestone.END_ID);

    private final String storageKey;
    private final String argument;
    private final String displayName;
    private final String milestoneId;

    DimensionUnlock(String storageKey, String argument, String displayName, String milestoneId) {
        this.storageKey = storageKey;
        this.argument = argument;
        this.displayName = displayName;
        this.milestoneId = milestoneId;
    }

    /** The key this dimension is recorded under in {@code state.yml}. Never changes. */
    public String storageKey() {
        return storageKey;
    }

    /** The token an operator types: {@code nether} or {@code end}. */
    public String argument() {
        return argument;
    }

    /** Player-facing name, for command output. */
    public String displayName() {
        return displayName;
    }

    /** The {@link Milestone} identifier for this dimension's gate. */
    public String milestoneId() {
        return milestoneId;
    }

    /** Every command token, in fixed order, for tab completion. */
    public static List<String> arguments() {
        return List.of(NETHER.argument, THE_END.argument);
    }

    /**
     * Resolves an operator-supplied token, case-insensitively.
     *
     * <p>Accepts the command spelling ({@code end}), the storage and configuration spelling
     * ({@code the_end}), and the enum constant name.
     *
     * @return the dimension, or empty when {@code token} names none
     */
    public static Optional<DimensionUnlock> parse(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String normalised = token.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (DimensionUnlock dimension : values()) {
            if (normalised.equals(dimension.argument)
                    || normalised.equals(dimension.storageKey)
                    || normalised.equals(dimension.name().toLowerCase(Locale.ROOT))) {
                return Optional.of(dimension);
            }
        }
        return Optional.empty();
    }

    /** The dimension whose gate is the milestone {@code milestoneId}, if any. */
    public static Optional<DimensionUnlock> byMilestone(String milestoneId) {
        Objects.requireNonNull(milestoneId, "milestoneId");
        for (DimensionUnlock dimension : values()) {
            if (dimension.milestoneId.equals(milestoneId)) {
                return Optional.of(dimension);
            }
        }
        return Optional.empty();
    }

    /** The dimension recorded under {@code storageKey}, if any. */
    public static Optional<DimensionUnlock> byStorageKey(String storageKey) {
        Objects.requireNonNull(storageKey, "storageKey");
        for (DimensionUnlock dimension : values()) {
            if (dimension.storageKey.equals(storageKey)) {
                return Optional.of(dimension);
            }
        }
        return Optional.empty();
    }
}
