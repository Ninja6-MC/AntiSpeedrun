package com.ninja6.antispeedrun.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ninja6.antispeedrun.config.PluginConfig.Profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /asr profile apply} against a real filesystem.
 *
 * <p>{@link ProfileApplier} is plain {@code java.nio}, so the backup, the overwrite and the
 * ordering between them are exercised for real rather than mocked.
 */
class ProfileApplierTest {

    private static final Instant AT = Instant.parse("2026-09-04T15:30:12Z");
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static InputStream preset(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("resource naming")
    class ResourceNaming {

        @Test
        @DisplayName("the filename is derived from the enum constant, so the two cannot drift")
        void derivedFromEnum() {
            assertEquals("profiles/casual.yml", ProfileApplier.resourcePath(Profile.CASUAL));
            assertEquals("profiles/smp_standard.yml", ProfileApplier.resourcePath(Profile.SMP_STANDARD));
            assertEquals("profiles/hardcore.yml", ProfileApplier.resourcePath(Profile.HARDCORE));
        }

        @Test
        @DisplayName("CUSTOM is refused: it marks a hand-edited file, it is not a preset")
        void customIsNotAPreset() {
            assertThrows(IllegalArgumentException.class, () -> ProfileApplier.resourcePath(Profile.CUSTOM));
            assertEquals(List.of(Profile.CASUAL, Profile.SMP_STANDARD, Profile.HARDCORE),
                    ProfileApplier.applicable());
        }
    }

    @Nested
    @DisplayName("backup naming")
    class BackupNaming {

        @Test
        @DisplayName("the stamp goes before the extension, so the copy still opens as YAML")
        void stampBeforeExtension() {
            assertEquals("config-20260904-153012.yml",
                    ProfileApplier.backupFileName("config.yml", AT, UTC));
        }

        @Test
        @DisplayName("a name with no extension simply gets the stamp appended")
        void noExtension() {
            assertEquals("config-20260904-153012", ProfileApplier.backupFileName("config", AT, UTC));
        }
    }

    @Nested
    @DisplayName("apply")
    class Apply {

        @Test
        @DisplayName("the configuration is replaced and the previous one is copied aside")
        void backsUpThenReplaces(@TempDir Path dataFolder) throws IOException {
            Path config = dataFolder.resolve("config.yml");
            Path backups = dataFolder.resolve(ProfileApplier.BACKUP_DIRECTORY);
            Files.writeString(config, "profile: CUSTOM\n");

            Optional<Path> backup =
                    ProfileApplier.apply(preset("profile: HARDCORE\n"), config, backups, AT, UTC);

            assertEquals("profile: HARDCORE\n", Files.readString(config));
            assertEquals("profile: CUSTOM\n", Files.readString(backup.orElseThrow()));
            assertEquals("config-20260904-153012.yml", backup.orElseThrow().getFileName().toString());
            assertEquals(backups, backup.orElseThrow().getParent());
        }

        @Test
        @DisplayName("two applies in the same second keep both backups")
        void collidingBackupsAreBothKept(@TempDir Path dataFolder) throws IOException {
            Path config = dataFolder.resolve("config.yml");
            Path backups = dataFolder.resolve(ProfileApplier.BACKUP_DIRECTORY);
            Files.writeString(config, "first\n");

            Path one = ProfileApplier.apply(preset("second\n"), config, backups, AT, UTC).orElseThrow();
            Path two = ProfileApplier.apply(preset("third\n"), config, backups, AT, UTC).orElseThrow();

            // The whole point of a backup is that experimenting with two presets in a row still
            // leaves the original recoverable, so the second must not overwrite the first.
            assertEquals("first\n", Files.readString(one));
            assertEquals("second\n", Files.readString(two));
            assertEquals("config-20260904-153012-1.yml", two.getFileName().toString());
            assertEquals("third\n", Files.readString(config));
        }

        @Test
        @DisplayName("a first run with no config.yml applies cleanly and reports no backup")
        void noExistingConfiguration(@TempDir Path dataFolder) throws IOException {
            Path config = dataFolder.resolve("config.yml");

            Optional<Path> backup = ProfileApplier.apply(preset("profile: CASUAL\n"), config,
                    dataFolder.resolve(ProfileApplier.BACKUP_DIRECTORY), AT, UTC);

            assertTrue(backup.isEmpty());
            assertEquals("profile: CASUAL\n", Files.readString(config));
        }

        @Test
        @DisplayName("nothing is left staged behind after a successful apply")
        void leavesNoTemporaryFile(@TempDir Path dataFolder) throws IOException {
            Path config = dataFolder.resolve("config.yml");
            Files.writeString(config, "old\n");

            ProfileApplier.apply(preset("new\n"), config,
                    dataFolder.resolve(ProfileApplier.BACKUP_DIRECTORY), AT, UTC);

            assertFalse(Files.exists(dataFolder.resolve("config.yml.incoming")));
        }

        @Test
        @DisplayName("the backup directory is created on demand")
        void createsBackupDirectory(@TempDir Path dataFolder) throws IOException {
            Path config = dataFolder.resolve("config.yml");
            Path backups = dataFolder.resolve("nested").resolve(ProfileApplier.BACKUP_DIRECTORY);
            Files.writeString(config, "old\n");

            ProfileApplier.apply(preset("new\n"), config, backups, AT, UTC);

            assertTrue(Files.isDirectory(backups));
        }
    }
}
