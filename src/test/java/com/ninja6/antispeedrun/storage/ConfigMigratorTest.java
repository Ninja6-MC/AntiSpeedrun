package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import com.ninja6.antispeedrun.config.ConfigLoadException;
import com.ninja6.antispeedrun.config.ConfigVersionException;
import com.ninja6.antispeedrun.config.MapConfigSection;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PluginConfig.Profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigMigrator} on real text and a real temporary directory.
 *
 * <p>The centrepiece is the v0.1.x fixture: {@code config.yml} as v0.1.1 shipped it, with the kind
 * of edits an operator makes (changed values, a trailing comment, a note of their own, a value
 * that differs from the old default). Migrating it must add exactly the one missing key, off, and
 * change nothing else.
 */
class ConfigMigratorTest {

    private static final Instant AT = Instant.parse("2026-10-05T12:00:00Z");

    private static String resource(String name) throws IOException {
        try (InputStream in = ConfigMigratorTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name + " must be on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static PluginConfig parse(String yaml) throws ConfigLoadException {
        return PluginConfig.from(MapConfigSection.of((Map<?, ?>) new Yaml().load(yaml)));
    }

    private static List<String> lines(String text) {
        return List.of(text.split("\r?\n", -1));
    }

    @Test
    @DisplayName("a v0.1.x file with operator edits gains config-version and the one missing key, off, and nothing else changes")
    void upgradesV01xShapeWithoutTouchingOperatorValues() throws Exception {
        String before = resource("/config-v0.1.1-edited.yml");
        assertFalse(before.contains("config-version"));
        assertFalse(before.contains("cap-single-hit-boss-damage"));

        ConfigMigrator.Outcome outcome = ConfigMigrator.migrateText(before);

        assertEquals(0, outcome.from());
        assertEquals(PluginConfig.CONFIG_VERSION, outcome.to());
        assertEquals(List.of("anti-cheese.cap-single-hit-boss-damage"), outcome.added());
        assertEquals(List.of(), outcome.skipped());

        // Line level: every original line survives, in order, and the only new lines are the
        // version stamp (header comments, key, blank) and the added key with its comment.
        List<String> after = new ArrayList<>(lines(outcome.text()));
        List<String> original = lines(before);
        List<String> added = new ArrayList<>();
        int next = 0;
        for (String line : after) {
            if (next < original.size() && line.equals(original.get(next))) {
                next++;
            } else {
                added.add(line);
            }
        }
        assertEquals(original.size(), next, "an original line was lost or reordered");
        assertTrue(added.contains("config-version: " + PluginConfig.CONFIG_VERSION));
        assertTrue(added.contains("  cap-single-hit-boss-damage: false"));
        assertEquals(3 + 1 + 1 + 2 + 1, added.size(), "unexpected extra lines: " + added);

        // Value level: parsed, the result equals the old file except for the one new field, and
        // that field is off. The values the operator set, and the two v0.1.1 defaults that later
        // builds flipped off, are still exactly as they were.
        PluginConfig old = parse(before);
        PluginConfig migrated = parse(outcome.text());
        assertTrue(migrated.isClean(), "migrated file warns: " + migrated.warnings());
        assertEquals(old.profile(), migrated.profile());
        assertEquals(Profile.CUSTOM, migrated.profile());
        assertEquals(old.dimensionGates(), migrated.dimensionGates());
        assertEquals(old.itemProgression(), migrated.itemProgression());
        assertEquals(old.trimProgression(), migrated.trimProgression());
        assertEquals(old.bossScaling(), migrated.bossScaling());
        assertEquals(old.villagerProgression(), migrated.villagerProgression());
        assertEquals(20.0, migrated.antiCheese().maxSingleHitBossDamage());
        assertEquals(750, migrated.antiCheese().outerEndRadius());
        assertFalse(migrated.antiCheese().blockBedAnchorBossDamage());
        assertTrue(migrated.antiCheese().blockExitPortalCrystalPlace());
        assertTrue(migrated.antiCheese().blockGatewayPreDragon());
        assertFalse(migrated.antiCheese().capSingleHitBossDamage());
        assertEquals(old.antiCheese(), migrated.antiCheese(), "cap defaults to false when absent, so the "
                + "migrated section must equal the old one");
    }

    @Test
    @DisplayName("migrating twice is a no-op")
    void idempotent() throws Exception {
        String once = ConfigMigrator.migrateText(resource("/config-v0.1.1-edited.yml")).text();
        ConfigMigrator.Outcome twice = ConfigMigrator.migrateText(once);
        assertFalse(twice.migrated());
        assertEquals(once, twice.text());
    }

    @Test
    @DisplayName("an existing value for the added key is never overwritten")
    void existingKeyIsKept() throws Exception {
        String before = "anti-cheese:\n  enabled: true\n  cap-single-hit-boss-damage: true\n";
        ConfigMigrator.Outcome outcome = ConfigMigrator.migrateText(before);
        assertEquals(List.of(), outcome.added());
        assertEquals(List.of(), outcome.skipped());
        assertTrue(outcome.text().contains("  cap-single-hit-boss-damage: true\n"));
        assertEquals(1, outcome.text().split("cap-single-hit-boss-damage", -1).length - 1);
    }

    @Test
    @DisplayName("a deleted section is not recreated, and a section with no body is reported rather than guessed at")
    void missingSection() throws Exception {
        ConfigMigrator.Outcome gone = ConfigMigrator.migrateText("profile: CASUAL\n");
        assertEquals(List.of(), gone.added());
        assertEquals(List.of("anti-cheese.cap-single-hit-boss-damage"), gone.skipped());
        assertFalse(gone.text().contains("anti-cheese"));
        assertTrue(gone.text().contains("config-version: 1"));

        ConfigMigrator.Outcome empty = ConfigMigrator.migrateText("anti-cheese: {}\n");
        assertEquals(List.of("anti-cheese.cap-single-hit-boss-damage"), empty.skipped());
        assertTrue(empty.text().contains("anti-cheese: {}"));
    }

    @Test
    @DisplayName("windows line endings, a BOM and a missing trailing newline are kept")
    void preservesFileFormat() throws Exception {
        String crlf = "﻿profile: CASUAL\r\nanti-cheese:\r\n  enabled: true\r\n";
        String out = ConfigMigrator.migrateText(crlf).text();
        assertTrue(out.startsWith("﻿# Schema version"));
        assertFalse(out.replace("\r\n", "").contains("\n"), "a bare LF was introduced");
        assertTrue(out.endsWith("  cap-single-hit-boss-damage: false\r\n"));

        String noNewline = "anti-cheese:\n  enabled: true";
        String bare = ConfigMigrator.migrateText(noNewline).text();
        assertTrue(bare.endsWith("  cap-single-hit-boss-damage: false"));
    }

    @Test
    @DisplayName("an unfinished comment under the section does not move the new key out of it")
    void insertsBeforeTheNextSection() throws Exception {
        String before = "anti-cheese:\n  enabled: true\n  # trailing note\n\n# next\nvillager-progression:\n  gate-mending-trade: false\n";
        String out = ConfigMigrator.migrateText(before).text();
        int cap = out.indexOf("  cap-single-hit-boss-damage");
        assertTrue(cap > out.indexOf("  enabled: true"));
        assertTrue(cap < out.indexOf("villager-progression:"));
        assertTrue(out.contains("# next\nvillager-progression:"));
    }

    @Test
    @DisplayName("a newer, zero, negative or non-numeric config-version is refused")
    void refusesUnreadableVersions() {
        for (String bad : List.of("2", "99", "0", "-1", "one", "1.5", "''")) {
            ConfigVersionException refused = assertThrows(ConfigVersionException.class,
                    () -> ConfigMigrator.migrateText("config-version: " + bad + "\nprofile: CASUAL\n"),
                    "config-version: " + bad);
            assertTrue(refused.getMessage().contains("config-version"), refused.getMessage());
        }
    }

    @Test
    @DisplayName("PluginConfig itself refuses an unreadable config-version and accepts an absent one")
    void pluginConfigChecksVersion() throws Exception {
        assertThrows(ConfigVersionException.class, () -> parse("config-version: 2\n"));
        assertThrows(ConfigVersionException.class, () -> parse("config-version: banana\n"));
        assertThrows(ConfigVersionException.class, () -> parse("config-version: 0\n"));
        assertTrue(parse("config-version: 1\n").warnings().stream().noneMatch(w -> w.contains("config-version")));
        assertEquals(PluginConfig.defaults().profile(), parse("profile: SMP_STANDARD\n").profile());
    }

    @Test
    @DisplayName("migrateFile backs the old file up byte for byte before rewriting it")
    void migrateFileBacksUpFirst(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.yml");
        String before = resource("/config-v0.1.1-edited.yml");
        Files.writeString(config, before, StandardCharsets.UTF_8);

        Optional<ConfigMigrator.Result> result = ConfigMigrator.migrateFile(
                config, dir.resolve("backups"), AT, ZoneOffset.UTC);

        assertTrue(result.isPresent());
        assertEquals("config-20261005-120000.yml", result.get().backup().getFileName().toString());
        assertEquals(before, Files.readString(result.get().backup(), StandardCharsets.UTF_8));
        assertEquals(result.get().outcome().text(), Files.readString(config, StandardCharsets.UTF_8));
        try (var entries = Files.list(dir)) {
            assertEquals(List.of("backups", "config.yml"),
                    entries.map(p -> p.getFileName().toString()).sorted().toList(),
                    "no staging file may be left behind");
        }

        // Second run: already current, so nothing is written and no second backup appears.
        assertTrue(ConfigMigrator.migrateFile(config, dir.resolve("backups"), AT, ZoneOffset.UTC).isEmpty());
        try (var backups = Files.list(dir.resolve("backups"))) {
            assertEquals(1, backups.count());
        }
    }

    @Test
    @DisplayName("a file from a newer version is left untouched and not backed up")
    void newerFileIsUntouched(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.yml");
        String future = "config-version: 7\nprofile: HARDCORE\n";
        Files.writeString(config, future, StandardCharsets.UTF_8);

        assertThrows(ConfigVersionException.class,
                () -> ConfigMigrator.migrateFile(config, dir.resolve("backups"), AT, ZoneOffset.UTC));

        assertEquals(future, Files.readString(config, StandardCharsets.UTF_8));
        assertFalse(Files.exists(dir.resolve("backups")));
    }

    @Test
    @DisplayName("an absent file is not an error and creates nothing")
    void absentFile(@TempDir Path dir) throws Exception {
        assertTrue(ConfigMigrator.migrateFile(
                dir.resolve("config.yml"), dir.resolve("backups"), AT, ZoneOffset.UTC).isEmpty());
        assertFalse(Files.exists(dir.resolve("backups")));
    }

    @Test
    @DisplayName("the shipped files declare the current version and need no migration")
    void shippedFilesAreCurrent() throws Exception {
        List<String> resources = new ArrayList<>(List.of("/config.yml"));
        for (Profile profile : ProfileApplier.applicable()) {
            resources.add("/" + ProfileApplier.resourcePath(profile));
        }
        for (String name : resources) {
            String text = resource(name);
            ConfigMigrator.Outcome outcome = ConfigMigrator.migrateText(text);
            assertFalse(outcome.migrated(), name + " must declare config-version: "
                    + PluginConfig.CONFIG_VERSION + " (bump it when the shape changes)");
            Object declared = ((Map<?, ?>) new Yaml().load(text)).get("config-version");
            assertEquals(PluginConfig.CONFIG_VERSION, declared, name);
            assertTrue(parse(text).warnings().stream().noneMatch(w -> w.contains("config-version")), name);
        }
    }

    @Test
    @DisplayName("the migration table ends at the current version and only adds keys the shipped file still has")
    void stepsMatchTheShippedFile() throws Exception {
        assertEquals(PluginConfig.CONFIG_VERSION, ConfigMigrator.STEPS.get(ConfigMigrator.STEPS.size() - 1).to());
        int previous = 0;
        Map<?, ?> shipped = (Map<?, ?>) new Yaml().load(resource("/config.yml"));
        for (ConfigMigrator.Step step : ConfigMigrator.STEPS) {
            assertEquals(previous + 1, step.to(), "versions must be consecutive");
            previous = step.to();
            for (ConfigMigrator.Addition addition : step.additions()) {
                Map<?, ?> section = (Map<?, ?>) shipped.get(addition.section());
                assertNotNull(section, addition.section());
                assertTrue(section.containsKey(addition.key()), addition.section() + "." + addition.key()
                        + " is migrated in but no longer shipped");
                String value = addition.lines().get(addition.lines().size() - 1);
                assertEquals(addition.key() + ": " + section.get(addition.key()), value,
                        "a step's frozen default must still be the shipped default, or the shipped "
                                + "default changed and needs a new step");
                assertEquals(false, section.get(addition.key()),
                        "a migrated-in rule must ship off");
            }
        }
    }
}
