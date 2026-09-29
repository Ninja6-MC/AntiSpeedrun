package com.ninja6.antispeedrun.progression;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimPattern;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PluginConfig.TrimProgression;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;

/**
 * Maps armor trim patterns and smithing templates to the structure a player must have explored
 * before using them (Epic 5, #36).
 *
 * <p>Three structures are gated, each by one vanilla advancement:
 *
 * <table>
 *   <caption>Gated structures</caption>
 *   <tr><th>Structure</th><th>Trim patterns</th><th>Other templates</th><th>Advancement</th></tr>
 *   <tr><td>Ancient City</td><td>{@code silence}, {@code ward}</td><td></td>
 *       <td>{@value #ANCIENT_CITY_ADVANCEMENT}</td></tr>
 *   <tr><td>Bastion Remnant</td><td>{@code snout}</td><td>{@code NETHERITE_UPGRADE_SMITHING_TEMPLATE}</td>
 *       <td>{@value #BASTION_ADVANCEMENT}</td></tr>
 *   <tr><td>End City</td><td>{@code spire}</td><td></td>
 *       <td>{@value #END_CITY_ADVANCEMENT}</td></tr>
 * </table>
 *
 * <p>Every other pattern resolves to nothing and is not gated. Vanilla has no advancement for
 * entering an Ancient City, so that structure is proven by the nearest thing it does have:
 * <em>Sneak 100</em>, sneaking past a Sculk Sensor or Warden. Both occur naturally only in the Deep
 * Dark. <em>It Spreads</em> was the alternative and is worse: it needs a kill beside a Sculk
 * Catalyst, and hostile mobs do not spawn in the Deep Dark.
 *
 * <p>The requirements are constants rather than configured keys, the same position
 * {@link Milestone#EARLY_EYE_ADVANCEMENT} takes: section 3 of {@code config.yml} configures which
 * locks are on, not what they require.
 *
 * <p>{@code NETHERITE_UPGRADE_SMITHING_TEMPLATE} is named explicitly. {@code netherite-tier} in
 * section 2 excludes it so that this class owns it, and no {@code *_ARMOR_TRIM_SMITHING_TEMPLATE}
 * rule covers it, because it applies no trim.
 *
 * <h2>Using it</h2>
 *
 * The lookups — {@link #forPattern}, {@link #forTrim}, {@link #forTemplate} — are pure and safe
 * from any thread. They answer <em>which</em> milestone gates something, independent of
 * configuration; the listener decides whether its own lock ({@code block-unearned-smithing},
 * {@code block-wearing-unearned-trims}, {@code block-unearned-template-duplication}) is on, and
 * asks {@link #evaluate} only if it is. {@link #evaluate} carries the {@link ProgressionManager}
 * threading rule: call it from a context that owns the player.
 */
public final class TrimProgressionManager {

    /** Sneak 100: the Ancient City proxy. See the class javadoc for why. */
    public static final String ANCIENT_CITY_ADVANCEMENT = "minecraft:adventure/avoid_vibration";

    /** Those Were the Days: enter a Bastion Remnant. */
    public static final String BASTION_ADVANCEMENT = "minecraft:nether/find_bastion";

    /** The City at the End of the Game: enter an End City. */
    public static final String END_CITY_ADVANCEMENT = "minecraft:end/find_end_city";

    /** Prefix of every {@link Milestone#id()} this class issues. */
    public static final String ID_PREFIX = "trim:";

    /** Milestone gating the Ancient City trims. */
    public static final Milestone ANCIENT_CITY =
            structure("ancient_city", "Ancient City", ANCIENT_CITY_ADVANCEMENT);

    /** Milestone gating the Bastion trim and the netherite upgrade template. */
    public static final Milestone BASTION = structure("bastion", "Bastion Remnant", BASTION_ADVANCEMENT);

    /** Milestone gating the End City trim. */
    public static final Milestone END_CITY = structure("end_city", "End City", END_CITY_ADVANCEMENT);

    /** Every structure milestone, in table order. */
    public static final List<Milestone> STRUCTURES = List.of(ANCIENT_CITY, BASTION, END_CITY);

    /** Material-name suffix shared by every armor trim template, whose prefix is the pattern key. */
    static final String TRIM_TEMPLATE_SUFFIX = "_ARMOR_TRIM_SMITHING_TEMPLATE";

    /** The one gated template that applies no trim. */
    static final String NETHERITE_UPGRADE_TEMPLATE = "NETHERITE_UPGRADE_SMITHING_TEMPLATE";

    /** Trim pattern registry key to milestone. */
    private static final Map<NamespacedKey, Milestone> PATTERNS = Map.of(
            NamespacedKey.minecraft("silence"), ANCIENT_CITY,
            NamespacedKey.minecraft("ward"), ANCIENT_CITY,
            NamespacedKey.minecraft("snout"), BASTION,
            NamespacedKey.minecraft("spire"), END_CITY);

    private final ProgressionManager progression;

    /**
     * @param progression the service every gate evaluates through, so trim checks share its
     *                    snapshot cache
     */
    public TrimProgressionManager(ProgressionManager progression) {
        this.progression = Objects.requireNonNull(progression, "progression");
    }

    private static Milestone structure(String id, String displayName, String advancement) {
        return new Milestone(ID_PREFIX + id, displayName,
                new MilestoneRequirement(List.of(advancement), 0.0D, 0));
    }

