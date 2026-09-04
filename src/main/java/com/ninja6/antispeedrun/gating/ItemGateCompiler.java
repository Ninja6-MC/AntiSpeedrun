package com.ninja6.antispeedrun.gating;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * Compiles {@code item-progression.gated-items} into an {@link ItemGateTable}.
 *
 * <h2>Precedence: most-restrictive-wins, with a named error on a genuine fork</h2>
 *
 * A material can be claimed by more than one tier — {@code NETHERITE_UPGRADE_SMITHING_TEMPLATE}
 * matches {@code NETHERITE_*} while belonging to Epic 5's trim system, and any two overlapping
 * patterns can do the same. Audit finding R-11 rejects letting map ordering decide. The rule is:
 *
 * <ol>
 *   <li><strong>An exclusion always wins, within its own tier.</strong>
 *       {@code exclude-materials} is applied after both {@code match-patterns} and {@code items},
 *       so the tier simply never claims the material and no comparison happens. This is the
 *       intended answer for a template that a gear pattern sweeps up: it is a legitimate exclusion,
 *       not a collision.</li>
 *   <li><strong>Otherwise the strictly more restrictive tier wins.</strong> Tier <em>A</em>
 *       dominates <em>B</em> when A's required advancements are a superset of B's, A's required
 *       playtime is at least B's, A's required account age is at least B's, and at least one of
 *       those is strictly greater. A player who can hold A's items can necessarily hold B's, so
 *       gating the material at A is the only choice that does not leak it.</li>
 *   <li><strong>Identical requirements are resolved by document order</strong>, earliest tier
 *       wins, with a warning. The two tiers unlock at the same instant, so the choice cannot change
 *       any player's outcome; the warning exists because the redundancy is almost always a mistake.
 *       This is the tie-break {@code PluginConfig.ItemProgression#gatedItems()} keeps document order
 *       for.</li>
 *   <li><strong>Incomparable requirements are a {@link GateCollisionException}.</strong> When each
 *       tier demands an advancement the other does not, neither dominates and there is no
 *       defensible winner. Startup fails with an error naming the material, both tiers, both
 *       requirement sets, and the {@code exclude-materials} line that resolves it.</li>
 * </ol>
 *
 * <p>Steps 2 to 4 are evaluated over the whole set of tiers claiming a material at once, never as a
 * running pairwise fold — see {@link #resolve}. Which tier wins, and whether the configuration is
 * rejected at all, are both independent of the order the tiers are written in.</p>
 *
 * <h2>Cost</h2>
 *
 * Every string operation in the whole gating feature happens here, once per configuration snapshot:
 * one pass over the cached material universe, testing each name against each tier's parsed
 * patterns. Around 1,500 materials by a handful of tiers is a few tens of thousands of
 * {@code startsWith} calls at load time, in exchange for an array read per item pickup afterwards.
 *
 * <p>{@code Material.values()} allocates a fresh array on every call, so callers pass a cached copy
 * in rather than this class calling it. {@link MaterialGates} holds that cache.
 */
public final class ItemGateCompiler {

    private ItemGateCompiler() {
    }

    /**
     * Compiles {@code tiers} against the material universe {@code universe}/{@code values}.
     *
     * @param universe the material enum class, for the {@code EnumMap}/{@code EnumSet} constructors
     * @param values   every constant of that enum; cached by the caller, never re-derived here
     * @param tiers    the configured tiers, in document order
     * @param warnings collector for recoverable problems, in the same shape the config parser uses;
     *                 the caller logs them. Never null
     * @return the compiled table
     * @throws GateCollisionException if two tiers claim one material and neither dominates
     */
    public static <M extends Enum<M>> ItemGateTable<M> compile(
            Class<M> universe,
            M[] values,
            List<PluginConfig.ItemTier> tiers,
            List<String> warnings) throws GateCollisionException {

        Objects.requireNonNull(universe, "universe");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(tiers, "tiers");
        Objects.requireNonNull(warnings, "warnings");

        Set<String> known = new HashSet<>(values.length * 2);
        for (M value : values) {
            known.add(value.name());
        }

        List<CompiledTier> compiled = new ArrayList<>(tiers.size());
        for (PluginConfig.ItemTier tier : tiers) {
            compiled.add(CompiledTier.of(tier, known, warnings));
        }

        EnumMap<M, PluginConfig.ItemTier> byMaterial = new EnumMap<>(universe);
        EnumSet<M> gated = EnumSet.noneOf(universe);

        List<PluginConfig.ItemTier> claimants = new ArrayList<>(4);
        for (M value : values) {
            String name = value.name();
            claimants.clear();
            for (CompiledTier candidate : compiled) {
                if (candidate.claims(name)) {
                    claimants.add(candidate.tier());
                }
            }
            if (claimants.isEmpty()) {
                continue;
            }
            PluginConfig.ItemTier winner =
                    claimants.size() == 1 ? claimants.get(0) : resolve(name, claimants, warnings);
            byMaterial.put(value, winner);
            gated.add(value);
        }

        for (CompiledTier tier : compiled) {
            tier.reportUnusedPatterns(warnings);
        }

        return new ItemGateTable<>(gated, byMaterial);
    }

    /**
     * Applies the precedence rule to every tier that claims {@code materialName}.
     *
     * <p>Deliberately <strong>not</strong> a pairwise fold. Folding picks a running winner and
     * compares the next claimant against it, which makes the outcome depend on declaration order as
     * soon as three tiers overlap: with {@code B} and {@code C} incomparable and {@code A}
     * dominating both, {@code A, B, C} folds cleanly to {@code A} while {@code B, C, A} throws on
     * the first pair and never consults {@code A} at all. Same tiers, same requirements, different
     * line order, different answer — which is exactly what #55 asks not to happen.
     *
     * <p>Instead the whole claimant set is considered at once and the unique maximum of the
     * dominance order is selected: the tier that dominates or equals every other claimant. That is
     * order-independent by construction. It is quadratic in the number of tiers claiming one
     * material, which is one or two in practice and runs once per material at load time.
     *
     * @return the tier that owns the material
     * @throws GateCollisionException when no claimant dominates or equals all the others
     */
    private static PluginConfig.ItemTier resolve(String materialName,
                                                 List<PluginConfig.ItemTier> claimants,
                                                 List<String> warnings) throws GateCollisionException {
        int size = claimants.size();
        List<Set<String>> advancements = new ArrayList<>(size);
        for (PluginConfig.ItemTier claimant : claimants) {
            advancements.add(new LinkedHashSet<>(claimant.requireAdvancements()));
        }

        // A maximum dominates or equals every other claimant. Maxima can only be tied with one
        // another -- dominance is antisymmetric, so a tier another tier dominates is never itself a
        // maximum -- which is why document order is a safe tie-break among them and only among them.
        List<Integer> maxima = new ArrayList<>(2);
        for (int i = 0; i < size; i++) {
            boolean beatsEveryone = true;
            for (int j = 0; j < size && beatsEveryone; j++) {
                if (i == j) {
                    continue;
                }
                beatsEveryone = dominates(claimants.get(i), advancements.get(i),
                        claimants.get(j), advancements.get(j))
                        || same(claimants.get(i), advancements.get(i),
                        claimants.get(j), advancements.get(j));
            }
            if (beatsEveryone) {
                maxima.add(i);
            }
        }

        if (maxima.isEmpty()) {
            throw collision(materialName, claimants, advancements);
        }

        int winner = maxima.get(0);
        if (maxima.size() > 1) {
            warnings.add("item-progression.gated-items: " + materialName + " is gated by both \""
                    + claimants.get(winner).id() + "\" and \""
                    + claimants.get(maxima.get(1)).id()
                    + "\", which have identical requirements; \"" + claimants.get(winner).id()
                    + "\" owns it because it is declared first. Remove the duplicate, or exclude "
                    + materialName + " from one of them.");
        }
        return claimants.get(winner);
    }

    /** Names the first genuinely incomparable pair, for an error a reader can act on. */
    private static GateCollisionException collision(String materialName,
                                                    List<PluginConfig.ItemTier> claimants,
                                                    List<Set<String>> advancements) {
        for (int i = 0; i < claimants.size(); i++) {
            for (int j = i + 1; j < claimants.size(); j++) {
                boolean comparable =
                        dominates(claimants.get(i), advancements.get(i), claimants.get(j), advancements.get(j))
                                || dominates(claimants.get(j), advancements.get(j), claimants.get(i), advancements.get(i))
                                || same(claimants.get(i), advancements.get(i), claimants.get(j), advancements.get(j));
                if (!comparable) {
                    return new GateCollisionException(materialName, claimants.get(i), claimants.get(j));
                }
            }
        }
        // Unreachable: a set with no maximum must contain an incomparable pair.
        return new GateCollisionException(materialName, claimants.get(0), claimants.get(1));
    }

    /** Whether two tiers unlock at exactly the same moment, so the choice between them is free. */
    private static boolean same(PluginConfig.ItemTier a, Set<String> advancementsOfA,
                                PluginConfig.ItemTier b, Set<String> advancementsOfB) {
        return advancementsOfA.equals(advancementsOfB)
                && a.requirePlaytimeHours() == b.requirePlaytimeHours()
                && a.requireAccountAgeDays() == b.requireAccountAgeDays();
    }

    /** Whether {@code a} demands everything {@code b} does, and strictly more of something. */
    private static boolean dominates(PluginConfig.ItemTier a, Set<String> advancementsOfA,
                                     PluginConfig.ItemTier b, Set<String> advancementsOfB) {
        if (!advancementsOfA.containsAll(advancementsOfB)) {
            return false;
        }
        if (a.requirePlaytimeHours() < b.requirePlaytimeHours()) {
            return false;
        }
        if (a.requireAccountAgeDays() < b.requireAccountAgeDays()) {
            return false;
        }
        return advancementsOfA.size() > advancementsOfB.size()
                || a.requirePlaytimeHours() > b.requirePlaytimeHours()
                || a.requireAccountAgeDays() > b.requireAccountAgeDays();
    }

    /**
     * One tier with its patterns parsed and its name lists folded to upper case, ready to be tested
     * against a material name.
     */
    private static final class CompiledTier {

        private final PluginConfig.ItemTier tier;
        private final List<MaterialPattern> patterns;
        private final Set<String> items;
        private final Set<String> excluded;
        private final boolean[] patternMatchedSomething;

        private CompiledTier(PluginConfig.ItemTier tier, List<MaterialPattern> patterns,
                             Set<String> items, Set<String> excluded) {
            this.tier = tier;
            this.patterns = patterns;
            this.items = items;
            this.excluded = excluded;
            this.patternMatchedSomething = new boolean[patterns.size()];
        }

        static CompiledTier of(PluginConfig.ItemTier tier, Set<String> known, List<String> warnings) {
            String path = "item-progression.gated-items." + tier.id();

            List<MaterialPattern> patterns = new ArrayList<>(tier.matchPatterns().size());
            for (String raw : tier.matchPatterns()) {
                MaterialPattern parsed = MaterialPattern.parse(raw);
                if (parsed == null) {
                    warnings.add(path + ".match-patterns: \"" + raw + "\" is not a usable pattern. "
                            + "A wildcard is only meaningful at the start (*_IRON_ORE), the end "
                            + "(IRON_*) or both (*_DIAMOND_*); this entry was ignored.");
                    continue;
                }
                if (parsed.mode() == MaterialPattern.Mode.ALL) {
                    warnings.add(path + ".match-patterns: \"" + raw
                            + "\" gates every material in the game. That is almost certainly not "
                            + "what was meant, but it was applied as written.");
                }
                patterns.add(parsed);
            }

            Set<String> items = names(tier.items(), known, path + ".items", warnings);
            Set<String> excluded =
                    names(tier.excludeMaterials(), known, path + ".exclude-materials", warnings);

            for (String name : items) {
                if (excluded.contains(name)) {
                    warnings.add(path + ": " + name + " is in both items and exclude-materials; "
                            + "the exclusion wins, so this tier does not gate it.");
                }
            }
            return new CompiledTier(tier, patterns, items, excluded);
        }

        /**
         * Folds a configured name list to upper case, warning about any entry that is not a real
         * material.
         *
         * <p>An entry that <em>is</em> a real material but that this tier never matches is left
         * alone deliberately: {@code CHAIN} and {@code LANTERN} are exactly that in the shipped
         * file — defensive exclusions that keep working if the patterns around them widen later.
         * A name that resolves to nothing at all is a different thing, usually a typo or a
         * material from a different game version, and it is worth saying so.
         */
        private static Set<String> names(List<String> raw, Set<String> known, String path,
                                         List<String> warnings) {
            Set<String> resolved = new LinkedHashSet<>(raw.size() * 2);
            for (String entry : raw) {
                String name = entry.trim().toUpperCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                if (!known.contains(name)) {
                    warnings.add(path + ": \"" + entry + "\" is not a material this server knows "
                            + "about and was ignored. Check the spelling, or the Minecraft version "
                            + "it was added in.");
                    continue;
                }
                resolved.add(name);
            }
            return resolved;
        }

        PluginConfig.ItemTier tier() {
            return tier;
        }

        /**
         * Whether this tier gates {@code name}. Patterns and {@code items} are unioned first and
         * {@code exclude-materials} is applied last, so an exclusion wins outright over both.
         *
         * <p>A pattern is recorded as having matched even when the exclusion then removes the
         * material, so that a pattern whose every hit is deliberately excluded is not reported as
         * dead.
         */
        boolean claims(String name) {
            boolean matched = items.contains(name);
            for (int i = 0; i < patterns.size(); i++) {
                if (patterns.get(i).matches(name)) {
                    patternMatchedSomething[i] = true;
                    matched = true;
                }
            }
            return matched && !excluded.contains(name);
        }

        /**
         * Warns about a pattern that matched no material at all — a dead config line, and the way a
         * renamed material shows up after a Minecraft update.
         */
        void reportUnusedPatterns(List<String> warnings) {
            for (int i = 0; i < patterns.size(); i++) {
                if (!patternMatchedSomething[i]) {
                    warnings.add("item-progression.gated-items." + tier.id()
                            + ".match-patterns: \"" + patterns.get(i).source()
                            + "\" matched no material and gates nothing.");
                }
            }
        }
    }
}
