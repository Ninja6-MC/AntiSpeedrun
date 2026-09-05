package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * A {@link StateFile} backed by one YAML document on disk.
 *
 * <p>The only Bukkit-bound class in this package's server-wide half, and it is kept to exactly two
 * jobs so that everything worth testing lives above it: turn a {@code YamlConfiguration} into a
 * flat map, and turn a flat map back into a file.
 *
 * <p>{@code YamlConfiguration} rather than SnakeYAML directly because SnakeYAML is a test-scope
 * dependency in this build — nothing in {@code src/main} may import it — and Paper already ships
 * the parser.
 *
 * <h2>Writes are atomic, and that is not decoration</h2>
 *
 * The document is rendered to a string, written to a sibling temporary file and then moved over the
 * target. A crash or a full disk partway through a direct write would otherwise leave a truncated
 * {@code state.yml}, and a truncated unlock file is indistinguishable from a server whose
 * dimensions were never opened — which is precisely the restart-loses-state failure this package
 * exists to prevent.
 */
public final class YamlStateFile implements StateFile {

    /** Stamp appended to a quarantined document. Sortable, and filename-safe on Windows. */
    private static final DateTimeFormatter CORRUPT_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** How many suffixed names to try when two quarantines land in the same second. */
    private static final int MAX_COLLISION_ATTEMPTS = 100;

    private final Path file;

    /**
     * @param file the document, typically {@code <dataFolder>/state.yml}. Its parent directory is
     *             created on the first write; it need not exist yet
     */
    public YamlStateFile(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** The document's path, for log lines. */
    public Path path() {
        return file;
    }

    @Override
    public Map<String, Object> load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (InvalidConfigurationException malformed) {
            throw new IOException(file + " is not valid YAML: " + malformed.getMessage(), malformed);
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) {
                flat.put(key, yaml.get(key));
            }
        }
        return Map.copyOf(flat);
    }

    /**
     * Renames the unreadable document to {@code state.yml.corrupt-<timestamp>} beside itself.
     *
     * <p>A rename rather than a copy, so the damaged bytes exist in exactly one place and the next
     * {@link #save} writes a clean file rather than appending to a broken one. The timestamp keeps
     * a second incident from overwriting the evidence of the first.
     */
    @Override
    public Optional<String> quarantine() throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String stamp = CORRUPT_STAMP.format(LocalDateTime.now());
        Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + stamp);
        for (int attempt = 1; Files.exists(target) && attempt <= MAX_COLLISION_ATTEMPTS; attempt++) {
            target = file.resolveSibling(file.getFileName() + ".corrupt-" + stamp + "-" + attempt);
        }
        if (Files.exists(target)) {
            throw new IOException("Could not find an unused quarantine name beside " + file);
        }
        Files.move(file, target);
        return Optional.of(target.getFileName().toString());
    }

    @Override
    public void save(Map<String, Object> document) throws IOException {
        Objects.requireNonNull(document, "document");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(java.util.List.of(
                "AntiSpeedrun server-wide state. Written by the plugin; safe to read, edit at your",
                "own risk. Player-scoped state (bypass grants, journey book delivery) is not here --",
                "it lives in each player's persistent data container."));
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            yaml.set(entry.getKey(), entry.getValue());
        }

        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            // Some filesystems cannot promise it. A non-atomic replace is still better than
            // writing the target in place, because the render already succeeded by this point.
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
