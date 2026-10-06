package com.ninja6.antispeedrun.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The personal credit record (#213). */
class PersonalCreditStoreTest {

    private static final Logger LOGGER = Logger.getLogger(PersonalCreditStoreTest.class.getName());

    private static final class InMemoryStateFile implements StateFile {

        private Map<String, Object> document = Map.of();
        private int saves;
        private IOException failLoadWith;
        private final List<Map<String, Object>> quarantined = new ArrayList<>();

        @Override
        public synchronized Map<String, Object> load() throws IOException {
            if (failLoadWith != null) {
                throw failLoadWith;
            }
            return document;
        }

        @Override
        public synchronized void save(Map<String, Object> document) {
            saves++;
            this.document = Map.copyOf(document);
        }

        @Override
        public synchronized Optional<String> quarantine() {
            quarantined.add(document);
            document = Map.of();
            failLoadWith = null;
            return Optional.of("personal-credits.yml.corrupt-test");
        }
    }

    private static PersonalCreditStore store(InMemoryStateFile file) {
        return new PersonalCreditStore(LOGGER, file, Runnable::run);
    }

    @Test
    @DisplayName("every credit survives a restart, for the player who earned it only")
    void survivesRestart() {
        InMemoryStateFile file = new InMemoryStateFile();
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        PersonalCreditStore before = store(file);
        assertTrue(before.loadNow());
        for (PersonalCredit credit : PersonalCredit.values()) {
            assertTrue(before.record(player, credit, CreditSource.ACTION));
        }

        PersonalCreditStore after = store(file);
        assertTrue(after.loadNow());
        for (PersonalCredit credit : PersonalCredit.values()) {
            assertTrue(after.has(player, credit, false), credit.id());
            assertFalse(after.has(other, credit, true), credit.id());
        }
    }

    @Test
    @DisplayName("a repeated credit writes nothing")
    void repeatWritesNothing() {
        InMemoryStateFile file = new InMemoryStateFile();
        PersonalCreditStore store = store(file);
        store.loadNow();
        UUID player = UUID.randomUUID();
        assertTrue(store.record(player, PersonalCredit.MINE_STONE, CreditSource.ACTION));
        assertFalse(store.record(player, PersonalCredit.MINE_STONE, CreditSource.ACTION));
        assertEquals(1, file.saves);
    }

    @Test
    @DisplayName("a loot record counts only with the setting on, and only for iron and diamonds")
    void lootCountsOnlyWhenAllowed() {
        PersonalCreditStore store = store(new InMemoryStateFile());
        store.loadNow();
        UUID player = UUID.randomUUID();
        for (PersonalCredit credit : PersonalCredit.values()) {
            store.record(player, credit, CreditSource.LOOT);
        }
        for (PersonalCredit credit : PersonalCredit.values()) {
            assertFalse(store.has(player, credit, false), credit.id());
            boolean lootable = credit == PersonalCredit.MINED_IRON || credit == PersonalCredit.MINE_DIAMOND;
            assertEquals(lootable, store.has(player, credit, true), credit.id());
            assertEquals(lootable, credit.lootable(), credit.id());
        }
        assertEquals(Set.of(CreditSource.LOOT), store.sources(player, PersonalCredit.MINED_IRON));
    }

    @Test
    @DisplayName("an action and a loot source are kept side by side")
    void bothSourcesKept() {
        InMemoryStateFile file = new InMemoryStateFile();
        PersonalCreditStore store = store(file);
        store.loadNow();
        UUID player = UUID.randomUUID();
        assertTrue(store.record(player, PersonalCredit.MINE_DIAMOND, CreditSource.LOOT));
        assertTrue(store.record(player, PersonalCredit.MINE_DIAMOND, CreditSource.ACTION));
        assertEquals(List.of("action", "loot"),
                file.document.get(PersonalCreditStore.KEY_PREFIX + player + ".mine-diamond"));

        PersonalCreditStore after = store(file);
        after.loadNow();
        assertEquals(Set.of(CreditSource.ACTION, CreditSource.LOOT),
                after.sources(player, PersonalCredit.MINE_DIAMOND));
    }

    @Test
    @DisplayName("the smelt_iron sub-credits are separate and both map to story/smelt_iron")
    void smeltIronSubCredits() {
        PersonalCreditStore store = store(new InMemoryStateFile());
        store.loadNow();
        UUID player = UUID.randomUUID();
        store.record(player, PersonalCredit.MINED_IRON, CreditSource.ACTION);
        assertTrue(store.has(player, PersonalCredit.MINED_IRON, false));
        assertFalse(store.has(player, PersonalCredit.SMELTED_IRON, true));
        assertEquals("minecraft:story/smelt_iron", PersonalCredit.MINED_IRON.advancement());
        assertEquals("minecraft:story/smelt_iron", PersonalCredit.SMELTED_IRON.advancement());
    }

    @Test
    @DisplayName("unknown players, credits, sources and shapes are skipped on load")
    void skipsUnrecognised() {
        UUID player = UUID.randomUUID();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put(PersonalCreditStore.KEY_PREFIX + player + ".mine-stone", List.of("action", "gift"));
        document.put(PersonalCreditStore.KEY_PREFIX + player + ".mine-netherite", List.of("action"));
        document.put(PersonalCreditStore.KEY_PREFIX + player + ".iron-tools", "action");
        document.put(PersonalCreditStore.KEY_PREFIX + player + ".obtain-blaze-rod", List.of("gift"));
        document.put(PersonalCreditStore.KEY_PREFIX + "not-a-uuid.mine-stone", List.of("action"));
        document.put(PersonalCreditStore.KEY_PREFIX + player, List.of("action"));
        document.put("other." + player + ".mine-stone", List.of("action"));

        Map<UUID, Map<PersonalCredit, Set<CreditSource>>> read = PersonalCreditStore.fromDocument(document);
        assertEquals(Map.of(player, Map.of(PersonalCredit.MINE_STONE, Set.of(CreditSource.ACTION))), read);
    }

    @Test
    @DisplayName("the document is sorted and round-trips")
    void documentRoundTrips() {
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        Map<UUID, Map<PersonalCredit, Set<CreditSource>>> credits = Map.of(
                b, Map.of(PersonalCredit.OBTAIN_BLAZE_ROD, Set.of(CreditSource.ACTION)),
                a, Map.of(PersonalCredit.MINE_STONE, Set.of(CreditSource.ACTION),
                        PersonalCredit.MINED_IRON, Set.of(CreditSource.LOOT, CreditSource.ACTION)));
        Map<String, Object> document = PersonalCreditStore.toDocument(credits);
        assertEquals(List.of(
                PersonalCreditStore.KEY_PREFIX + a + ".mine-stone",
                PersonalCreditStore.KEY_PREFIX + a + ".mined-iron",
                PersonalCreditStore.KEY_PREFIX + b + ".obtain-blaze-rod"),
                List.copyOf(document.keySet()));
        assertEquals(credits, PersonalCreditStore.fromDocument(document));
    }

    @Test
    @DisplayName("an unreadable file is kept until the first write, then moved aside")
    void damagedFileQuarantinedBeforeWrite() {
        InMemoryStateFile file = new InMemoryStateFile();
        file.document = Map.of("damaged", "bytes");
        file.failLoadWith = new IOException("truncated");
        PersonalCreditStore store = store(file);
        assertFalse(store.loadNow());
        assertTrue(store.isAwaitingQuarantine());
        assertEquals(0, file.saves);

        UUID player = UUID.randomUUID();
        store.record(player, PersonalCredit.IRON_TOOLS, CreditSource.ACTION);
        assertEquals(List.of(Map.of("damaged", "bytes")), file.quarantined);
        assertFalse(store.isAwaitingQuarantine());
        assertTrue(file.document.containsKey(PersonalCreditStore.KEY_PREFIX + player + ".iron-tools"));
    }

    @Test
    @DisplayName("credits recorded concurrently from many threads are all kept")
    void concurrentRecords() throws InterruptedException {
        InMemoryStateFile file = new InMemoryStateFile();
        ExecutorService io = Executors.newSingleThreadExecutor();
        PersonalCreditStore store = new PersonalCreditStore(LOGGER, file, io);
        store.loadNow();
        int threads = 8;
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            players.add(UUID.randomUUID());
        }
        ExecutorService regions = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (UUID player : players) {
            regions.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (PersonalCredit credit : PersonalCredit.values()) {
                    store.record(player, credit, CreditSource.ACTION);
                }
            });
        }
        start.countDown();
        regions.shutdown();
        assertTrue(regions.awaitTermination(10, TimeUnit.SECONDS));
        io.shutdown();
        assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));

        PersonalCreditStore after = store(file);
        after.loadNow();
        for (UUID player : players) {
            for (PersonalCredit credit : PersonalCredit.values()) {
                assertTrue(after.has(player, credit, false));
            }
        }
    }

    @Test
    @DisplayName("stored ids are stable")
    void storedIdsStable() {
        for (PersonalCredit credit : PersonalCredit.values()) {
            assertEquals(Optional.of(credit), PersonalCredit.fromId(credit.id()));
        }
        for (CreditSource source : CreditSource.values()) {
            assertEquals(Optional.of(source), CreditSource.fromId(source.id()));
        }
        assertEquals(Optional.empty(), PersonalCredit.fromId("smelt-iron"));
    }
}
