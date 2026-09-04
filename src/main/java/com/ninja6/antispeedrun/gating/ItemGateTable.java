package com.ninja6.antispeedrun.gating;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * The compiled material lookup: which materials are gated, and by which tier.
 *
 * <p>Immutable and built once per configuration snapshot by {@link ItemGateCompiler}. A reload
 * compiles a whole new table and publishes it with one {@code volatile} write; this type has no
 * mutator and nothing reachable from it can be changed, so the write safely publishes the whole
 * graph to every Folia region thread.
 *
 * <h2>Why there is nothing else in this class</h2>
 *
 * {@link #isGated} sits on the item pickup path, which fires for every item entity every player
 * walks over. The original acceptance criterion asked it to run in under 10 nanoseconds, which no
 * JUnit test can honestly check without JMH; audit finding R-11 replaced it with a criterion a test
 * can actually assert — <em>the lookup performs no allocation and no string operation</em>.
 *
 * <p>This class is written to that criterion literally. It holds an {@link EnumSet} and an
 * {@link EnumMap}, both array-indexed by {@code ordinal()}, and its lookup methods do nothing but
 * read them. Every pattern parse, case fold, prefix test and material-name resolution happens once
 * in {@link ItemGateCompiler}. Warnings live on the compiler's result rather than here, so that
 * this class's constant pool refers to no string type at all — which is exactly what
 * {@code ItemGateTableStructureTest} asserts, by parsing the compiled class file.
 *
 * <p>Consequently: no {@code toString}, no {@code equals}, no message formatting and no two-argument
 * {@code Objects.requireNonNull}, since its descriptor alone would name {@code java.lang.String}.
 *
 * @param <M> the material enum; {@code org.bukkit.Material} in production, a fixture enum in tests
 */
public final class ItemGateTable<M extends Enum<M>> {

    private final EnumSet<M> gated;
    private final EnumMap<M, PluginConfig.ItemTier> byMaterial;
    private final Set<M> gatedView;
    private final Map<M, PluginConfig.ItemTier> assignmentView;

    ItemGateTable(EnumSet<M> gated, EnumMap<M, PluginConfig.ItemTier> byMaterial) {
        this.gated = Objects.requireNonNull(gated);
        this.byMaterial = Objects.requireNonNull(byMaterial);
        this.gatedView = Collections.unmodifiableSet(gated);
        this.assignmentView = Collections.unmodifiableMap(byMaterial);
    }

    /**
     * Whether {@code material} is gated by any tier. One {@link EnumSet} membership test: a bit
     * test against a {@code long}, or an array read once the universe exceeds 64 constants.
     */
    public boolean isGated(M material) {
        return gated.contains(material);
    }

    /**
     * The tier that owns {@code material}, or {@code null} if it is ungated. One {@link EnumMap}
     * read. Returns the configured tier itself, so a caller reaches its requirements and its hint
     * without a second lookup.
     */
    public PluginConfig.ItemTier tierFor(M material) {
        return byMaterial.get(material);
    }

    /** Every gated material, unmodifiable. For {@code /asr} diagnostics, not for an event path. */
    public Set<M> gatedMaterials() {
        return gatedView;
    }

    /** The whole material-to-tier assignment, unmodifiable. Diagnostics and tests. */
    public Map<M, PluginConfig.ItemTier> assignments() {
        return assignmentView;
    }

    /** How many materials this table gates. */
    public int size() {
        return gated.size();
    }

    /** Whether this table gates nothing at all. */
    public boolean isEmpty() {
        return gated.isEmpty();
    }
}
