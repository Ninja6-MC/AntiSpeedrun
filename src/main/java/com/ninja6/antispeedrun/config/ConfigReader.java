package com.ninja6.antispeedrun.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.kyori.adventure.text.minimessage.MiniMessage;

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
 * <p>{@link #miniMessage} adds a fourth arm to the same policy rather than a second policy: a value
 * of the right type that the consumer cannot use falls back to the shipped default and warns. It
 * exists because a message template is only discovered to be unusable at the moment it is sent,
 * which for {@code idle-reminder.message} is on a region thread once per reminder.
 *
 * <h2>The one exception: an advancement key the server cannot resolve is fatal</h2>
 *
 * <p>{@link #advancementKey} and {@link #advancementKeys} do not fall back. A key that is still not
 * resolvable after {@link AdvancementKeys#canonical normalisation} throws
 * {@link UnenforceableGateException}, and so does a requirement list that is written with entries
 * but has none left once the ones naming no advancement are dropped — whether they were blank, or
 * written with no value at all. Either rejects the whole document and nothing is published.
 * <strong>This is deliberate and it is the fail-closed choice</strong>, decided on #83 against the
 * alternative of dropping the requirement with a warning.
 *
 * <p>One boundary on that, because the sentence above is easy to read as wider than it is. It
 * applies only to a gate that is switched <em>on</em> — the {@code enforced} overloads take the
 * flag, and a gate that is off claims nothing, so a stale key inside it is a warning rather than a
 * refused document.
 *
 * <h2>The rule is about the requirement going missing, not about the spelling</h2>
 *
 * <p>An unresolvable key is only one way to end up with a gate that is switched on and requires
 * nothing. #92 closed the other two, and they take the same arm because they have the same
 * consequence:
 *
 * <ul>
 *   <li>a {@code require-advancements} written as a <strong>scalar</strong> where a list is
 *       expected, <em>where the fallback default is itself empty</em>. {@link #stringList} warns and
 *       substitutes the shipped default, which for an item tier is {@code List.of()} — so the tier
 *       came out armed and requiring nothing, on a warning. A key with an upper-case letter was
 *       already fatal; the same operator mistake written with the wrong YAML shape is now fatal
 *       too. Where the default is non-empty, as it is for every dimension gate, the gate still
 *       requires something and was never unenforceable, so that stays the ordinary wrong-type
 *       warning — see {@link #requireListShape};</li>
 *   <li>a <strong>blank single</strong> key beside the flag that switches its gate on —
 *       {@code villager-progression.required-advancement: ""} with {@code gate-mending-trade: true}.
 *       Each half is legitimate alone, which is why neither warned; together they are a gate that
 *       reports itself armed and admits every player.</li>
 * </ul>
 *
 * <p><strong>What "rejected" costs differs between a reload and a boot, and the difference is why
 * the exception is a named subtype.</strong> On {@code /asr reload} the previously loaded
 * configuration and everything derived from it stay live, so nothing is disarmed and the rule is
 * closed end to end; the subtype changes nothing there. At {@code onEnable} there is no previous
 * configuration to keep, and {@code AntiSpeedrunPlugin} refuses to start rather than fall back to
 * {@link PluginConfig#defaults()} — which declare <em>no item tiers</em>, so booting on them would
 * turn all item gating off over one typo, which is the same armed-but-permissive outcome this rule
 * exists to prevent. A document that fails to parse at all keeps the fallback that audit finding
 * R-11 asked for, because it describes no gating for the plugin to fail to enforce. That is the
 * split decided on #91; {@link UnenforceableGateException} carries the reasoning in full.
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
 * key under a gate that is <em>off</em> names no advancement rather than misspelling one — an
 * operator clearing {@code villager-progression.required-advancement} beside
 * {@code gate-mending-trade: false} is saying "do not gate the trade, and require nothing for it" —
 * so it reads as absent. Note the exemption is about what survives, not about the
 * blank itself: an entry naming no advancement inside a <em>list</em> is dropped with a warning while
 * a usable entry remains beside it, and is fatal when none does, because a list that empties itself
 * leaves exactly the armed-but-permissive gate this policy exists to prevent. And a well-formed key
 * that names no
 * advancement <em>on this server</em> stays a runtime warning: that is a property of the server's
 * version and datapacks, not of the file, and making a datapack change refuse to start is the
 * failure mode #79 rejected. {@code MilestoneEvaluator} documents that waiver and the other
 * deliberate fail-open paths in one place.
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

    /**
     * Reads a string that will be handed to MiniMessage, and falls back if it will not parse.
     *
     * <p><strong>What actually throws, measured rather than assumed.</strong> Against
     * adventure-text-minimessage 4.20.0 — the version {@code paper-api} resolves — the lenient
     * {@code MiniMessage.miniMessage()} instance swallows a great deal. An unknown tag survives as
     * literal text, which is what lets {@code <next_step>} through, and so does a tag it
     * <em>does</em> know carrying a bad argument: {@code MiniMessageParser} catches the
     * {@code ParsingException} a resolver raises and emits the tag as text
     * ({@code catch (final ParsingException ignored) { return null; }}), so {@code <color:nosuchcolour>}
     * and {@code <click:not_an_action:x>} both parse cleanly. Strict mode, which would reject an
     * unclosed tag, is off and is not ours to turn on.
     *
     * <p>What is left is the case an operator is most likely to write: a <strong>legacy formatting
     * code</strong>. {@code TokenParser.parseString} throws {@code ParsingException} — "Legacy
     * formatting codes have been detected in a MiniMessage string" — on a section sign followed by
     * anything, so a template pasted from a pre-Adventure config, {@code §eNext Goal: {NEXT_STEP}},
     * fails to deserialise. Nothing else in the load path can see that: the value is a well-formed
     * string, so {@link #string} passes it straight through, and the failure surfaces per message on
     * whatever thread the message was being sent from, arbitrarily far from the operator who typed
     * it. Which specific inputs throw is a property of the MiniMessage version, so this deserialises
     * the value rather than pattern-matching for the ones known today.
     *
     * <p><strong>This warns and falls back; it does not refuse to boot.</strong> That is the
     * opposite answer from {@link UnenforceableGateException}, deliberately, and the difference is
     * what the value does rather than how it failed. A gate the server cannot enforce describes
     * gating that will not be applied, so booting on the defaults ships a server that reports itself
     * armed and lets everyone through — the fail-closed rule from #83. A message is cosmetic: it
     * gates nothing, and the shipped default says the same thing in the same place, so the fallback
     * leaves no wrong running state for the rule to protect against. #91 drew that line explicitly:
     * refusal at boot is reserved for a document whose defaults would turn gating off, and stopping
     * a server over a typo in a cosmetic hint is the failure mode on the other side of it. The
     * operator learns about it from the warning, at startup and again on every {@code /asr reload},
     * which is what the finding asked for — the failure is reported at load rather than discovered
     * as a per-second exception on a region thread.
     *
     * <p>The raw configured value is what gets parsed here, not the form the engine hands to
     * MiniMessage: {@code {NEXT_STEP}} is not MiniMessage syntax and the tag it is rewritten to is
     * resolved with an unparsed placeholder, so neither the rewrite nor the resolver can turn a
     * template that parses here into one that throws there. Validating the operator's own markup is
     * the whole of what is at issue.
     *
     * @param key the key under this section
     * @param def the shipped default, which must itself parse
     */
    String miniMessage(String key, String def) {
        String value = string(key, def);
        if (value.equals(def)) {
            return def;
        }
        try {
            MiniMessage.miniMessage().deserialize(value);
            return value;
        } catch (RuntimeException malformed) {
            // MiniMessage's message carries the offending line and a caret under it, so it arrives
            // with newlines in it. One warning is one line here -- the whole list is logged as such.
            String because = String.valueOf(malformed.getMessage()).replaceAll("\\s+", " ").trim();
            warnings.add(qualify(key) + ": \"" + value + "\" is not valid MiniMessage ("
                    + because + "); using the default");
            return def;
        }
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
     * itself armed and admits everyone. It takes the same {@link UnenforceableGateException} arm as
     * an unresolvable key.
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
     * @throws UnenforceableGateException naming the entry, if any entry is not a resolvable key,
     *                                    if every entry of a non-empty list turned out to be
     *                                    unusable, or if the value is not written as a list at all
     *                                    while {@code def} is empty (see {@link #requireListShape})
     */
    List<String> advancementKeys(String key, List<String> def) throws ConfigLoadException {
        return advancementKeys(key, def, true);
    }

    /**
     * As {@link #advancementKeys(String, List)}, but fatal only when the gate that reads this list
     * is switched on.
     *
     * <p>The strictness exists to stop a gate reporting itself armed while admitting everyone. A
     * gate whose {@code enabled} flag is {@code false} makes no such claim: it admits everyone and
     * says so, so a key it names gates nothing either way and there is no unenforceable gating for
     * the plugin to refuse to start over. Reading it strictly anyway would mean a server that
     * booted yesterday refusing to boot today because of a stale key inside a section the operator
     * has already turned off — a false refusal, and a much worse one at startup than at reload.
     *
     * <p>The key is still normalised and still warned about, because switching the section back on
     * is exactly when the operator needs to know, and because that flip is then the thing that
     * fails rather than something unrelated much later.
     *
     * <p>The value's <em>shape</em> is read strictly on the same condition, and on one more: see
     * {@link #requireListShape}, which is #92's first waiver path.
     *
     * @param enforced whether the gate reading this list is switched on. {@code false} downgrades
     *                 every fatal case here to a warning and drops the unusable entries
     */
    List<String> advancementKeys(String key, List<String> def, boolean enforced)
            throws ConfigLoadException {
        requireListShape(key, def, enforced);
        StringList configured = stringList(key, def);
        List<String> canonical = new ArrayList<>(configured.values().size());
        for (String entry : configured.values()) {
            String normalised = AdvancementKeys.canonical(entry);
            if (normalised.isEmpty()) {
                continue;
            }
            if (!AdvancementKeys.isResolvable(normalised)) {
                requireResolvable(key, entry, normalised, enforced);
                continue;
            }
            canonical.add(normalised);
        }
        StringList surviving = new StringList(canonical, configured.declared());
        if (surviving.dropped() > 0) {
            requireSomethingLeft(key, surviving, enforced);
        }
        return surviving.values();
    }

    /**
     * Rejects a {@code require-advancements} that is not written as a list at all, when falling
     * back would leave a live gate requiring nothing.
     *
     * <p>Deliberately checked before {@link #stringList} rather than inside it: the fallback is
     * right for every other key, and it is only this one where substituting the default can publish
     * no requirement at all under a gate that reports itself armed.
     *
     * <p><strong>Two conditions, not one, and the second is the one worth stating.</strong> The
     * gate has to be switched on — a gate that is off gates nothing either way, and a stale value
     * in a section the operator has already turned off must not stop a server that booted
     * yesterday. And the <em>fallback default has to be empty</em>, which in practice means an item
     * tier: {@code PluginConfig.parseItemTier} passes {@code List.of()}, so falling back arms the
     * tier and requires nothing, which is the outcome this whole policy exists to prevent. Every
     * dimension gate ships a non-empty default, so falling back there leaves the gate requiring the
     * shipped advancements — not what the file says, which is worth a warning, but not a gate this
     * server would fail to enforce. Refusing to boot on a configuration that is still enforceable
     * is a false refusal, and false refusals at startup were the merge blocker on #91.
     *
     * <p>In both non-fatal cases {@code stringList}'s own wrong-type warning does the work, and
     * this adds the sentence that says what was substituted and what it costs.
     */
    private void requireListShape(String key, List<String> def, boolean enforced)
            throws ConfigLoadException {
        Object raw = section.get(key);
        if (raw == null || raw instanceof List<?>) {
            return;
        }
        if (!enforced) {
            warnings.add(qualify(key) + ": is not written as a list, so the shipped default was "
                    + "used instead of what the file says. That is tolerated only because the gate "
                    + "reading it is switched off; switching it on unchanged will stop the plugin "
                    + "at the next start. Write each advancement as its own \"- \" list entry.");
            return;
        }
        if (!def.isEmpty()) {
            warnings.add(qualify(key) + ": is not written as a list, so the shipped default ("
                    + String.join(", ", def) + ") is required instead of what the file says. The "
                    + "gate is still enforcing something, which is why this is not an error, but "
                    + "it is not enforcing what the file asked for. Write each advancement as its "
                    + "own \"- \" list entry.");
            return;
        }
        throw new UnenforceableGateException(qualify(key) + ": " + describe(raw) + " is not a list "
                + "of advancement keys, and config.yml has NOT been applied: after a reload the "
                + "configuration already running stays live, and at startup the plugin does not "
                + "enable. There is no default to fall back to here, so the gate would be "
                + "published switched on and requiring nothing at all, which admits every player "
                + "while reporting itself armed. This is the same error an unresolvable key gets, "
                + "because it is the same mistake with the same consequence. Write each "
                + "advancement as its own \"- \" list entry, or write the empty list \"[]\" if no "
                + "advancement is meant to be required.");
    }

    /**
     * Decides what a dropped entry costs: a warning when the list still requires something or the
     * gate reading it is switched off, and a rejected document when a live gate is left requiring
     * nothing.
     */
    private void requireSomethingLeft(String key, StringList surviving, boolean enforced)
            throws ConfigLoadException {
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
        if (!enforced) {
            warnings.add(qualify(key) + ": all " + dropped + " entr"
                    + (dropped == 1 ? "y names" : "ies name") + " no advancement, so this list "
                    + "requires nothing at all. That is tolerated only because the gate reading it "
                    + "is switched off; switching it on with this list unchanged will stop the "
                    + "plugin at the next start. Name an advancement, or write the empty list "
                    + "\"[]\" if no advancement is meant to be required.");
            return;
        }
        throw new UnenforceableGateException(qualify(key) + ": all " + dropped + " entr"
                + (dropped == 1 ? "y names" : "ies name") + " no advancement, so this list requires "
                + "nothing at all while still being written as a requirement. config.yml has NOT "
                + "been applied: after a reload the configuration already running stays live, and "
                + "at startup the plugin does not enable. A gate left with no requirement admits "
                + "every player while "
                + "reporting itself enabled, which is the failure this list is read strictly to "
                + "prevent. An entry can end up naming nothing by being blank, or by being written "
                + "with no value at all (a bare \"-\"). Name an advancement, or write the empty "
                + "list \"[]\" if no advancement is meant to be required.");
    }

    /**
     * Reads a single advancement key, normalised as {@link #advancementKeys} normalises each entry.
     *
     * <p>Reads the key as a gate that is switched <em>on</em> would read it, so a blank value is
     * fatal here: see {@link #advancementKey(String, String, boolean)}, where the caller says
     * whether the gate is on and a blank under a gate that is off comes back as {@code ""}.
     * Anything the server's key parser rejects is fatal either way.
     *
     * @throws UnenforceableGateException naming the value, if it is blank or is not a resolvable
     *                                    key
     */
    String advancementKey(String key, String def) throws ConfigLoadException {
        return advancementKey(key, def, true);
    }

    /**
     * As {@link #advancementKey(String, String)}, but fatal only when the gate that reads this key
     * is switched on. See {@link #advancementKeys(String, List, boolean)} for why a switched-off
     * gate describes no gating and so cannot describe gating this server would fail to enforce.
     *
     * <p>{@code enforced} decides the blank case too, in the other direction, and that is #92's
     * second waiver path. A blank beside a gate that is switched on is not a requirement switched
     * off, it is a requirement that went missing: clearing the key and setting
     * {@code gate-mending-trade: true} are each legitimate on their own, which is why neither half
     * warned, but together they publish a gate that reports itself armed and admits every player,
     * reached without a single malformed character. There is nothing else to fall back on —
     * {@code villager-progression} declares this key and the flag, and nothing else. Switching the
     * gate off remains the way to say "do not gate the mending trade".
     *
     * @param enforced whether the gate reading this key is switched on. {@code false} downgrades
     *                 an unresolvable key to a warning and reads it as no requirement; {@code true}
     *                 additionally makes a blank value fatal instead of reading it as no
     *                 requirement
     */
    String advancementKey(String key, String def, boolean enforced) throws ConfigLoadException {
        String configured = string(key, def);
        String normalised = AdvancementKeys.canonical(configured);
        if (normalised.isEmpty()) {
            if (enforced) {
                throw new UnenforceableGateException(qualify(key) + ": is blank while the gate "
                        + "that reads it is switched on, so that gate is enabled and requires "
                        + "nothing at all. config.yml has NOT been applied: after a reload the "
                        + "configuration already running stays live, and at startup the plugin "
                        + "does not enable. A cleared key is how the requirement is switched off, "
                        + "and gate-mending-trade: true is how the gate is switched on; each is "
                        + "legitimate alone, but together they publish a gate that reports itself "
                        + "armed and admits every player. Name an advancement, or set "
                        + "gate-mending-trade to false if the trade is not meant to be gated.");
            }
            return "";
        }
        if (!AdvancementKeys.isResolvable(normalised)) {
            requireResolvable(key, configured, normalised, enforced);
            return "";
        }
        return normalised;
    }

    /**
     * Rejects an unresolvable key, or — when the gate reading it is switched off — records it as a
     * warning and lets the caller drop it.
     */
    private void requireResolvable(String key, String configured, String normalised,
            boolean enforced) throws ConfigLoadException {
        if (!enforced) {
            warnings.add(qualify(key) + ": \"" + configured + "\" is not an advancement key this "
                    + "server can resolve (read as \"" + normalised + "\"), so it was dropped. "
                    + "That is a warning rather than an error only because the gate reading it is "
                    + "switched off and so gates nothing either way; switching it on with this key "
                    + "unchanged will stop the plugin at the next start. Fix the spelling.");
            return;
        }
        throw new UnenforceableGateException(qualify(key) + ": \"" + configured + "\" is not an advancement "
                + "key this server can resolve (read as \"" + normalised + "\"; a key is "
                + "namespace:path, lower case, using only a-z 0-9 / . _ and -). config.yml has NOT "
                + "been applied: after a reload the configuration already running stays live, and "
                + "at startup the plugin does not enable. This is an error rather than a warning "
                + "because an unresolvable "
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
