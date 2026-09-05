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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProfileApplier#apply} on a real temporary directory: the backup is taken before anything
 * is written, the replacement is all-or-nothing, and the preset stream is closed however the method
 * exits.
 *
 * <p>Plain {@code java.nio}, no Bukkit — the seam {@link ProfileApplier} exists to keep.
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
