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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ninja6.antispeedrun.config.PluginConfig.Profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProfileApplier} on a real temporary directory: the backup is taken before anything is
 * written, it is named and suffixed the way the operator is promised, the replacement is
 * all-or-nothing, and the preset stream is closed however the method exits.
 *
 * <p>Plain {@code java.nio}, no Bukkit — the seam {@link ProfileApplier} exists to keep.
 *
 * <p>{@code resourcePath} is asserted here as well as in {@code PresetProfileTest}, which resolves
 * each preset through it. The overlap is deliberate: that test would fail on a wrong path, but it
 * says nothing about {@code CUSTOM} being refused or about what {@code applicable()} contains, and
 * both are part of what {@code /asr profile apply} will accept.
 */
class ProfileApplierTest {

    private static final Instant AT = Instant.parse("2026-09-04T15:30:12Z");
    private static final ZoneId ZONE = ZoneId.of("UTC");

    /** A preset stream that records whether it was closed. */
    private static final class CloseRecordingStream extends InputStream {

        private final InputStream delegate;
        private boolean closed;

        CloseRecordingStream(String content) {
            this.delegate = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            delegate.close();
        }
    }

    @Test
    @DisplayName("a successful apply backs the configuration up and replaces it, closing the preset")
    void appliesAndCloses(@TempDir Path folder) throws Exception {
        Path config = folder.resolve("config.yml");
        Files.writeString(config, "profile: SMP_STANDARD\n");
        CloseRecordingStream preset = new CloseRecordingStream("profile: HARDCORE\n");

        Optional<Path> backup = ProfileApplier.apply(
                preset, config, folder.resolve(ProfileApplier.BACKUP_DIRECTORY), AT, ZONE);

        assertEquals("profile: HARDCORE\n", Files.readString(config));
        assertEquals("profile: SMP_STANDARD\n", Files.readString(backup.orElseThrow()));
        assertTrue(preset.closed, "the preset stream must be closed on the happy path too");
        assertTrue(stagingFiles(folder).isEmpty(), "no staging file may survive a successful apply");
        // The backup lands under the documented name, in the backup directory and not beside
        // config.yml -- the server must never read a backup as a configuration.
        assertEquals("config-20260904-153012.yml", backup.orElseThrow().getFileName().toString());
        assertEquals(folder.resolve(ProfileApplier.BACKUP_DIRECTORY), backup.orElseThrow().getParent());
    }

    @Test
    @DisplayName("the filename is derived from the enum constant, so the two cannot drift")
    void resourcePathDerivedFromEnum() {
        assertEquals("profiles/casual.yml", ProfileApplier.resourcePath(Profile.CASUAL));
        assertEquals("profiles/smp_standard.yml", ProfileApplier.resourcePath(Profile.SMP_STANDARD));
        assertEquals("profiles/hardcore.yml", ProfileApplier.resourcePath(Profile.HARDCORE));
    }

    @Test
    @DisplayName("CUSTOM is refused: it marks a hand-edited file, it is not a preset")
    void customIsNotAPreset() {
        assertThrows(IllegalArgumentException.class,
                () -> ProfileApplier.resourcePath(Profile.CUSTOM));
        // Asserted as a whole list, not merely searched for CUSTOM: applicable() is what
        // tab completion offers and what /asr profile apply accepts, so an extra constant
        // slipping into it is as much a fault as CUSTOM appearing in it.
        assertEquals(List.of(Profile.CASUAL, Profile.SMP_STANDARD, Profile.HARDCORE),
                ProfileApplier.applicable());
    }

    @Test
    @DisplayName("the stamp goes before the extension, so the copy still opens as YAML")
    void stampBeforeExtension() {
        assertEquals("config-20260904-153012.yml",
                ProfileApplier.backupFileName("config.yml", AT, ZONE));
    }

    @Test
    @DisplayName("a name with no extension simply gets the stamp appended")
    void noExtension() {
        assertEquals("config-20260904-153012", ProfileApplier.backupFileName("config", AT, ZONE));
    }

