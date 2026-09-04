package com.ninja6.antispeedrun.storage;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which dimension gates an operator has opened server-wide, and when.
 *
 * <p>Immutable, so it is published to every region thread by one {@code volatile} write in
 * {@link DimensionUnlockStore} — the same contract {@code PluginConfig} uses. Every mutator returns
 * a new instance.
 *
 * <p>The timestamp is kept rather than a bare boolean because it costs nothing and answers the
 * question an operator actually asks after an event ("when did we open this?"). Nothing depends on
 * its value.
 */
public final class UnlockState {

    /** Prefix every unlock key carries in the persisted document. */
    static final String KEY_PREFIX = "dimension-unlocks.";

    private static final UnlockState EMPTY = new UnlockState(new EnumMap<>(DimensionUnlock.class));

    private final Map<DimensionUnlock, Long> unlockedAt;

    private UnlockState(Map<DimensionUnlock, Long> unlockedAt) {
        this.unlockedAt = unlockedAt;
    }

    /** Nothing unlocked. The state a server with no {@code state.yml} starts on. */
    public static UnlockState empty() {
        return EMPTY;
    }

    /** Whether {@code dimension} has been unlocked by an operator. */
    public boolean isUnlocked(DimensionUnlock dimension) {
        return unlockedAt.containsKey(Objects.requireNonNull(dimension, "dimension"));
    }

    /** When {@code dimension} was unlocked, in wall-clock milliseconds; empty if it is not. */
    public Optional<Long> unlockedAt(DimensionUnlock dimension) {
        return Optional.ofNullable(unlockedAt.get(Objects.requireNonNull(dimension, "dimension")));
    }

    /**
     * This state with {@code dimension} unlocked at {@code atMillis}.
     *
     * <p>Returns {@code this} unchanged when it is already unlocked, so a repeated {@code /asr
     * unlock} does not rewrite the file and does not move the recorded timestamp.
     */
    public UnlockState unlocked(DimensionUnlock dimension, long atMillis) {
        Objects.requireNonNull(dimension, "dimension");
        if (unlockedAt.containsKey(dimension)) {
            return this;
        }
        Map<DimensionUnlock, Long> copy = new EnumMap<>(unlockedAt);
        copy.put(dimension, atMillis);
        return new UnlockState(copy);
    }

    /** This state with {@code dimension} locked again; {@code this} when it already was. */
    public UnlockState locked(DimensionUnlock dimension) {
        Objects.requireNonNull(dimension, "dimension");
        if (!unlockedAt.containsKey(dimension)) {
            return this;
        }
        Map<DimensionUnlock, Long> copy = new EnumMap<>(unlockedAt);
        copy.remove(dimension);
        return new UnlockState(copy);
    }

    /**
     * This state as the flat document {@link StateFile} persists.
     *
     * <p>Flat, dotted keys rather than nested mappings: it keeps {@link YamlStateFile} to a
     * {@code getKeys(true)} loop with no {@code ConfigurationSection} walking, and it keeps this
     * method testable without any of Bukkit on the classpath.
     */
    public Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        for (DimensionUnlock dimension : DimensionUnlock.values()) {
            Long at = unlockedAt.get(dimension);
            if (at != null) {
                document.put(KEY_PREFIX + dimension.storageKey(), at);
            }
        }
        return Map.copyOf(document);
    }

    /**
     * Reads a state back out of a persisted document.
     *
     * <p>Deliberately forgiving, and it is worth saying why: this file is hand-editable and is the
     * only record that a dimension was opened. An entry that has been mangled — a key for a
     * dimension this version does not know, a value that is not a number — is skipped rather than
     * thrown on, because rejecting the whole document over one bad line would silently re-lock
     * every dimension the server had opened. Unreadable entries are dropped on the next write.
     */
    public static UnlockState fromDocument(Map<String, Object> document) {
        Objects.requireNonNull(document, "document");
        Map<DimensionUnlock, Long> parsed = new EnumMap<>(DimensionUnlock.class);
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            if (!entry.getKey().startsWith(KEY_PREFIX)) {
                continue;
            }
            String storageKey = entry.getKey().substring(KEY_PREFIX.length());
            Optional<DimensionUnlock> dimension = DimensionUnlock.byStorageKey(storageKey);
            if (dimension.isEmpty() || !(entry.getValue() instanceof Number at)) {
                continue;
            }
            parsed.put(dimension.get(), at.longValue());
        }
        return parsed.isEmpty() ? EMPTY : new UnlockState(parsed);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UnlockState state && unlockedAt.equals(state.unlockedAt);
    }

    @Override
    public int hashCode() {
        return unlockedAt.hashCode();
    }

    @Override
    public String toString() {
        return "UnlockState" + unlockedAt;
    }
}
