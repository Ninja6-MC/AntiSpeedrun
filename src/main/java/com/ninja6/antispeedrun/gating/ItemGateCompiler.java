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
 * <h2>Requirement identity</h2>
 *
 * Steps 2 to 4 all rest on deciding whether two requirement sets are the same, so identity has to
 * mean the same thing here as it does at the point the requirement is actually evaluated:
 *
 * <ul>
 *   <li><strong>Advancement keys are normalised the way the runtime resolver normalises them</strong>
 *       — trimmed, and given the implicit {@code minecraft:} namespace when they carry none. That is
 *       exactly what {@code NamespacedKey.fromString} does, so {@code story/smelt_iron} and
 *       {@code minecraft:story/smelt_iron} name one advancement to the server and must name one
 *       requirement here. Before, a purely cosmetic difference between two tiers turned a clean
 *       dominance into an incomparable pair, and since the collision became fatal, into a refusal
 *       to start. Case is deliberately <em>not</em> folded: {@code NamespacedKey} rejects an
 *       upper-case key outright, so {@code MINECRAFT:STORY/X} resolves to nothing on the server and
 *       is a genuinely different — unsatisfiable — requirement from the lower-case form, not a
 *       spelling of it. See {@link #normalise}.</li>
 *   <li><strong>A {@code NaN} playtime is neutralised to zero, with a warning.</strong> It is
 *       reachable: {@code require-playtime-hours} is read with the plain {@code decimal} reader, and
 *       YAML spells a floating-point NaN {@code .nan}, which SnakeYAML resolves to
 *       {@link Double#NaN}. Left alone it makes {@code same} false and dominance false in
 *       <em>both</em> directions, so a tier would collide with an identical copy of itself and the
 *       error would name two requirement sets that print the same. Infinities need no special case:
 *       they order normally against every other value.</li>
 * </ul>
 *
 * <h2>Why a rule the plugin cannot interpret warns, while an ambiguous one is fatal</h2>
 *
 * An interior wildcard ({@code IRON_*_ORE}), and a material name no server knows, are dropped with a
 * warning naming the line. A tier collision stops the plugin. All three end with items ungated, so
 * the asymmetry is worth stating rather than leaving to be rediscovered.
 *
 * <p>The distinction is not how many items end up ungated, it is <em>whether the operator can tell
 * which, and why, from the log</em>:
 *
 * <ul>
 *   <li>A dropped rule has a correct running state. Exactly one named configuration line has no
 *       effect, the log says which line and why, and everything else gates as written. That is the
 *       contract the rest of this plugin's configuration already keeps — {@code ConfigReader} falls
 *       back with a warning for a wrong-typed boolean, an out-of-range integer, an unknown enum
 *       constant and a non-string list element, and {@code onEnable} deliberately degrades a
 *       config.yml that will not parse at all to the shipped defaults rather than refusing to
 *       start. Making two gating typos fatal would make this one section the only place where a
 *       typo takes the server down.</li>
 *   <li>A collision has no correct running state. Either the plugin picks a tier that document
 *       order alone chose — audit finding R-11, the thing the check exists to prevent — or it
 *       leaves the material ungated while the file plainly says it should be gated twice. Nor can
 *       the log stand in for the fix: the message can name the two tiers, but not which of the
 *       materials they both claim were misassigned.</li>
 *   <li>The blast radii differ, and in the direction that settles it. A malformed pattern is a
 *       fixed property of the file: wrong on every server, forever, until it is edited. An unknown
 *       material name is a property of the <em>server</em> — the same file is valid on one
 *       Minecraft version and not on the next. Making it fatal would mean a version bump that
 *       renames or removes one constant turns "these items are no longer gated, and here is the
 *       name that vanished" into "the server does not start", precisely at the moment the operator
 *       has least warning. And in {@code exclude-materials} an unrecognised name removes nothing,
 *       so the tier gates <em>more</em> than intended, not less: the safe direction.</li>
 * </ul>
 *
 * <p>What that argument owes in return is that the log has to be worth trusting, so a dropped rule
 * says what it costs rather than only what was wrong with it, and a final line counts the rules
 * that are not in effect — see {@link #reportDroppedRules}. The alternative to revisit, if
 * operators still miss it, is a configurable {@code on-invalid-rule: warn|fail} rather than a
 * blanket promotion.
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

    /**
     * Joins two tier ids into the key that dedupes the identical-requirements warning. A tier id is
     * a YAML mapping key and may contain a space, so a space would conflate two different pairs and
     * silently swallow one of the two warnings; nothing YAML can put in a key collides with this.
     */
    private static final char TIE_KEY_SEPARATOR = 0;

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

        // Rules the plugin could not interpret and therefore did not apply. Counted rather than
        // thrown, for the reasons in the class documentation, and reported once at the end so the
        // cost of the individual warnings above is not left to be added up by hand.
        int[] droppedRules = new int[1];

        List<CompiledTier> compiled = new ArrayList<>(tiers.size());
        for (PluginConfig.ItemTier tier : tiers) {
            compiled.add(CompiledTier.of(tier, known, warnings, droppedRules));
        }

        EnumMap<M, PluginConfig.ItemTier> byMaterial = new EnumMap<>(universe);
        EnumSet<M> gated = EnumSet.noneOf(universe);

        // One entry per pair of tiers already reported as having identical requirements. The
        // warning describes the pair, not the material, so a broad overlap used to emit the same
        // line once per material -- hundreds of Logger#warning calls saying one thing.
        Set<String> reportedTies = new HashSet<>();

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
            PluginConfig.ItemTier winner = claimants.size() == 1
                    ? claimants.get(0)
                    : resolve(name, claimants, warnings, reportedTies);
            byMaterial.put(value, winner);
            gated.add(value);
        }

        for (CompiledTier tier : compiled) {
            tier.reportUnusedPatterns(warnings);
        }
        reportDroppedRules(droppedRules[0], warnings);

        return new ItemGateTable<>(gated, byMaterial);
    }

    /**
     * Closes the warning list with what the dropped rules add up to, when there were any.
     *
     * <p>The individual warnings each name one line; this one names the consequence, because the
     * decision to keep them recoverable is only defensible if an operator scanning the log can see
     * that part of the gating configuration is not running. See the class documentation.
     */
    private static void reportDroppedRules(int dropped, List<String> warnings) {
        if (dropped == 0) {
            return;
        }
        warnings.add("item-progression.gated-items: " + dropped + " gating rule"
                + (dropped == 1 ? " was" : "s were")
                + " dropped and " + (dropped == 1 ? "is" : "are") + " NOT in effect, so the items "
                + (dropped == 1 ? "it names are" : "they name are")
                + " ungated. Every other rule was applied as written. Fix the lines named above "
                + "and reload; the plugin does not stop for these, because a dropped rule is one "
                + "named line with no effect, where a tier collision has no correct answer at all.");
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
     * @param reportedTies tier-id pairs already warned about, so the identical-requirements warning
     *                     describes each pair once rather than once per material they both claim
     * @return the tier that owns the material
     * @throws GateCollisionException when no claimant dominates or equals all the others
     */
    private static PluginConfig.ItemTier resolve(String materialName,
                                                 List<PluginConfig.ItemTier> claimants,
                                                 List<String> warnings,
                                                 Set<String> reportedTies) throws GateCollisionException {
        int size = claimants.size();
        List<Set<String>> advancements = new ArrayList<>(size);
        for (PluginConfig.ItemTier claimant : claimants) {
            advancements.add(normalise(claimant.requireAdvancements()));
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
            String owner = claimants.get(winner).id();
            String other = claimants.get(maxima.get(1)).id();
            // Keyed on the pair, not the material: the problem is a duplicated tier, and it is the
            // same problem for every material the two of them both claim. A broad overlapping
            // pattern otherwise emits this line hundreds of times.
            if (reportedTies.add(owner + TIE_KEY_SEPARATOR + other)) {
                warnings.add("item-progression.gated-items: \"" + owner + "\" and \"" + other
                        + "\" have identical requirements and both claim the same materials"
                        + " (first seen on " + materialName + "); \"" + owner
                        + "\" owns it because it is declared first, and the same applies to every"
                        + " other material they share. Remove the duplicate tier, or exclude the"
                        + " overlapping materials from one of them.");
            }
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
                && playtimeOf(a) == playtimeOf(b)
                && a.requireAccountAgeDays() == b.requireAccountAgeDays();
    }

    /** Whether {@code a} demands everything {@code b} does, and strictly more of something. */
    private static boolean dominates(PluginConfig.ItemTier a, Set<String> advancementsOfA,
                                     PluginConfig.ItemTier b, Set<String> advancementsOfB) {
        if (!advancementsOfA.containsAll(advancementsOfB)) {
            return false;
        }
        double playtimeOfA = playtimeOf(a);
        double playtimeOfB = playtimeOf(b);
        if (playtimeOfA < playtimeOfB) {
            return false;
        }
        if (a.requireAccountAgeDays() < b.requireAccountAgeDays()) {
            return false;
        }
        return advancementsOfA.size() > advancementsOfB.size()
                || playtimeOfA > playtimeOfB
                || a.requireAccountAgeDays() > b.requireAccountAgeDays();
    }

    /**
     * The tier's playtime requirement as an orderable number.
     *
     * <p>Only {@code NaN} needs the treatment, and it collapses to zero — "no playtime required",
     * which is what {@code require-playtime-hours} defaults to and the only reading of a non-number
     * that cannot lock players out of a tier. Every comparison in {@link #same} and
     * {@link #dominates} is otherwise a plain {@code double} comparison, and {@code NaN} is false
     * against everything including itself: a tier would fail to equal a byte-identical copy of
     * itself and fail to dominate it in either direction, producing a {@link GateCollisionException}
     * whose message prints two requirement sets that read the same. Infinities are left alone; they
     * order normally, and {@code require-playtime-hours: .inf} is an unreachable tier the operator
     * asked for rather than a broken comparison. {@link CompiledTier#of} warns about both.
     */
    private static double playtimeOf(PluginConfig.ItemTier tier) {
        double hours = tier.requirePlaytimeHours();
        return Double.isNaN(hours) ? 0.0D : hours;
    }

    /**
     * Normalises a tier's advancement keys for comparison, preserving configured order.
     *
     * <p>Duplicates within one tier collapse, which is correct: requiring one advancement twice is
     * requiring it once, and leaving them distinct would make the set-size test in
     * {@link #dominates} count a repetition as extra strictness.
     */
    private static Set<String> normalise(List<String> advancements) {
        Set<String> normalised = new LinkedHashSet<>(advancements.size() * 2);
        for (String key : advancements) {
            normalised.add(normalise(key));
        }
        return normalised;
    }

    /**
     * Puts one advancement key in the form the server will resolve it in.
     *
     * <p>Trim, then supply the implicit {@code minecraft:} namespace — the same two steps
     * {@code NamespacedKey.fromString} takes, which is what {@code BukkitAdvancementLookup} hands
     * the raw configured string to. Matching that resolver exactly is the whole point: two keys are
     * one requirement here precisely when they are one advancement there.
     *
     * <p>Case is left alone deliberately. {@code NamespacedKey} rejects an upper-case key rather
     * than folding it, so {@code MINECRAFT:STORY/X} resolves to no advancement at all; folding it
     * here would declare it identical to a requirement players can actually earn.
     */
    private static String normalise(String advancementKey) {
        if (advancementKey == null) {
            return "";
        }
        String trimmed = advancementKey.trim();
        if (trimmed.isEmpty() || trimmed.indexOf(':') >= 0) {
            return trimmed;
        }
        return "minecraft:" + trimmed;
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

        static CompiledTier of(PluginConfig.ItemTier tier, Set<String> known, List<String> warnings,
                               int[] droppedRules) {
            String path = "item-progression.gated-items." + tier.id();

            double hours = tier.requirePlaytimeHours();
            if (Double.isNaN(hours)) {
                // Reachable from a hand-edited file: YAML spells NaN ".nan", the plain decimal
                // reader accepts any Number, and NaN then compares false against everything --
                // including an identical tier -- which reads as an unexplainable collision.
                warnings.add(path + ".require-playtime-hours: \"" + hours
                        + "\" is not a number, so this tier requires no playtime. Set a value in "
                        + "hours, or remove the line.");
            } else if (Double.isInfinite(hours)) {
                warnings.add(path + ".require-playtime-hours: " + hours
                        + " can never be reached, so no player will ever hold this tier's items. "
                        + "It was applied as written.");
            }

            List<MaterialPattern> patterns = new ArrayList<>(tier.matchPatterns().size());
            for (String raw : tier.matchPatterns()) {
                MaterialPattern parsed = MaterialPattern.parse(raw);
                if (parsed == null) {
                    droppedRules[0]++;
                    warnings.add(path + ".match-patterns: \"" + raw + "\" is not a usable pattern. "
                            + "A wildcard is only meaningful at the start (*_IRON_ORE), the end "
                            + "(IRON_*) or both (*_DIAMOND_*); this entry was ignored, so nothing "
                            + "it would have gated is gated by this tier.");
                    continue;
                }
                if (parsed.mode() == MaterialPattern.Mode.ALL) {
                    warnings.add(path + ".match-patterns: \"" + raw
                            + "\" gates every material in the game. That is almost certainly not "
                            + "what was meant, but it was applied as written.");
                }
                patterns.add(parsed);
            }

            Set<String> items = names(tier.items(), known, path + ".items",
                    "this tier does not gate it", warnings, droppedRules);
            // An unrecognised exclusion removes nothing, so the tier gates more than intended
            // rather than less. Still worth naming, but it is not a dropped gating rule: the
            // failure direction is the safe one.
            Set<String> excluded = names(tier.excludeMaterials(), known, path + ".exclude-materials",
                    "this tier's exclusion has no effect and it may gate more than intended",
                    warnings, null);

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
         *
         * @param consequence what the operator loses by the entry being dropped, appended to the
         *                    warning; it differs between {@code items} and {@code exclude-materials}
         * @param droppedRules counter of rules that reduce gating, or {@code null} for a list whose
         *                     dropped entries can only over-gate
         */
        private static Set<String> names(List<String> raw, Set<String> known, String path,
                                         String consequence, List<String> warnings,
                                         int[] droppedRules) {
            Set<String> resolved = new LinkedHashSet<>(raw.size() * 2);
            for (String entry : raw) {
                String name = entry.trim().toUpperCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                if (!known.contains(name)) {
                    if (droppedRules != null) {
                        droppedRules[0]++;
                    }
                    warnings.add(path + ": \"" + entry + "\" is not a material this server knows "
                            + "about and was ignored, so " + consequence + ". Check the spelling, "
                            + "or the Minecraft version it was added in.");
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
