package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.ninja6.antispeedrun.config.PluginConfig.Profile;

/**
 * Backs up {@code config.yml} and replaces it with a shipped preset, for {@code /asr profile apply}.
 *
 * <p>Plain JDK: {@code java.nio} and {@code java.time} only. That is what lets the backup naming,
 * the collision handling and the replace-in-place behaviour be tested for real, on a temporary
 * directory, with none of Bukkit on the classpath — the same seam discipline
 * {@code PluginConfig} follows.
 *
 * <h2>The three names that must agree</h2>
 *
 * {@link Profile} is the command signature, the {@code profile:} key at the top of
 * {@code config.yml} is what an operator reads, and {@link #resourcePath(Profile)} derives the
 * resource filename from the enum constant rather than from a hand-kept table. Renaming a constant
 * therefore moves the filename with it and cannot silently drift, which is the standing risk #57
 * names. {@code ProfilePresetTest} closes the loop by asserting each shipped preset declares the
 * profile its own filename implies.
 *
 * <h2>Threading</h2>
 *
 * Every method here touches the filesystem. None may run on a Folia region thread; the command
 * dispatches them onto the {@code AsyncScheduler}.
 *
 * <p>{@link #apply} is serialised against itself, so two operators applying different presets at
 * the same moment cannot interleave into a {@code config.yml} that is neither of them (#75). That
 * lock is only ever contended on the {@code AsyncScheduler}, where blocking is legal.
 */
public final class ProfileApplier {

    /** Directory, relative to the plugin's data folder, that backups are written to. */
    public static final String BACKUP_DIRECTORY = "backups";

    /**
     * Backup timestamp format. Sortable, filename-safe on Windows (no colons), second resolution.
     */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** How many suffixed names to try when two backups land in the same second. */
    private static final int MAX_COLLISION_ATTEMPTS = 100;

    /**
     * Serialises {@link #apply}. Static because the thing being guarded is {@code config.yml}
     * itself, of which the server has exactly one; a per-instance lock would guard nothing, as this
     * class is never instantiated. Taken only on the {@code AsyncScheduler}.
     */
    private static final Object APPLY_LOCK = new Object();

    private ProfileApplier() {
    }

    /** The profiles a preset exists for: everything except {@link Profile#CUSTOM}. */
    public static List<Profile> applicable() {
        return List.of(Profile.CASUAL, Profile.SMP_STANDARD, Profile.HARDCORE);
    }

    /**
     * The classpath path of the preset for {@code profile}, derived from the constant's own name.
     *
     * @throws IllegalArgumentException for {@link Profile#CUSTOM}, which is not a preset but the
     *                                  marker for a configuration an operator has hand-edited away
     *                                  from one — there is nothing to copy
     */
    public static String resourcePath(Profile profile) {
        Objects.requireNonNull(profile, "profile");
        if (profile == Profile.CUSTOM) {
            throw new IllegalArgumentException("CUSTOM is not a preset: it means the configuration "
                    + "has been hand-edited away from one, so there is no resource to apply");
        }
        return "profiles/" + profile.name().toLowerCase(Locale.ROOT) + ".yml";
    }

    /**
     * The backup filename for a configuration named {@code configFileName}, taken at {@code at}.
     *
     * <p>Example: {@code config-20260904-153012.yml}. The stamp goes before the extension so the
     * file still opens as YAML in an editor, and the backups live in their own directory so the
     * server never reads one as a configuration.
     */
    public static String backupFileName(String configFileName, Instant at, ZoneId zone) {
        Objects.requireNonNull(configFileName, "configFileName");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(zone, "zone");
        String stamp = STAMP.format(at.atZone(zone));
        int dot = configFileName.lastIndexOf('.');
        if (dot <= 0) {
            return configFileName + "-" + stamp;
        }
        return configFileName.substring(0, dot) + "-" + stamp + configFileName.substring(dot);
    }

