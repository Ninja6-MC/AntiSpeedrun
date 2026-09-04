package com.ninja6.antispeedrun.gating;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;

import org.bukkit.Material;

import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * Binds {@link ItemGateCompiler} to Bukkit's {@code Material}.
 *
 * <p>The only type in this plugin's gating code that names a server class, which is what keeps the
 * compiler and the configuration model testable without a server on the classpath.
 *
 * <p>{@code Material.values()} clones its backing array on every call — the standard cost of an
 * enum's generated {@code values()} — and the compiler walks the whole universe, so the array is
 * cached here once and handed in rather than re-derived per tier or per reload. Audit finding R-11.
 */
public final class MaterialGates {

    /**
     * Every material, resolved once. Safe to share: it is written during class initialisation and
     * read afterwards, and nothing here hands the array itself out — {@link #compile} passes it to
     * a compiler that only reads it.
     */
    private static final Material[] UNIVERSE = Material.values();

    private MaterialGates() {
    }

    /**
     * Compiles the gated-item tiers of {@code progression} into a live lookup.
     *
     * @param progression the item-progression section of a configuration snapshot
     * @param warnings    collector for recoverable problems; the caller logs them
     * @return the compiled table; empty when item progression is switched off, so a caller never
     *         has to check {@code enabled} and the table at the same time
     * @throws GateCollisionException if two tiers claim one material and neither dominates
     */
    public static ItemGateTable<Material> compile(PluginConfig.ItemProgression progression,
                                                  List<String> warnings)
            throws GateCollisionException {
        if (!progression.enabled()) {
            return empty();
        }
        return ItemGateCompiler.compile(Material.class, UNIVERSE, progression.gatedItems(), warnings);
    }

    /** A table that gates nothing. The startup and disabled states, without a null to check. */
    public static ItemGateTable<Material> empty() {
        return new ItemGateTable<>(EnumSet.noneOf(Material.class), new EnumMap<>(Material.class));
    }
}