    @Test
    @DisplayName("two applies in the same second keep both backups")
    void collidingBackupsAreBothKept(@TempDir Path folder) throws Exception {
        Path config = folder.resolve("config.yml");
        Path backups = folder.resolve(ProfileApplier.BACKUP_DIRECTORY);
        Files.writeString(config, "first\n");

        Path one = ProfileApplier.apply(new CloseRecordingStream("second\n"), config, backups, AT, ZONE)
                .orElseThrow();
        Path two = ProfileApplier.apply(new CloseRecordingStream("third\n"), config, backups, AT, ZONE)
                .orElseThrow();

        // The whole point of a backup is that experimenting with two presets in a row still
        // leaves the original recoverable, so the second must not overwrite the first.
        assertEquals("first\n", Files.readString(one));
        assertEquals("second\n", Files.readString(two));
        assertEquals("config-20260904-153012-1.yml", two.getFileName().toString());
        assertEquals("third\n", Files.readString(config));
    }

    @Test
    @DisplayName("a backup refuses rather than overwrites once the suffixes are exhausted")
    void collisionAttemptsAreBounded(@TempDir Path folder) throws Exception {
        Path config = folder.resolve("config.yml");
        Path backups = folder.resolve(ProfileApplier.BACKUP_DIRECTORY);
        Files.writeString(config, "original\n");
        Files.createDirectories(backups);

        // Occupy the unsuffixed name and every suffix the applier is willing to try. The bound
        // exists so a pathological directory ends in a reported failure rather than an endless
        // search, and nothing else in the suite pins it.
        String base = ProfileApplier.backupFileName("config.yml", AT, ZONE);
        Files.writeString(backups.resolve(base), "taken\n");
        for (int attempt = 1; attempt <= 100; attempt++) {
            Files.writeString(backups.resolve("config-20260904-153012-" + attempt + ".yml"), "taken\n");
        }

        assertThrows(IOException.class, () -> ProfileApplier.apply(
                new CloseRecordingStream("new\n"), config, backups, AT, ZONE));
        // The backup is taken before anything is written, so a refusal there leaves the
        // configuration exactly as it was.
        assertEquals("original\n", Files.readString(config));
        assertTrue(stagingFiles(folder).isEmpty(), "a refused apply must stage nothing");
    }

    @Test
    @DisplayName("an apply sweeps away staging files a crashed apply left behind")
    void staleStagingFilesAreSweptUp(@TempDir Path folder) throws Exception {
        Path config = folder.resolve("config.yml");
        Files.writeString(config, "old\n");
        // What a JVM killed between the copy and the move leaves: the finally in apply() never
        // ran, and the name is unique per apply, so nothing would ever reclaim these.
        Files.writeString(folder.resolve("config.yml." + UUID.randomUUID() + ".incoming"), "half\n");
        Files.writeString(folder.resolve("config.yml." + UUID.randomUUID() + ".incoming"), "half\n");
        // Not litter, and must survive: neither is a staging file for this configuration.
        Path unrelated = folder.resolve("other.yml." + UUID.randomUUID() + ".incoming");
        Path backup = folder.resolve("config.yml.bak");
        Files.writeString(unrelated, "someone else's\n");
        Files.writeString(backup, "keep me\n");

        ProfileApplier.apply(new CloseRecordingStream("new\n"), config,
                folder.resolve(ProfileApplier.BACKUP_DIRECTORY), AT, ZONE);

        assertEquals("new\n", Files.readString(config));
        assertTrue(stagingFiles(folder).stream().noneMatch(path ->
                        path.getFileName().toString().startsWith("config.yml.")),
                "an apply must leave no config.yml staging file behind, its own or an older one");
        assertTrue(Files.exists(unrelated), "the sweep must not reach another file's staging name");
        assertEquals("keep me\n", Files.readString(backup));
    }

