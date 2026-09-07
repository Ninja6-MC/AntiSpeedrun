package com.ninja6.antispeedrun.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Typed reads against one {@link ConfigSection}, with the fallback policy applied in exactly one
 * place.
 *
 * <p>The policy, which the whole model relies on:
 * <ul>
 *   <li>an <strong>absent</strong> key falls back to the shipped default, silently;</li>
 *   <li>a key present with the <strong>wrong type</strong> falls back to the shipped default and
 *       records a warning naming the full key path, what was expected and what was found;</li>
 *   <li>an <strong>unknown</strong> key is ignored and records a warning, so a typo in a key name
 *       is visible rather than silently doing nothing.</li>
 * </ul>
 *
 * <h2>The one exception: an advancement key the server cannot resolve is fatal</h2>
 *
 * <p>{@link #advancementKey} and {@link #advancementKeys} do not fall back. A key that is still not
 * resolvable after {@link AdvancementKeys#canonical normalisation} throws {@link ConfigLoadException},
 * and so does a requirement list that is written with entries but has none left once the ones naming
 * no advancement are dropped — whether they were blank, or written with no value at all. Either
 * rejects the whole document and nothing is published. <strong>This is
 * deliberate and it is the fail-closed choice</strong>, decided on #83 against the alternative of
 * dropping the requirement with a warning.
 *
 * <p><strong>What "rejected" costs differs between a reload and a boot, and the difference matters.
 * </strong> On {@code /asr reload} the previously loaded configuration and everything derived from
 * it stay live, so nothing is disarmed and the rule is closed end to end. At {@code onEnable} there
 * is no previous configuration: {@code AntiSpeedrunPlugin} starts on {@link PluginConfig#defaults()}
 * and its {@code CONFIG_REJECTED} arm leaves it there, logging loudly. Those defaults keep the
 * dimension gates armed on their shipped keys, but they declare <em>no item tiers</em>, so item
 * gating is off until the file is fixed. That is an amplification of one typo and it is not what
 * this policy wants; turning that arm into a refusal to start is a separate change against
 * {@code AntiSpeedrunPlugin}, and it collides with audit finding R-11's deliberate decision that a
 * malformed {@code config.yml} must not stop the server. That collision is #91, which carries the
 * options and is the maintainer's call. Until it is settled, the honest statement is the one above
 * rather than "the previous configuration stays live", which is true only of a reload.
 *
 * <p>The reasoning, so it is not re-argued: an unresolvable advancement is not a strict requirement,
 * it is <em>no</em> requirement. {@code BukkitAdvancementLookup} returns {@code UNRESOLVABLE} for a
 * key {@code NamespacedKey.fromString} rejects, and {@code MilestoneEvaluator} waives an
 * unresolvable requirement rather than blocking on it forever. So one typo in
 * {@code dimension-gates.the_end.require-advancements} would let any player walk into the End while
 * the gate reported nothing wrong — the requirement silently waived, the gate still "enabled" in the
 * file and in {@code /asr status}. Every other recoverable problem in this file leaves a running
 * state an operator can read off the log; this one leaves a gate that says it is armed and is not.
 * Refusing the document is the cheaper failure, and it is the same fatal-vs-warn line #79 drew for
 * a tier collision: a warning is right when exactly one named line has no effect, an error is right
 * when there is no correct running state at all.
 *
 * <p>Two things are <em>not</em> covered by that rule, on purpose. A <strong>blank single</strong>
 * key names no advancement rather than misspelling one — an operator clearing
 * {@code villager-progression.required-advancement} is saying "gate the trade, require no
 * advancement" — so it reads as absent. Note the exemption is about what survives, not about the
 * blank itself: an entry naming no advancement inside a <em>list</em> is dropped with a warning while
 * a usable entry remains beside it, and is fatal when none does, because a list that empties itself
 * leaves exactly the armed-but-permissive gate this policy exists to prevent. And a well-formed key
 * that names no
 * advancement <em>on this server</em> stays a runtime warning: that is a property of the server's
 * version and datapacks, not of the file, and making a datapack change refuse to start is the
 * failure mode #79 rejected.
 *
 * <p>Package-private on purpose: it is parsing scaffolding, not part of the configuration
 * contract that the rest of the plugin reads.
 */
final class ConfigReader {

    private final ConfigSection section;
    private final String path;
    private final List<String> warnings;

    ConfigReader(ConfigSection section, String path, List<String> warnings) {
        this.section = section == null ? MapConfigSection.EMPTY : section;
        this.path = path;
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    /** A reader for the child mapping at {@code key}; never null, absent children read as empty. */
    ConfigReader child(String key) {
        ConfigSection child = section.section(key);
        if (child == null && section.contains(key) && section.get(key) != null) {
            warn(key, "a section", section.get(key));
        }
        return new ConfigReader(child, qualify(key), warnings);
    }

    String string(String key, String def) {
        Object raw = section.get(key);
        if (raw == null) {
            return def;
        }
        if (raw instanceof String value) {
            return value;
        }
        if (raw instanceof Number || raw instanceof Boolean || raw instanceof Character) {
            return String.valueOf(raw);
        }
        warn(key, "a string", raw);
        return def;
    }

    boolean bool(String key, boolean def) {
        Object raw = section.get(key);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        warn(key, "a boolean", raw);
        return def;
    }

    int integer(String key, int def) {
        Object raw = section.get(key);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number value) {
            return value.intValue();
        }
        warn(key, "an integer", raw);
        return def;
    }

    /**
     * Reads a decimal.
     *
     * <p>{@code NaN} falls back like a wrong type rather than being passed on. It is reachable from
     * a hand-edited file — YAML 1.1 spells it {@code .nan} and SnakeYAML, the parser Bukkit itself
     * uses, resolves that to {@link Double#NaN} — and it compares {@code false} against every value
     * including itself, so any consumer that orders or equates two configured numbers gets a
     * nonsense answer from it. #79 neutralised it for item tiers inside {@code ItemGateCompiler};
     * here it is neutralised for every decimal in the file, which is where it belongs. Infinities
     * are left alone: they order normally, and {@code .inf} is a requirement nobody can meet rather
     * than a broken comparison.
     */
    double decimal(String key, double def) {
        Object raw = section.get(key);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number value) {
            double parsed = value.doubleValue();
            if (Double.isNaN(parsed)) {
                warnings.add(qualify(key) + ": \"" + raw + "\" is not a number, so the default "
                        + def + " was used instead.");
                return def;
            }
            return parsed;
        }
        warn(key, "a number", raw);
        return def;
    }

    /**
     * A list read: the entries that survived, and how many the operator actually wrote.
     *
     * <p>The second number exists because measuring a filtered list against itself cannot see what
     * the filter removed, and that mistake has now been made twice in this class. {@link #strings}
     * drops every element that is not a scalar, so a caller holding only its return value cannot
     * tell {@code require-advancements: []} — which says "require nothing" and means it — from a
     * list of items that all fell out on the way, which says "require these" and delivers nothing.
     * For a requirement list those are opposite meanings: the first is a configuration, the second
     * is a gate that reports itself armed and admits every player.
     *
     * <p>So the distinction is a named predicate on the value, {@link #emptiedItself()}, rather than
     * a size comparison each caller writes for itself. A caller that reaches for {@link #values()}
     * alone still gets correct data; only a caller that needs to know whether entries went missing
     * has to think about it, and for that caller the answer is already computed.
     *
     * @param values   what survived, in document order; never null
     * @param declared how many entries the document itself listed. Equal to {@code values.size()}
     *                 when the key was absent or wrong-typed and the default was substituted, so a
     *                 fallback never reads as a list that lost entries
     */
    record StringList(List<String> values, int declared) {

        StringList {
            values = List.copyOf(values);
        }

        /** How many written entries did not survive the read. */
        int dropped() {
            return declared - values.size();
        }

        /**
         * Whether the document wrote entries here and none of them survived — the state that means
         * something different from, and more dangerous than, an empty list written on purpose.
         */
        boolean emptiedItself() {
            return values.isEmpty() && declared > 0;
        }
    }

    /**
     * Reads a list of strings. Absent or wrong-typed reads fall back to {@code def}; individual
     * non-scalar elements are dropped with a warning rather than failing the whole list.
     *
     * <p>Callers that care whether entries were dropped must use {@link #stringList} instead: this
     * overload returns the survivors alone, and their count is not the count the operator wrote.
     */
    List<String> strings(String key, List<String> def) {
        return stringList(key, def).values();
    }

    /**
     * Reads a list of strings, keeping the number of entries the document declared alongside the
     * ones that survived. See {@link StringList} for why the two are worth carrying together.
     */
    StringList stringList(String key, List<String> def) {
        Object raw = section.get(key);
        if (raw == null) {
            return fallback(def);
        }
        if (!(raw instanceof List<?> list)) {
            warn(key, "a list of strings", raw);
            return fallback(def);
        }
        List<String> parsed = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof String value) {
                parsed.add(value);
            } else if (element instanceof Number || element instanceof Boolean) {
                parsed.add(String.valueOf(element));
            } else {
                warnings.add(qualify(key) + ": dropped a list entry that is not a string (found "
                        + describe(element) + ")");
            }
        }
        return new StringList(parsed, list.size());
    }

    /**
     * A substituted default, which by construction lost nothing: the document declared no usable
     * list here at all, so there is no operator-written entry for anything to have dropped.
     */
    private static StringList fallback(List<String> def) {
        return new StringList(def, def.size());
    }

    /**
     * Reads a list of advancement keys, normalised to the form the server resolves them in.
     *
     * <p>Every entry is put through {@link AdvancementKeys#canonical}, so the compiled gate table
     * and {@code BukkitAdvancementLookup} reason about one string rather than two spellings of it.
     * A non-blank entry the server's key parser rejects is fatal; see this class's documentation for
     * why that is not a warning.
     *
     * <p>An unusable entry is dropped with a warning <strong>only while a usable entry survives
     * beside it</strong>. That proviso is the whole of it: an entry that names no advancement
     * expresses nothing, so dropping it changes no requirement as long as the list still requires
     * something. A list that <em>empties itself</em> — written with entries, none of which survived
     * — is the outcome the fail-closed policy exists to prevent, reached by a different route: the
     * gate stays {@code enabled: true} with no requirement at all, which is a gate that reports
     * itself armed and admits everyone. It takes the same {@link ConfigLoadException} arm as an
     * unparseable key.
     *
     * <p>"Unusable" is deliberately wider than "blank", and the count is taken from what the
     * document declared rather than from what {@link #strings} handed back — see
     * {@link StringList}. A list item written with no value at all ({@code - } on its own, which is
     * what deleting a value or commenting one out leaves behind) is a null YAML entry: it never
     * reaches the loop below, because {@code strings} drops every non-scalar element before
     * returning. Measuring the shrink against the surviving list therefore could not see it, and a
     * gate whose only entry was a bare dash came out armed and requiring nothing. So could
     * {@code - {a: b}} and {@code - [x]}. All of them are now the same case.
     *
     * <p>An <em>absent</em> key and an explicitly empty list are a different thing and stay
     * non-fatal. Writing {@code require-advancements: []}, or leaving the key out, says "require no
     * advancement" and says it unambiguously; only a list that was written with entries and has none
     * left is a configuration the operator got wrong.
     *
     * @throws ConfigLoadException naming the entry, if any entry is not a resolvable key, or if
     *                             every entry of a non-empty list turned out to be unusable
     */
    List<String> advancementKeys(String key, List<String> def) throws ConfigLoadException {
        StringList configured = stringList(key, def);
        List<String> canonical = new ArrayList<>(configured.values().size());
        for (String entry : configured.values()) {
            String normalised = AdvancementKeys.canonical(entry);
            if (normalised.isEmpty()) {
                continue;
            }
            requireResolvable(key, entry, normalised);
            canonical.add(normalised);
        }
        StringList surviving = new StringList(canonical, configured.declared());
        if (surviving.dropped() > 0) {
            requireSomethingLeft(key, surviving);
        }
        return surviving.values();
    }

    /**
     * Decides what a dropped entry costs: a warning when the list still requires something, and a
     * rejected document when it no longer does.
     */
    private void requireSomethingLeft(String key, StringList surviving) throws ConfigLoadException {
        int dropped = surviving.dropped();
        int kept = surviving.values().size();
        if (!surviving.emptiedItself()) {
            warnings.add(qualify(key) + ": dropped " + dropped + " entr"
                    + (dropped == 1 ? "y that names" : "ies that name") + " no advancement. The "
                    + kept + " remaining " + (kept == 1 ? "entry is" : "entries are")
                    + " still required, so nothing was disarmed. Remove the empty list "
                    + (dropped == 1 ? "item" : "items") + ", or name an advancement.");
            return;
        }
        throw new ConfigLoadException(qualify(key) + ": all " + dropped + " entr"
                + (dropped == 1 ? "y names" : "ies name") + " no advancement, so this list requires "
                + "nothing at all while still being written as a requirement. config.yml has NOT "
                + "been applied. A gate left with no requirement admits every player while "
                + "reporting itself enabled, which is the failure this list is read strictly to "
                + "prevent. An entry can end up naming nothing by being blank, or by being written "
                + "with no value at all (a bare \"-\"). Name an advancement, or write the empty "
                + "list \"[]\" if no advancement is meant to be required.");
    }

    /**
     * Reads a single advancement key, normalised as {@link #advancementKeys} normalises each entry.
     *
     * <p>A blank value reads as "no advancement is required" and comes back as {@code ""}: clearing
     * the key is how an operator switches the requirement off, and it is not a typo. Anything else
     * the server's key parser rejects is fatal.
     *
     * @throws ConfigLoadException naming the value, if it is not blank and not a resolvable key
     */
    String advancementKey(String key, String def) throws ConfigLoadException {
        String configured = string(key, def);
        String normalised = AdvancementKeys.canonical(configured);
        if (normalised.isEmpty()) {
            return "";
        }
        requireResolvable(key, configured, normalised);
        return normalised;
    }

    private void requireResolvable(String key, String configured, String normalised)
            throws ConfigLoadException {
        if (AdvancementKeys.isResolvable(normalised)) {
            return;
        }
        throw new ConfigLoadException(qualify(key) + ": \"" + configured + "\" is not an advancement "
                + "key this server can resolve (read as \"" + normalised + "\"; a key is "
                + "namespace:path, lower case, using only a-z 0-9 / . _ and -). config.yml has NOT "
                + "been applied. This is an error rather than a warning because an unresolvable "
                + "advancement is waived at runtime, not enforced, so the gate that names it would "
                + "silently let every player through. Fix the spelling and reload.");
    }

    /** Reads an enum constant case-insensitively, falling back with a warning that lists the options. */
    <E extends Enum<E>> E enumValue(String key, Class<E> type, E def) {
        Object raw = section.get(key);
        if (raw == null) {
            return def;
        }
        if (raw instanceof String value) {
            String normalised = value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(normalised)) {
                    return constant;
                }
            }
            warnings.add(qualify(key) + ": \"" + value + "\" is not one of "
                    + Arrays.toString(type.getEnumConstants()) + "; using the default " + def);
            return def;
        }
        warn(key, "one of " + Arrays.toString(type.getEnumConstants()), raw);
        return def;
    }

    /**
     * Reads a value that must be strictly positive, falling back with a warning otherwise. Used for
     * the documented {@code multiplier > 0.0} invariant.
     */
    double positiveDecimal(String key, double def) {
        double value = decimal(key, def);
        if (!(value > 0.0D) || !Double.isFinite(value)) {
            warnings.add(qualify(key) + ": must be greater than 0.0 (found " + value
                    + "); using the default " + def);
            return def;
        }
        return value;
    }

    /** Reads a value clamped to be at least {@code min}, warning when the configured value is below it. */
    int atLeast(String key, int def, int min) {
        int value = integer(key, def);
        if (value < min) {
            warnings.add(qualify(key) + ": must be at least " + min + " (found " + value
                    + "); using the default " + def);
            return def;
        }
        return value;
    }

    /** The keys declared on this section, in document order. */
    Set<String> keys() {
        return section.keys();
    }

    /** Warns about every key on this section that is not in {@code known}. */
    void expect(String... known) {
        Set<String> recognised = Set.of(known);
        for (String key : section.keys()) {
            if (!recognised.contains(key)) {
                warnings.add("unknown key " + qualify(key) + " is not used by AntiSpeedrun and was ignored");
            }
        }
    }

    /**
     * Records a warning about this section as a whole, rather than about one key. Used for states
     * that are individually well-formed but jointly meaningless.
     */
    void note(String message) {
        warnings.add(path.isEmpty() ? message : path + ": " + message);
    }

    private void warn(String key, String expected, Object found) {
        warnings.add(qualify(key) + ": expected " + expected + " but found " + describe(found)
                + "; using the default");
    }

    private String qualify(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static String describe(Object value) {
        if (value == null) {
            return "nothing";
        }
        if (value instanceof ConfigSection || value instanceof java.util.Map<?, ?>) {
            return "a section";
        }
        if (value instanceof List<?>) {
            return "a list";
        }
        return value.getClass().getSimpleName() + " \"" + value + "\"";
    }
}
