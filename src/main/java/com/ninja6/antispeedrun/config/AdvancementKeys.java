package com.ninja6.antispeedrun.config;

import org.bukkit.NamespacedKey;

/**
 * The one definition of what an advancement key is, shared by the configuration reader and by every
 * consumer that compares two of them.
 *
 * <h2>Why this is a shared type rather than a private helper</h2>
 *
 * <p>{@code config.yml} names advancements in three places — {@code dimension-gates.*
 * .require-advancements}, {@code item-progression.gated-items.*.require-advancements} and
 * {@code villager-progression.required-advancement} — and the strings it names them with are
 * eventually handed to {@code NamespacedKey.fromString} on the running server. Every place that
 * decides whether two configured keys are <em>the same requirement</em> has to answer that question
 * the way the server will, or the plugin can decide one thing at boot and the opposite at runtime.
 * That divergence was issue #83: {@code ItemGateCompiler} folded the implicit {@code minecraft:}
 * namespace while nothing on the path to the resolver folded anything, so the two sides compared
 * different strings.
 *
 * <p>{@link #isResolvable(String)} therefore delegates to {@code NamespacedKey.fromString} itself
 * rather than restating its grammar. A copy of that pattern would be one more thing to drift.
 *
 * <h2>What canonicalisation does, and does not, do</h2>
 *
 * <p>{@link #canonical(String)} trims and supplies the implicit {@code minecraft:} namespace. It
 * does <strong>not</strong> fold case, because {@code NamespacedKey} rejects an upper-case key
 * rather than lower-casing it: folding here would invent an agreement the server does not have.
 * A key that survives canonicalisation and still fails {@link #isResolvable(String)} is a typo, and
 * {@link ConfigReader} rejects the document for it — see that class for the fail-closed policy and
 * why it is not a warning.
 */
public final class AdvancementKeys {

    /** The namespace {@code NamespacedKey.fromString} supplies for a key that carries none. */
    private static final String IMPLICIT_NAMESPACE = "minecraft:";

    private AdvancementKeys() {
    }

    /**
     * Puts one configured advancement key in the exact form the server will resolve it in.
     *
     * <p>Trimming is safe here <em>only</em> because this is the single normalisation site: the
     * trimmed key is what gets stored on the configuration snapshot, so it is also what
     * {@code BukkitAdvancementLookup} is later handed. #79 attempted the same trim inside
     * {@code ItemGateCompiler} alone and was reverted, because there the compiler equated two tiers
     * whose keys differed only by padding while the resolver still saw — and waived — the padded
     * one, silently ungating the material. Normalising once, at the read site, is what removes that
     * hazard rather than moving it.
     *
     * @param key a configured key, possibly {@code null}
     * @return the canonical form; {@code ""} for {@code null} or a blank key, which every caller
     *         reads as "no advancement named" rather than as a malformed one
     */
    public static String canonical(String key) {
        if (key == null) {
            return "";
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty() || trimmed.indexOf(':') >= 0) {
            return trimmed;
        }
        return IMPLICIT_NAMESPACE + trimmed;
    }

    /**
     * Whether the server's key parser accepts {@code key}, which is the only definition of usable
     * that matters: a key this returns {@code false} for resolves to
     * {@code AdvancementLookup.State#UNRESOLVABLE} at runtime, and an unresolvable advancement is
     * waived rather than enforced.
     *
     * @param key a key already put through {@link #canonical(String)}; a blank key is not resolvable
     */
    public static boolean isResolvable(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return NamespacedKey.fromString(key) != null;
    }
}
