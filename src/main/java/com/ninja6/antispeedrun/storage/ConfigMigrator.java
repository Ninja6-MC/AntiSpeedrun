package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ninja6.antispeedrun.config.ConfigVersionException;
import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * Brings an older {@code config.yml} up to {@link PluginConfig#CONFIG_VERSION}, one version at a
 * time, without disturbing anything the operator wrote.
 *
 * <p>The file is edited as text, not parsed and re-serialised: a YAML round trip drops or
 * reflows comments, and the comments in this file are most of its documentation. Lines the
 * migrator does not own are written back unchanged, in their original order and with the file's
 * own line endings.
 *
 * <h2>What a migration may and may not do</h2>
 *
 * A step <strong>adds a key that is missing, with its shipped default</strong>. It never moves or
 * removes a value the operator has, and a key already present is left alone even when its value
 * differs from the default. The default written is the one the step itself carries, frozen when
 * the step was written, so changing a shipped default later cannot change what an old step does to
 * an old file. Every key added so far ships off; a step that adds a rule must add it disabled.
 *
 * <p><strong>The one exception</strong> is a {@link Reset}: the step to version 1 sets
 * {@code anti-cheese.block-bed-anchor-boss-damage}, {@code block-exit-portal-crystal-place} and
 * {@code block-gateway-pre-dragon} to {@code false} where they read {@code true}. v0.1.x shipped
 * all three {@code true} in {@code config.yml} and in the presets, but no code enforced them, so a
 * v0.1.x server never applied them. Carrying the {@code true} forward would switch on three rules
 * the operator never saw working; {@code false} keeps the server doing what it did. This runs once,
 * on the move from version 0, and only on a literal {@code true}: the value is edited in place,
 * its trailing comment kept, the backup taken first covers it, and each change is reported
 * separately from the additions so the caller can log it. An operator who wants a rule on sets it
 * back to {@code true}; a file already at version 1 is never reset.
 *
 * <p>A section the operator has deleted is not recreated. An absent section already behaves as the
 * defaults, so there is nothing to fill and nothing is added.
 *
 * <h2>Versions</h2>
 *
 * A file with no {@code config-version} is the shape released as v0.1.x and is treated as version
 * 0. A version above {@link PluginConfig#CONFIG_VERSION}, or one that is not a whole number of at
 * least 1, is refused with a {@link ConfigVersionException} and the file is left untouched.
 *
 * <h2>Writing</h2>
 *
 * The original is copied into {@code backups/} before anything is written, and the new file is
 * staged beside it and moved into place, so a failure leaves the original intact. Plain JDK, no
 * Bukkit, so it is tested on a temporary directory.
 *
 * <p>Touches the filesystem: never call it from a Folia region thread.
 */
public final class ConfigMigrator {

    /** A key to add under a top-level section, written as the shipped file writes it. */
    record Addition(String section, String key, List<String> lines) {
    }

    /**
     * A boolean under a top-level section that is switched from {@code true} to {@code false},
     * because the version it is migrated from shipped it {@code true} without enforcing it.
     */
    record Reset(String section, String key) {
    }

    /** What moving to {@code to} adds, and the unenforced values it switches off. */
    record Step(int to, List<Addition> additions, List<Reset> resets) {
        Step {
            additions = List.copyOf(additions);
            resets = List.copyOf(resets);
        }
    }

    /**
     * Every migration, in order. The last step's {@code to} is {@link PluginConfig#CONFIG_VERSION};
     * a test enforces that, and that each key named here still exists in the shipped file.
     */
    static final List<Step> STEPS = List.of(
            // v0.1.x -> 1: the only key the shipped file has gained since v0.1.1. Off by default.
            new Step(1, List.of(new Addition("anti-cheese", "cap-single-hit-boss-damage", List.of(
                    "# Caps what one hit can do to the Ender Dragon or the Wither, measured after armour and",
                    "# resistance. Off by default: set it to true to enforce max-single-hit-boss-damage.",
                    "cap-single-hit-boss-damage: false"))),
                    // Shipped true in v0.1.x but enforced by nothing; see the class comment.
                    List.of(new Reset("anti-cheese", "block-bed-anchor-boss-damage"),
                            new Reset("anti-cheese", "block-exit-portal-crystal-place"),
                            new Reset("anti-cheese", "block-gateway-pre-dragon"))));

    private static final List<String> VERSION_HEADER = List.of(
            "# Schema version of this file, kept current by the plugin. Do not edit or remove it:",
            "# on start an older file is migrated forward (the old one is saved to backups/ first)",
            "# and a file from a newer version is refused.");

    private static final Pattern VERSION_LINE =
            Pattern.compile("^" + Pattern.quote(PluginConfig.VERSION_KEY) + ":[ \\t]*(.*?)[ \\t]*(#.*)?$");

    private static final Pattern DOCUMENT_START = Pattern.compile("^---([ \\t].*)?$");

    private static final Pattern QUOTES = Pattern.compile("^[\"']|[\"']$");

    private ConfigMigrator() {
    }

    /**
     * The result of migrating a document.
     *
     * @param from    the version the document declared, or 0 when it declared none
     * @param to      the version it now declares
     * @param text    the migrated document; equal to the input when nothing was needed
     * @param added   the dotted keys that were added
     * @param changed the dotted keys whose value was switched from {@code true} to {@code false}
     * @param skipped the dotted keys a step wanted to add but could not place
     */
    public record Outcome(int from, int to, String text, List<String> added, List<String> changed,
                          List<String> skipped) {
        public Outcome {
            added = List.copyOf(added);
            changed = List.copyOf(changed);
            skipped = List.copyOf(skipped);
        }

        /** Whether the document needed migrating. */
        public boolean migrated() {
            return from != to;
        }
    }

    /** What {@link #migrateFile} did to a file on disk. */
    public record Result(Outcome outcome, Path backup) {
    }

    /**
     * Migrates {@code text} to the current version.
     *
     * @throws ConfigVersionException if the declared version is newer than this build knows or is
     *                                not a whole number of at least 1
     */
    public static Outcome migrateText(String text) throws ConfigVersionException {
        Objects.requireNonNull(text, "text");
        boolean bom = text.startsWith("\uFEFF");
        String body = bom ? text.substring(1) : text;
        String eol = body.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(body.split("\r?\n", -1)));

        int versionLine = -1;
        int from = 0;
        for (int i = 0; i < lines.size(); i++) {
            Matcher match = VERSION_LINE.matcher(lines.get(i));
            if (match.matches()) {
                versionLine = i;
                from = parseVersion(match.group(1));
                break;
            }
        }
        if (from > PluginConfig.CONFIG_VERSION) {
            throw new ConfigVersionException("config.yml has " + PluginConfig.VERSION_KEY + ": " + from
                    + ", but this build of AntiSpeedrun only reads versions up to "
                    + PluginConfig.CONFIG_VERSION + ". It was written by a newer version. Install that "
                    + "version, or restore a config.yml from backups/ that this build can read.");
        }
        if (from == PluginConfig.CONFIG_VERSION) {
            return new Outcome(from, from, text, List.of(), List.of(), List.of());
        }

        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Step step : STEPS) {
            if (step.to() <= from) {
                continue;
            }
            for (Addition addition : step.additions()) {
                String dotted = addition.section() + "." + addition.key();
                if (insert(lines, addition)) {
                    added.add(dotted);
                } else if (!present(lines, addition)) {
                    skipped.add(dotted);
                }
            }
            for (Reset reset : step.resets()) {
                if (switchOff(lines, reset)) {
                    changed.add(reset.section() + "." + reset.key());
                }
            }
        }

        String stamp = PluginConfig.VERSION_KEY + ": " + PluginConfig.CONFIG_VERSION;
        if (versionLine >= 0) {
            lines.set(versionLine, stamp);
        } else {
            List<String> header = new ArrayList<>(VERSION_HEADER);
            header.add(stamp);
            header.add("");
            lines.addAll(headerPosition(lines), header);
        }
        return new Outcome(from, PluginConfig.CONFIG_VERSION,
                (bom ? "\uFEFF" : "") + String.join(eol, lines), added, changed, skipped);
    }

    /**
     * Where the version header goes: after any {@code %} directives and the document-start marker
     * that precede the first key, never before them. Above a {@code ---} it would sit in a document
     * of its own, and the file would no longer load as one.
     */
    private static int headerPosition(List<String> lines) {
        int position = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("%")) {
                position = i + 1;
            } else if (DOCUMENT_START.matcher(line).matches()) {
                return i + 1;
            } else if (isContent(line)) {
                break;
            }
        }
        return position;
    }

    /**
     * Rewrites {@code key: true} as {@code key: false} under its section, keeping the spacing and
     * any trailing comment. Returns false, changing nothing, when the key is absent or is anything
     * other than a literal {@code true}.
     */
    private static boolean switchOff(List<String> lines, Reset reset) {
        int header = sectionHeader(lines, reset.section());
        if (header < 0) {
            return false;
        }
        int childIndent = -1;
        for (int i = header + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!isContent(line)) {
                continue;
            }
            int indent = indentOf(line);
            if (indent == 0) {
                break;
            }
            if (childIndent < 0) {
                childIndent = indent;
            }
            if (indent != childIndent) {
                continue;
            }
            Matcher match = Pattern.compile("^( {" + indent + "}" + Pattern.quote(reset.key())
                    + ":[ \\t]*)true([ \\t]*(#.*)?)$").matcher(line);
            if (match.matches()) {
                lines.set(i, match.group(1) + "false" + match.group(2));
                return true;
            }
        }
        return false;
    }

    private static int parseVersion(String raw) throws ConfigVersionException {
        String value = QUOTES.matcher(raw).replaceAll("");
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= 1) {
                return parsed;
            }
        } catch (NumberFormatException notANumber) {
            // Falls through to the same refusal as a number below 1.
        }
        throw new ConfigVersionException(PluginConfig.VERSION_KEY + " must be a whole number of at "
                + "least 1, but found \"" + raw + "\". Restore the value the plugin wrote, or remove the "
                + "key to have the file treated as the oldest supported shape.");
    }

    private static boolean isContent(String line) {
        String trimmed = line.strip();
        return !trimmed.isEmpty() && !trimmed.startsWith("#");
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /** The line of the section's header, or -1 when the section is absent or not a block. */
    private static int sectionHeader(List<String> lines, String section) {
        Pattern header = Pattern.compile("^" + Pattern.quote(section) + ":[ \\t]*(#.*)?$");
        for (int i = 0; i < lines.size(); i++) {
            if (header.matcher(lines.get(i)).matches()) {
                return i;
            }
        }
        return -1;
    }

    /** Whether the key is already a direct child of its section. */
    private static boolean present(List<String> lines, Addition addition) {
        int header = sectionHeader(lines, addition.section());
        if (header < 0) {
            return false;
        }
        int childIndent = -1;
        Pattern key = null;
        for (int i = header + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!isContent(line)) {
                continue;
            }
            int indent = indentOf(line);
            if (indent == 0) {
                break;
            }
            if (childIndent < 0) {
                childIndent = indent;
                key = Pattern.compile("^ {" + indent + "}" + Pattern.quote(addition.key()) + ":.*");
            }
            if (indent == childIndent && key.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds the key after the last line of its section, at the section's own indentation. Returns
     * false, changing nothing, when the section is absent, is not a block with children, or already
     * has the key.
     */
    private static boolean insert(List<String> lines, Addition addition) {
        int header = sectionHeader(lines, addition.section());
        if (header < 0 || present(lines, addition)) {
            return false;
        }
        int last = -1;
        int childIndent = -1;
        for (int i = header + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!isContent(line)) {
                continue;
            }
            int indent = indentOf(line);
            if (indent == 0) {
                break;
            }
            if (childIndent < 0) {
                childIndent = indent;
            }
            last = i;
        }
        if (last < 0) {
            return false;
        }
        String pad = " ".repeat(childIndent);
        List<String> block = new ArrayList<>();
        for (String line : addition.lines()) {
            block.add(pad + line);
        }
        lines.addAll(last + 1, block);
        return true;
    }

    /**
     * Migrates {@code configFile} in place, if it needs it.
     *
     * <p>Order is the point: the document is migrated in memory first (so a refused version touches
     * nothing), the original is copied into {@code backupDirectory}, and only then is the new file
     * staged and moved over it.
     *
     * @return what was done, or empty when the file is absent or already current, in which case
     *         nothing was written and no backup was taken
     * @throws ConfigVersionException if the file declares a version this build cannot read
     * @throws IOException            if the file cannot be read, backed up or rewritten; the
     *                                original is intact
     */
    public static Optional<Result> migrateFile(Path configFile, Path backupDirectory, Instant at,
                                               ZoneId zone) throws IOException, ConfigVersionException {
        Objects.requireNonNull(configFile, "configFile");
        if (!Files.isRegularFile(configFile)) {
            return Optional.empty();
        }
        // The same lock /asr profile apply holds, so a reload's migration cannot interleave with a
        // preset being written: whichever runs second reads the other's finished file.
        synchronized (ProfileApplier.APPLY_LOCK) {
            return migrateLocked(configFile, backupDirectory, at, zone);
        }
    }

    private static Optional<Result> migrateLocked(Path configFile, Path backupDirectory, Instant at,
                                                  ZoneId zone) throws IOException, ConfigVersionException {
        if (!Files.isRegularFile(configFile)) {
            return Optional.empty();
        }
        Outcome outcome = migrateText(Files.readString(configFile, StandardCharsets.UTF_8));
        if (!outcome.migrated()) {
            return Optional.empty();
        }
        Path backup = ProfileApplier.backup(configFile, backupDirectory, at, zone)
                .orElseThrow(() -> new IOException(configFile + " disappeared before it was backed up"));

        Path staged = configFile.resolveSibling(
                configFile.getFileName() + "." + UUID.randomUUID() + ".incoming");
        try {
            Files.writeString(staged, outcome.text(), StandardCharsets.UTF_8);
            try {
                Files.move(staged, configFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException ignored) {
                // The failure that got us here, if any, is the one worth reporting.
            }
        }
        return Optional.of(new Result(outcome, backup));
    }
}