    /**
     * Copies {@code configFile} into {@code backupDirectory} under a timestamped name.
     *
     * <p>Two backups inside the same second get {@code -1}, {@code -2} and so on appended, rather
     * than the second overwriting the first: the whole value of a backup is that applying two
     * presets in quick succession while experimenting still leaves the original recoverable.
     *
     * @return the backup written, or empty when there was no configuration file to back up (a first
     *         run, before {@code saveDefaultConfig} — not an error)
     */
    public static Optional<Path> backup(Path configFile, Path backupDirectory, Instant at, ZoneId zone)
            throws IOException {
        Objects.requireNonNull(configFile, "configFile");
        Objects.requireNonNull(backupDirectory, "backupDirectory");
        if (!Files.isRegularFile(configFile)) {
            return Optional.empty();
        }
        Files.createDirectories(backupDirectory);

        String base = backupFileName(configFile.getFileName().toString(), at, zone);
        Path target = backupDirectory.resolve(base);
        for (int attempt = 1; Files.exists(target) && attempt <= MAX_COLLISION_ATTEMPTS; attempt++) {
            int dot = base.lastIndexOf('.');
            target = backupDirectory.resolve(dot <= 0
                    ? base + "-" + attempt
                    : base.substring(0, dot) + "-" + attempt + base.substring(dot));
        }
        if (Files.exists(target)) {
            throw new IOException("Could not find an unused backup name in " + backupDirectory
                    + " after " + MAX_COLLISION_ATTEMPTS + " attempts");
        }
        Files.copy(configFile, target, StandardCopyOption.COPY_ATTRIBUTES);
        return Optional.of(target);
    }

    /**
     * Backs up {@code configFile} and overwrites it with {@code preset}.
     *
     * <p>Order matters and is the point: the backup is taken and completed <em>before</em> anything
     * is written to the configuration, so a failure to read the preset or to write the file leaves
     * both the original configuration and a copy of it intact. The new configuration is staged in a
     * temporary file and moved into place, so a partial write cannot leave a truncated
     * {@code config.yml} for the reload that follows to reject.
     *
     * <p>This does not reload anything. The caller applies the file by calling
     * {@code reloadConfiguration()} afterwards, on a thread where that is legal.
     *
     * <p>{@code preset} is closed on <em>every</em> path out of this method, including one where the
     * backup itself throws. It used to be closed only by the try-with-resources around the write
     * below it, so an unwritable data folder left the stream open (#74).
     *
     * @param preset          the preset document; closed by this method
     * @param configFile      the configuration to replace
     * @param backupDirectory where the backup is written; created if absent
     * @param at              the moment stamped into the backup name
     * @param zone            the zone that stamp is rendered in; the server's own
     * @return the backup written, or empty when there was no existing configuration to back up
     */
    public static Optional<Path> apply(InputStream preset, Path configFile, Path backupDirectory,
                                       Instant at, ZoneId zone) throws IOException {
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(configFile, "configFile");

        // The stream is the outermost resource deliberately: backup(...) below can throw, and
        // before this it did so with the stream still open.
        try (InputStream in = preset) {
            // Serialised so that backup-then-replace is one unit. Two operators applying different
            // presets in the same instant must leave one whole preset on disk with a backup that
            // corresponds to it, not a config.yml assembled from both (#75). The lock is only ever
            // taken on the AsyncScheduler -- apply() is never called from a region thread -- so
            // blocking here blocks nothing that ticks.
            synchronized (APPLY_LOCK) {
                return replace(in, configFile, backupDirectory, at, zone);
            }
        }
    }

    /** The backup-then-replace body of {@link #apply}, with the preset stream already owned. */
    private static Optional<Path> replace(InputStream preset, Path configFile, Path backupDirectory,
                                          Instant at, ZoneId zone) throws IOException {
        Optional<Path> backup = backup(configFile, backupDirectory, at, zone);

        Path parent = configFile.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException(configFile + " has no parent directory to stage a write in");
        }
        Files.createDirectories(parent);

        // A name of this apply's own, rather than the one shared "config.yml.incoming": that
        // single path was the second half of #75, since two applies staging through it could
        // interleave into a config.yml that was neither preset. The staging file is a sibling of
        // the configuration so the move below stays within one filesystem and can be atomic.
        //
        // Named rather than Files.createTempFile, which pre-creates the file owner-only on POSIX:
        // the staging file becomes config.yml, so it would hand the operator a configuration they
        // can no longer read as anyone but the server user.
        Path staged = configFile.resolveSibling(
                configFile.getFileName() + "." + UUID.randomUUID() + ".incoming");
        boolean moved = false;
        try {
            Files.copy(preset, staged, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(staged, configFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                // A half-written staging file is litter in the data folder and nothing else; the
                // failure that got us here is what the caller is told about, so a failure to tidy
                // up must not replace it.
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // Nothing useful to do, and the real exception is already propagating.
                }
            }
        }
        return backup;
    }
}
