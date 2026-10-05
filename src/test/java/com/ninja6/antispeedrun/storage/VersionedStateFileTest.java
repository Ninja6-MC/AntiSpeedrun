package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link VersionedStateFile}: the {@code state-version} stamp on the plugin-written state files (#194). */
class VersionedStateFileTest {

    private static final Logger LOGGER = Logger.getLogger(VersionedStateFileTest.class.getName());

    private static final class InMemoryStateFile implements StateFile {

        private Map<String, Object> document;
        private int saves;
        private int quarantines;

        InMemoryStateFile(Map<String, Object> document) {
            this.document = document;
        }

        @Override
        public Map<String, Object> load() {
            return document;
        }

        @Override
        public void save(Map<String, Object> document) {
            saves++;
            this.document = new LinkedHashMap<>(document);
        }

        @Override
        public Optional<String> quarantine() {
            quarantines++;
            return Optional.of("moved");
        }
    }

    private static Map<String, Object> unlocks() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("dimension-unlocks.nether", 1_700_000_000_000L);
        return document;
    }

    @Test
    @DisplayName("an unversioned file is read as version 1 and stamped with its data unchanged")
    void stampsUnversionedFile() throws IOException {
        InMemoryStateFile raw = new InMemoryStateFile(unlocks());
        VersionedStateFile file = new VersionedStateFile("state.yml", raw);

        assertTrue(file.migrate());

        assertEquals(1, raw.saves);
        assertEquals(List.of(VersionedStateFile.VERSION_KEY, "dimension-unlocks.nether"),
                List.copyOf(raw.document.keySet()));
        assertEquals(VersionedStateFile.STATE_VERSION, raw.document.get(VersionedStateFile.VERSION_KEY));
        assertEquals(unlocks(), file.load());
    }

    @Test
    @DisplayName("a current file, an empty one and an absent one are not rewritten")
    void leavesCurrentAndEmptyFilesAlone() throws IOException {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put(VersionedStateFile.VERSION_KEY, 1);
        current.putAll(unlocks());
        InMemoryStateFile versioned = new InMemoryStateFile(current);
        InMemoryStateFile empty = new InMemoryStateFile(Map.of());

        assertFalse(new VersionedStateFile("state.yml", versioned).migrate());
        assertFalse(new VersionedStateFile("state.yml", empty).migrate());

        assertEquals(0, versioned.saves);
        assertEquals(0, empty.saves);
    }

    @Test
    @DisplayName("load strips the version key, so a store sees only its own keys")
    void loadStripsVersion() throws IOException {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put(VersionedStateFile.VERSION_KEY, 1L);
        current.putAll(unlocks());

        assertEquals(unlocks(), new VersionedStateFile("state.yml", new InMemoryStateFile(current)).load());
    }

    @Test
    @DisplayName("every save stamps the current version first, including an empty document")
    void saveStamps() throws IOException {
        InMemoryStateFile raw = new InMemoryStateFile(Map.of());
        VersionedStateFile file = new VersionedStateFile("portal-locks.yml", raw);

        file.save(Map.of());
        assertEquals(Map.of(VersionedStateFile.VERSION_KEY, VersionedStateFile.STATE_VERSION), raw.document);

        Map<String, Object> smuggled = new LinkedHashMap<>(unlocks());
        smuggled.put(VersionedStateFile.VERSION_KEY, 99);
        file.save(smuggled);
        assertEquals(VersionedStateFile.STATE_VERSION, raw.document.get(VersionedStateFile.VERSION_KEY));
        assertEquals(VersionedStateFile.VERSION_KEY, raw.document.keySet().iterator().next());
    }

    @Test
    @DisplayName("a file from a newer version is refused and never written or moved aside")
    void refusesNewerVersion() {
        Map<String, Object> newer = new LinkedHashMap<>();
        newer.put(VersionedStateFile.VERSION_KEY, VersionedStateFile.STATE_VERSION + 1);
        newer.putAll(unlocks());
        InMemoryStateFile raw = new InMemoryStateFile(newer);
        VersionedStateFile file = new VersionedStateFile("dragon-fights.yml", raw);

        StateVersionException refused = assertThrows(StateVersionException.class, file::migrate);
        assertTrue(refused.getMessage().contains("dragon-fights.yml"));
        assertThrows(StateVersionException.class, file::load);
        assertThrows(StateVersionException.class, () -> file.save(Map.of()));
        assertThrows(StateVersionException.class, file::quarantine);

        assertEquals(0, raw.saves);
        assertEquals(0, raw.quarantines);
        assertEquals(newer, raw.document);
    }

    @Test
    @DisplayName("a version that is not a whole number of at least 1 is refused")
    void refusesMalformedVersion() {
        for (Object bad : List.of(0, -1, "1", "two", 1.5)) {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put(VersionedStateFile.VERSION_KEY, bad);
            InMemoryStateFile raw = new InMemoryStateFile(document);

            assertThrows(StateVersionException.class,
                    () -> new VersionedStateFile("state.yml", raw).migrate(), String.valueOf(bad));
            assertEquals(0, raw.saves, String.valueOf(bad));
        }
    }

    @Test
    @DisplayName("migrating the set stamps nothing when any file is from a newer version, even ones listed before it")
    void migrateAllIsAllOrNothing() {
        InMemoryStateFile first = new InMemoryStateFile(unlocks());
        Map<String, Object> newer = new LinkedHashMap<>();
        newer.put(VersionedStateFile.VERSION_KEY, VersionedStateFile.STATE_VERSION + 1);
        InMemoryStateFile second = new InMemoryStateFile(newer);
        InMemoryStateFile third = new InMemoryStateFile(unlocks());

        assertThrows(StateVersionException.class, () -> VersionedStateFile.migrateAll(List.of(
                new VersionedStateFile("state.yml", first),
                new VersionedStateFile("dragon-fights.yml", second),
                new VersionedStateFile("portal-locks.yml", third))));

        assertEquals(0, first.saves);
        assertEquals(0, second.saves);
        assertEquals(0, third.saves);
        assertEquals(unlocks(), first.document);
    }

    @Test
    @DisplayName("migrating the set stamps every unversioned file and reports which, skipping current ones")
    void migrateAllStampsUnversioned() throws IOException {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put(VersionedStateFile.VERSION_KEY, 1);
        VersionedStateFile unversioned = new VersionedStateFile("state.yml", new InMemoryStateFile(unlocks()));
        VersionedStateFile versioned = new VersionedStateFile("portal-locks.yml", new InMemoryStateFile(current));
        VersionedStateFile empty = new VersionedStateFile("dragon-fights.yml", new InMemoryStateFile(Map.of()));

        assertEquals(List.of(unversioned), VersionedStateFile.migrateAll(List.of(unversioned, versioned, empty)));
    }

    @Test
    @DisplayName("a store over a refused file keeps the file intact instead of moving it aside")
    void storeCannotQuarantineRefusedFile() {
        Map<String, Object> newer = new LinkedHashMap<>();
        newer.put(VersionedStateFile.VERSION_KEY, VersionedStateFile.STATE_VERSION + 1);
        InMemoryStateFile raw = new InMemoryStateFile(newer);
        ReinforcedFightStore store = new ReinforcedFightStore(
                LOGGER, new VersionedStateFile("dragon-fights.yml", raw), Runnable::run);

        assertFalse(store.loadNow());
        store.markReinforced(UUID.randomUUID(), 1L);

        assertEquals(0, raw.quarantines);
        assertEquals(0, raw.saves);
        assertTrue(store.isAwaitingQuarantine());
    }

    @Test
    @DisplayName("on disk: a v0.2.0 dragon-fights.yml is stamped and still loads its fights")
    void stampsYamlFileOnDisk(@TempDir Path folder) throws IOException {
        UUID world = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Path path = folder.resolve("dragon-fights.yml");
        Files.writeString(path, "reinforced-fights:\n  " + world + ": 1700000000000\n",
                StandardCharsets.UTF_8);
        VersionedStateFile file = new VersionedStateFile("dragon-fights.yml", new YamlStateFile(path));

        assertTrue(file.migrate());
        assertTrue(Files.readString(path, StandardCharsets.UTF_8).contains("state-version: 1"));
        assertFalse(file.migrate());

        ReinforcedFightStore store = new ReinforcedFightStore(LOGGER, file, Runnable::run);
        assertTrue(store.loadNow());
        assertTrue(store.isReinforced(world));
    }

    @Test
    @DisplayName("on disk: a newer state.yml is left byte for byte as it was")
    void newerYamlFileUntouched(@TempDir Path folder) throws IOException {
        Path path = folder.resolve("state.yml");
        String text = "state-version: 2\ndimension-unlocks:\n  nether: 1700000000000\n";
        Files.writeString(path, text, StandardCharsets.UTF_8);
        VersionedStateFile file = new VersionedStateFile("state.yml", new YamlStateFile(path));

        assertThrows(StateVersionException.class, file::migrate);
        DimensionUnlockStore store = new DimensionUnlockStore(LOGGER, file, Runnable::run);
        assertFalse(store.loadNow());
        store.unlock(DimensionUnlock.THE_END, 1L);

        assertEquals(text, Files.readString(path, StandardCharsets.UTF_8));
        try (var siblings = Files.list(folder)) {
            assertEquals(List.of(path), siblings.toList());
        }
    }
}
