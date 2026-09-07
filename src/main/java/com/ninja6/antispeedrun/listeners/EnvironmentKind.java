package com.ninja6.antispeedrun.listeners;

import java.util.Locale;
import java.util.Objects;

/**
 * The four dimension kinds a transit can start in or end in, named independently of Bukkit.
 *
 * <p>{@code org.bukkit.World.Environment} is what the server hands a listener, but this plugin's
 * decision logic must be exercisable off-server: {@code paper-api} is {@code compileOnly}, and the
 * convention {@link com.ninja6.antispeedrun.progression.MilestoneEvaluator} set is that the rule
 * lives in a pure type and the listener is only wiring. So the listener translates once, at the
 * edge, with {@link #of(String)}, and every rule below is written against this enum.
 *
 * <p>{@link #CUSTOM} covers Bukkit's {@code CUSTOM} environment and any constant a future API adds:
 * a world whose kind this plugin does not recognise is not one of the two gated destinations, and
 * treating it as such would gate a datapack dimension nobody configured a gate for.
 */
public enum EnvironmentKind {

    /** {@code World.Environment.NORMAL}. */
    OVERWORLD,

    /** {@code World.Environment.NETHER} — gated by {@code dimension-gates.nether}. */
    NETHER,

    /** {@code World.Environment.THE_END} — gated by {@code dimension-gates.the_end}. */
    THE_END,

    /** Anything else, including {@code World.Environment.CUSTOM}. Never gated. */
    CUSTOM;

    /**
     * Translates a {@code World.Environment} constant name.
     *
     * @param environmentName the value of {@code World.Environment#name()}; {@code null} yields
     *                        {@link #CUSTOM}, since a transit whose end is unknown is not one this
     *                        plugin can attribute to a configured gate
     * @return the kind; never {@code null}
     */
    public static EnvironmentKind of(String environmentName) {
        if (environmentName == null) {
            return CUSTOM;
        }
        return switch (environmentName.toUpperCase(Locale.ROOT)) {
            case "NORMAL" -> OVERWORLD;
            case "NETHER" -> NETHER;
            case "THE_END" -> THE_END;
            default -> CUSTOM;
        };
    }

    /** Whether this kind is one of the two dimensions {@code dimension-gates} can gate. */
    public boolean isGatable() {
        return this == NETHER || this == THE_END;
    }

    @Override
    public String toString() {
        return Objects.toString(name());
    }
}
