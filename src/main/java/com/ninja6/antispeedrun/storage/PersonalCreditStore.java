package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The personal-action credits each player has earned (#213), the record the protected advancement
 * keys are answered from (the Amendment of {@code docs/provenance-model.md}).
 *
 * <p>Keyed by UUID and never by {@code Player}: a credit is written from whichever region thread
 * saw the action, which on Folia need not own the player, and is read for players who are offline.
 * The player's persistent data container cannot serve either case, which is why this is a file.
 *
 * <h2>The document</h2>
 *
 * Kept in its own file, {@code personal-credits.yml}, for the reason {@link ReinforcedFightStore}
 * gives. One key per player and credit, {@code personal-credits.<uuid>.<credit>}, holding the list
 * of {@link CreditSource sources} it was earned through, such as {@code [action, loot]}. A player
 * with no credits has no keys. Keys that are not a UUID and a known credit, values that are not a
 * list, and unknown sources are skipped on load.
 *
 * <p>Credits are only ever added here. Nothing the recorder sees takes one away; an administrator's
 * grant or reset is a separate operation on top of {@link #record}.
 *
 * <h2>Threading and damage</h2>
 *
 * The contract of {@link ExploredStructureStore}: {@link #sources} and {@link #has} are one
 * {@code volatile} read, legal from any thread; {@link #record} changes the live record under a
 * private lock, publishes it at once and hands the write to the I/O executor, and only when
 * something changed, so a player mining a hundred stone writes once. {@link #loadNow()} is
 * synchronous and runs in {@code onEnable}. An unreadable file is left untouched until the first
 * write, which moves it aside before writing a new one.
 */
public final class PersonalCreditStore {

    /** Prefix of every key in the document. */
    public static final String KEY_PREFIX = "personal-credits.";

    private final Logger logger;
    private final StateFile file;
    private final Executor ioExecutor;

    private final Object stateLock = new Object();
    private final Object writeLock = new Object();

    /** Player to credit to sources. Replaced wholesale, never mutated, so reads need no lock. */
    private volatile Map<UUID, Map<PersonalCredit, Set<CreditSource>>> credits = Map.of();

    private volatile boolean writesBlocked;

    /**
     * @param ioExecutor runs the file writes; never a region thread. Tests pass {@code Runnable::run}
     */
    public PersonalCreditStore(Logger logger, StateFile file, Executor ioExecutor) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.file = Objects.requireNonNull(file, "file");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /**
     * Reads the file in, synchronously. Call once from {@code onEnable}.
     *
     * @return {@code false} if the file existed and could not be read, in which case the store starts
     *         empty and the file is preserved
     */
    public boolean loadNow() {
        try {
            this.credits = fromDocument(file.load());
            this.writesBlocked = false;
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "Could not read the personal credit record; starting with none "
                    + "recorded. The damaged file has NOT been overwritten: it will be moved aside "
                    + "under a .corrupt name before the next write. Cause: " + failure.getMessage(),
                    failure);
            this.credits = Map.of();
            this.writesBlocked = true;
            return false;
        }
    }

    /** Whether the damaged file from a failed {@link #loadNow()} is still waiting to be moved aside. */
    public boolean isAwaitingQuarantine() {
        return writesBlocked;
    }

    /** The sources {@code player} has earned {@code credit} through; empty if none. Any thread. */
    public Set<CreditSource> sources(UUID player, PersonalCredit credit) {
        Objects.requireNonNull(credit, "credit");
        Map<PersonalCredit, Set<CreditSource>> earned =
                credits.get(Objects.requireNonNull(player, "player"));
        if (earned == null) {
            return Set.of();
        }
        return earned.getOrDefault(credit, Set.of());
    }

    /**
     * Whether {@code player} has earned {@code credit}. A {@link CreditSource#LOOT} record counts
     * only when {@code countLoot} is set and the credit is {@link PersonalCredit#lootable() lootable}.
     * Any thread.
     */
    public boolean has(UUID player, PersonalCredit credit, boolean countLoot) {
        Set<CreditSource> earned = sources(player, credit);
        return earned.contains(CreditSource.ACTION)
                || (countLoot && credit.lootable() && earned.contains(CreditSource.LOOT));
    }

    /**
     * Records that {@code player} earned {@code credit} through {@code source}, and persists it.
     * Legal from any thread, for an online or offline player.
     *
     * @return {@code true} if this is new; {@code false} if it was already recorded, in which case
     *         nothing is written
     */
    public boolean record(UUID player, PersonalCredit credit, CreditSource source) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(credit, "credit");
        Objects.requireNonNull(source, "source");
        synchronized (stateLock) {
            Map<UUID, Map<PersonalCredit, Set<CreditSource>>> current = credits;
            Map<PersonalCredit, Set<CreditSource>> before = current.getOrDefault(player, Map.of());
            Set<CreditSource> sources = before.getOrDefault(credit, Set.of());
            if (sources.contains(source)) {
                return false;
            }
            EnumSet<CreditSource> grown = EnumSet.of(source);
            grown.addAll(sources);
            Map<PersonalCredit, Set<CreditSource>> after = new EnumMap<>(PersonalCredit.class);
            after.putAll(before);
            after.put(credit, Set.copyOf(grown));
            Map<UUID, Map<PersonalCredit, Set<CreditSource>>> next = new HashMap<>(current);
            next.put(player, Map.copyOf(after));
            this.credits = Map.copyOf(next);
        }
        persist();
        return true;
    }

    /** The document {@code credits} is written as, sorted so the file diffs cleanly. */
    static Map<String, Object> toDocument(Map<UUID, Map<PersonalCredit, Set<CreditSource>>> credits) {
        Map<String, Object> document = new LinkedHashMap<>();
        credits.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(player -> {
                    for (PersonalCredit credit : PersonalCredit.values()) {
                        Set<CreditSource> sources = player.getValue().get(credit);
                        if (sources == null || sources.isEmpty()) {
                            continue;
                        }
                        List<String> ids = EnumSet.copyOf(sources).stream()
                                .map(CreditSource::id)
                                .toList();
                        document.put(KEY_PREFIX + player.getKey() + "." + credit.id(), ids);
                    }
                });
        return document;
    }

    /** Reads a document back, skipping anything it does not recognise. */
    static Map<UUID, Map<PersonalCredit, Set<CreditSource>>> fromDocument(Map<String, Object> document) {
        Map<UUID, Map<PersonalCredit, Set<CreditSource>>> credits = new HashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(KEY_PREFIX) || !(entry.getValue() instanceof Collection<?> values)) {
                continue;
            }
            String rest = key.substring(KEY_PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot < 0) {
                continue;
            }
            UUID player;
            try {
                player = UUID.fromString(rest.substring(0, dot));
            } catch (IllegalArgumentException notUuid) {
                continue;
            }
            PersonalCredit credit = PersonalCredit.fromId(rest.substring(dot + 1)).orElse(null);
            if (credit == null) {
                continue;
            }
            EnumSet<CreditSource> sources = EnumSet.noneOf(CreditSource.class);
            for (Object value : values) {
                if (value instanceof String id) {
                    CreditSource.fromId(id).ifPresent(sources::add);
                }
            }
            if (!sources.isEmpty()) {
                credits.computeIfAbsent(player, k -> new EnumMap<>(PersonalCredit.class))
                        .put(credit, Set.copyOf(sources));
            }
        }
        Map<UUID, Map<PersonalCredit, Set<CreditSource>>> frozen = new HashMap<>();
        credits.forEach((player, earned) -> frozen.put(player, Map.copyOf(earned)));
        return Map.copyOf(frozen);
    }

    /** Queues a write of the live record. Each write persists whatever is live when it runs. */
    void persist() {
        ioExecutor.execute(() -> {
            synchronized (writeLock) {
                if (!clearForWriting()) {
                    return;
                }
                try {
                    file.save(toDocument(credits));
                } catch (IOException | RuntimeException failure) {
                    logger.log(Level.SEVERE, "Could not persist the personal credit record. Credits "
                            + "earned since the last successful write will be missing after a "
                            + "restart. Cause: " + failure.getMessage(), failure);
                }
            }
        });
    }

    private boolean clearForWriting() {
        if (!writesBlocked) {
            return true;
        }
        try {
            String moved = file.quarantine().orElse(null);
            writesBlocked = false;
            if (moved != null) {
                logger.warning("The unreadable personal credit record has been moved aside as \""
                        + moved + "\" so a new one could be written.");
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "The personal credit record is unreadable and could not be "
                    + "moved aside, so it has been left as it is and the new credit was NOT persisted. "
                    + "Move or delete the file by hand to restore persistence. Cause: "
                    + failure.getMessage(), failure);
            return false;
        }
    }
}