    // -------------------------------------------------------------------------------------------
    // Lookups
    // -------------------------------------------------------------------------------------------

    /**
     * The milestone gating a trim pattern, by its key in the server's trim pattern registry.
     *
     * <p>{@code TrimPattern#getKey} is deprecated for removal, so the key comes from the registry.
     * A pattern the registry does not know has no vanilla key and is not gated. Needs a running
     * server; {@link #forPattern(NamespacedKey)} is the same answer without one.
     *
     * @return the structure milestone, or empty when the pattern is not gated
     */
    public static Optional<Milestone> forPattern(TrimPattern pattern) {
        Objects.requireNonNull(pattern, "pattern");
        NamespacedKey key = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN).getKey(pattern);
        return key == null ? Optional.empty() : forPattern(key);
    }

    /**
     * The milestone gating the trim pattern with this registry key. Datapack patterns outside the
     * {@code minecraft} namespace are never gated.
     */
    public static Optional<Milestone> forPattern(NamespacedKey pattern) {
        return Optional.ofNullable(PATTERNS.get(Objects.requireNonNull(pattern, "pattern")));
    }

    /** The milestone gating an applied trim, decided by its pattern alone; the material is free. */
    public static Optional<Milestone> forTrim(ArmorTrim trim) {
        return forPattern(Objects.requireNonNull(trim, "trim").getPattern());
    }

    /**
     * The milestone gating a smithing template item: an armor trim template by the pattern it
     * applies, or {@code NETHERITE_UPGRADE_SMITHING_TEMPLATE}. Any other material is empty.
     */
    public static Optional<Milestone> forTemplate(Material material) {
        return forTemplate(Objects.requireNonNull(material, "material").name());
    }

    /**
     * {@link #forTemplate(Material)} by material name, which is what the mapping keys on: every
     * vanilla trim template is named {@code <PATTERN>_ARMOR_TRIM_SMITHING_TEMPLATE}.
     */
    static Optional<Milestone> forTemplate(String materialName) {
        Objects.requireNonNull(materialName, "materialName");
        if (materialName.equals(NETHERITE_UPGRADE_TEMPLATE)) {
            return Optional.of(BASTION);
        }
        if (!materialName.endsWith(TRIM_TEMPLATE_SUFFIX)) {
            return Optional.empty();
        }
        String pattern = materialName
                .substring(0, materialName.length() - TRIM_TEMPLATE_SUFFIX.length())
                .toLowerCase(Locale.ROOT);
        return pattern.isEmpty() ? Optional.empty() : forPattern(NamespacedKey.minecraft(pattern));
    }

    // -------------------------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------------------------

    /**
     * Whether section 3 gates anything at all: {@code enabled} and at least one lock on.
     *
     * <p>The single place that decision is made, so {@link #requiredAdvancements} and
     * {@link #evaluate} cannot disagree about it.
     */
    public static boolean isActive(PluginConfig config) {
        TrimProgression trims = Objects.requireNonNull(config, "config").trimProgression();
        return trims.enabled()
                && (trims.gateNaturalTrimChests()
                        || trims.blockUnearnedTemplateDuplication()
                        || trims.blockUnearnedSmithing()
                        || trims.blockWearingUnearnedTrims());
    }

    /**
     * The advancements trim gating requires, for {@link Milestone#allRequiredAdvancements}.
     *
     * <p>Empty while {@link #isActive} is false, so a server with section 3 off does not pay to
     * capture them. While it is on they must be captured: a key the capture never queried would be
     * waived as uncovered on every check.
     */
    public static Set<String> requiredAdvancements(PluginConfig config) {
        if (!isActive(config)) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (Milestone structure : STRUCTURES) {
            keys.addAll(structure.requirement().advancements());
        }
        return keys;
    }

    // -------------------------------------------------------------------------------------------
    // Evaluation
    // -------------------------------------------------------------------------------------------

    /**
     * Whether {@code player} has explored the structure {@code milestone} stands for.
     *
     * <p>Passes unconditionally while {@link #isActive} is false. Bypass permissions are the
     * caller's to check, as they are for every other gate.
     *
     * @param player    the player; must be owned by the calling thread's region
     * @param config    the configuration snapshot the caller is already holding
     * @param milestone one of {@link #STRUCTURES}, as returned by a lookup
     */
    public EligibilityResult evaluate(Player player, PluginConfig config, Milestone milestone) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(milestone, "milestone");
        if (!isActive(config)) {
            return EligibilityResult.pass();
        }
        return progression.evaluate(player, config, milestone);
    }

    /**
     * Whether {@code player} may use {@code pattern}: ungated patterns always pass.
     *
     * @see #evaluate(Player, PluginConfig, Milestone)
     */
    public EligibilityResult evaluate(Player player, PluginConfig config, TrimPattern pattern) {
        Optional<Milestone> milestone = forPattern(pattern);
        return milestone.isPresent() ? evaluate(player, config, milestone.get()) : EligibilityResult.pass();
    }

    /**
     * Whether {@code player} may use the smithing template {@code material}: anything that is not a
     * gated template passes.
     *
     * @see #evaluate(Player, PluginConfig, Milestone)
     */
    public EligibilityResult evaluate(Player player, PluginConfig config, Material material) {
        Optional<Milestone> milestone = forTemplate(material);
        return milestone.isPresent() ? evaluate(player, config, milestone.get()) : EligibilityResult.pass();
    }
}
