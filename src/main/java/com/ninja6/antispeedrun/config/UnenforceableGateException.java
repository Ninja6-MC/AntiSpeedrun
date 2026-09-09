package com.ninja6.antispeedrun.config;

/**
 * Thrown when {@code config.yml} parses cleanly but describes a gate this server could not enforce
 * — today, an advancement key the resolver rejects, or a {@code require-advancements} list written
 * with entries none of which name an advancement.
 *
 * <p>It is a {@link ConfigLoadException} because the effect on a reload is identical: the document
 * is rejected, nothing is published, and the configuration already running stays live. The subtype
 * exists for the <em>other</em> caller. At {@code onEnable} there is no previous configuration to
 * keep, so {@code AntiSpeedrunPlugin} has to choose between falling back to
 * {@link PluginConfig#defaults()} and refusing to start, and the two failures deserve opposite
 * answers (#91):
 *
 * <ul>
 *   <li>A document that cannot be parsed at all describes <em>nothing</em>. Falling back to the
 *       shipped defaults is a legible landing zone for an operator who has just mistyped something,
 *       which is what audit finding R-11 asked for, and the defaults are a coherent configuration
 *       in their own right.</li>
 *   <li>A document that parses and then names a gate that cannot be enforced describes gating this
 *       server would not actually apply. Booting on defaults there turns <em>all</em> item gating
 *       off over one typo — the armed-but-permissive outcome the fail-closed rule from #83 exists
 *       to prevent, reached by way of the fallback instead of by way of the file. So this one stops
 *       the plugin, exactly as an unresolvable tier collision already does.</li>
 * </ul>
 *
 * <p>The principle both arms share, stated once: <strong>where the plugin can tell that the file
 * describes gating it could not enforce, it refuses to start rather than fall back to the
 * defaults.</strong> The fallback is for a file that describes no gating at all.
 *
 * <h2>What reaches this exception</h2>
 *
 * <p>Four ways a {@code config.yml} can describe a gate that is switched on and requires nothing,
 * listed together because they were closed one at a time and read as one rule:
 *
 * <ul>
 *   <li>an advancement key the server's parser cannot resolve (#83);</li>
 *   <li>a requirement list written with entries that all name nothing, so it empties itself
 *       (#89);</li>
 *   <li>{@code require-advancements} written as a scalar rather than a list, where falling back
 *       leaves no requirement at all — an item tier, whose default is the empty list (#92);</li>
 *   <li>{@code gate-mending-trade: true} with a blank {@code required-advancement}, which warns
 *       about neither half because each half is legitimate on its own (#92).</li>
 * </ul>
 *
 * <h2>What this deliberately does not cover</h2>
 *
 * <p>Two exemptions, both for the same reason: neither describes gating this server would fail to
 * enforce, and refusing a boot over a configuration that is still enforceable is a false refusal.
 *
 * <ul>
 *   <li>A gate whose {@code enabled} flag is {@code false} claims nothing, so a bad key inside it
 *       is a warning rather than a refusal to boot — see
 *       {@code ConfigReader#advancementKeys(String, java.util.List, boolean)}.</li>
 *   <li>A scalar {@code require-advancements} under a <em>dimension</em> gate, where the shipped
 *       default is non-empty: the gate falls back to requiring the shipped advancements rather than
 *       what the file says, which is worth the wrong-type warning it already gets, but it is still
 *       requiring something.</li>
 * </ul>
 */
public class UnenforceableGateException extends ConfigLoadException {

    private static final long serialVersionUID = 1L;

    public UnenforceableGateException(String message) {
        super(message);
    }
}
