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
 * <h2>What this does not cover</h2>
 *
 * <p>Stated as a limit rather than left to be discovered, because the sentence above reads like a
 * guarantee and is not yet one. Two ways of writing a requirement still disarm a gate without
 * reaching this exception, so a file can describe gating this server will not apply and start
 * anyway:
 *
 * <ul>
 *   <li>{@code require-advancements} written as a scalar rather than a list falls back through
 *       {@code ConfigReader#stringList} to the default on a warning, which can arm a tier with no
 *       requirement.</li>
 *   <li>{@code gate-mending-trade: true} with a blank {@code required-advancement} is a gate that
 *       is on and requires nothing, and warns about neither half, because each half is legitimate
 *       on its own.</li>
 * </ul>
 *
 * <p>Both are #92 and are fixed there, not here; this class covers the unresolvable key and the
 * self-emptying list. A third exemption is deliberate and stays: a gate whose {@code enabled} flag
 * is {@code false} claims nothing, so an unresolvable key inside it is a warning rather than a
 * refusal to boot — see {@code ConfigReader#advancementKeys(String, java.util.List, boolean)}.
 */
public class UnenforceableGateException extends ConfigLoadException {

    private static final long serialVersionUID = 1L;

    public UnenforceableGateException(String message) {
        super(message);
    }
}
