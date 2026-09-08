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
 * <p>The principle both arms share, stated once: <strong>the plugin refuses to start when the file
 * describes gating it cannot enforce, and falls back to the defaults only when the file describes
 * no gating at all.</strong>
 */
public class UnenforceableGateException extends ConfigLoadException {

    private static final long serialVersionUID = 1L;

    public UnenforceableGateException(String message) {
        super(message);
    }
}