    @Test
    @DisplayName("the backup directory is created on demand, nesting and all")
    void createsBackupDirectory(@TempDir Path folder) throws Exception {
        Path config = folder.resolve("config.yml");
        Path backups = folder.resolve("nested").resolve(ProfileApplier.BACKUP_DIRECTORY);
        Files.writeString(config, "old\n");

        ProfileApplier.apply(new CloseRecordingStream("new\n"), config, backups, AT, ZONE);

        assertTrue(Files.isDirectory(backups));
    }

    @Test
    @DisplayName("a throwing backup still closes the preset and leaves config.yml untouched")
    void failingBackupClosesThePreset(@TempDir Path folder) throws Exception {
        Path config = folder.resolve("config.yml");
        Files.writeString(config, "profile: SMP_STANDARD\n");

        // The backup directory is an existing regular file, so createDirectories inside backup(...)
        // throws before anything has been written. That is the path #74 named: the stream was
        // opened by the caller, the write below it sat in a try-with-resources, and this exit did
        // not go through it.
        Path backupDirectory = folder.resolve(ProfileApplier.BACKUP_DIRECTORY);
        Files.writeString(backupDirectory, "not a directory");

        CloseRecordingStream preset = new CloseRecordingStream("profile: HARDCORE\n");

        assertThrows(IOException.class,
                () -> ProfileApplier.apply(preset, config, backupDirectory, AT, ZONE));

        assertTrue(preset.closed, "the preset stream must be closed when backup(...) throws");
        assertEquals("profile: SMP_STANDARD\n", Files.readString(config),
                "a failed backup must leave the previous configuration exactly as it was");
    }

    @Test
    @DisplayName("no config.yml to back up is not an error")
    void firstRunHasNothingToBackUp(@TempDir Path folder) throws Exception {
        Path config = folder.resolve("config.yml");
        CloseRecordingStream preset = new CloseRecordingStream("profile: CASUAL\n");

        Optional<Path> backup = ProfileApplier.apply(
                preset, config, folder.resolve(ProfileApplier.BACKUP_DIRECTORY), AT, ZONE);

        assertTrue(backup.isEmpty());
        assertEquals("profile: CASUAL\n", Files.readString(config));
        assertTrue(preset.closed);
    }

    @Test
    @DisplayName("concurrent applies leave one whole preset, never a mixture of both")
    void concurrentAppliesDoNotInterleave(@TempDir Path folder) throws Exception {
        // #75: every apply used to stage through the same "config.yml.incoming", so two of them
        // running together could write into one file and move the result into place twice. The
        // presets are made long enough that an interleaved copy would be visible.
        String casual = "profile: CASUAL\n" + ("# casual\n".repeat(4_000));
        String hardcore = "profile: HARDCORE\n" + ("# hardcore\n".repeat(4_000));

        Path config = folder.resolve("config.yml");
        Path backups = folder.resolve(ProfileApplier.BACKUP_DIRECTORY);

        for (int round = 0; round < 20; round++) {
            Files.writeString(config, "profile: SMP_STANDARD\n");
            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = pool.submit(applying(casual, start, config, backups));
                Future<?> second = pool.submit(applying(hardcore, start, config, backups));
                first.get();
                second.get();
            } finally {
                pool.shutdownNow();
            }

            String written = Files.readString(config);
            assertTrue(written.equals(casual) || written.equals(hardcore),
                    "config.yml must be one preset or the other, never a mixture");
            assertTrue(stagingFiles(folder).isEmpty(), "no staging file may be left behind");
        }
    }

    private static Callable<Void> applying(String preset, CyclicBarrier start, Path config,
                                           Path backups) {
        return () -> {
            start.await();
            ProfileApplier.apply(new CloseRecordingStream(preset), config, backups, AT, ZONE);
            return null;
        };
    }

    /** Staging files left in {@code folder}, which a finished apply must never leave behind. */
    private static List<Path> stagingFiles(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.filter(path -> path.getFileName().toString().contains(".incoming"))
                    .toList();
        }
    }
}
