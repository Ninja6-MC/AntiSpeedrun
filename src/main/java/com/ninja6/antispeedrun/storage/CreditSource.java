package com.ninja6.antispeedrun.storage;

import java.util.Optional;

/**
 * How a {@link PersonalCredit} was earned.
 *
 * <p>Kept with the credit so that {@code item-progression.count-structure-loot} decides at lookup
 * time whether loot counts: switching the setting changes the answer at once, with nothing to
 * re-infer (the Amendment of {@code docs/provenance-model.md}, "The recorder always runs").
 */
public enum CreditSource {

    /** The player's own mining, smelting, crafting or kill. */
    ACTION("action"),

    /** Loot the player generated: a structure container or a vault they opened first. */
    LOOT("loot");

    private final String id;

    CreditSource(String id) {
        this.id = id;
    }

    /** The name the source is stored under. Never changes once shipped. */
    public String id() {
        return id;
    }

    /** The source stored as {@code id}, if any. */
    public static Optional<CreditSource> fromId(String id) {
        for (CreditSource source : values()) {
            if (source.id.equals(id)) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }
}
