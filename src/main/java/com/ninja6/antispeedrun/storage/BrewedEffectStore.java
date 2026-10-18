package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BrewedEffectStore {

    public static final String KEY_PREFIX = "brewed-effects.";

    private final Logger logger;
    private final StateFile file;
    private final Executor ioExecutor;

    private final Object stateLock = new Object();
    private final Object writeLock = new Object();

    private volatile Map<UUID, Set<String>> brewed = Map.of();

    private volatile boolean writesBlocked;

    public BrewedEffectStore(Logger logger, StateFile file, Executor ioExecutor) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.file = Objects.requireNonNull(file, "file");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    public boolean loadNow() {
        try {
            this.brewed = fromDocument(file.load());
            this.writesBlocked = false;
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "Could not read the brewed effect record; starting with "
                    + "none recorded. The damaged file has NOT been overwritten: it will be moved aside "
                    + "under a .corrupt name before the next write. Cause: " + failure.getMessage(),
                    failure);
            this.brewed = Map.of();
            this.writesBlocked = true;
            return false;
        }
    }

    public boolean isAwaitingQuarantine() {
        return writesBlocked;
    }

    public boolean hasBrewed(UUID player, String effect) {
        Objects.requireNonNull(effect, "effect");
        Set<String> ids = brewed.get(Objects.requireNonNull(player, "player"));
        return ids != null && ids.contains(effect);
    }

    public boolean record(UUID player, String effect, boolean hasBrewed) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(effect, "effect");
        if (!effect.equals(com.ninja6.antispeedrun.config.BrewingEffects.canonical(effect))) {
            throw new IllegalArgumentException("Not a canonical beneficial effect: " + effect);
        }
        synchronized (stateLock) {
            Map<UUID, Set<String>> current = brewed;
            Set<String> before = current.getOrDefault(player, Set.of());
            if (before.contains(effect) == hasBrewed) {
                return false;
            }
            Set<String> after = new TreeSet<>(before);
            if (hasBrewed) {
                after.add(effect);
            } else {
                after.remove(effect);
            }
            Map<UUID, Set<String>> next = new HashMap<>(current);
            if (after.isEmpty()) {
                next.remove(player);
            } else {
                next.put(player, Set.copyOf(after));
            }
            this.brewed = Map.copyOf(next);
        }
        persist();
        return true;
    }

    static Map<String, Object> toDocument(Map<UUID, Set<String>> brewed) {
        Map<String, Object> document = new LinkedHashMap<>();
        brewed.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> document.put(KEY_PREFIX + entry.getKey(),
                        List.copyOf(new TreeSet<>(entry.getValue()))));
        return document;
    }

    static Map<UUID, Set<String>> fromDocument(Map<String, Object> document) {
        Map<UUID, Set<String>> brewed = new HashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(KEY_PREFIX) || !(entry.getValue() instanceof Collection<?> values)) {
                continue;
            }
            UUID player;
            try {
                player = UUID.fromString(key.substring(KEY_PREFIX.length()));
            } catch (IllegalArgumentException notUuid) {
                continue;
            }
            Set<String> ids = new TreeSet<>();
            for (Object value : values) {
                if (value instanceof String id && com.ninja6.antispeedrun.config.BrewingEffects.canonical(id) != null) {
                    ids.add(com.ninja6.antispeedrun.config.BrewingEffects.canonical(id));
                }
            }
            if (!ids.isEmpty()) {
                brewed.put(player, Set.copyOf(ids));
            }
        }
        return Map.copyOf(brewed);
    }

    void persist() {
        ioExecutor.execute(() -> {
            synchronized (writeLock) {
                if (!clearForWriting()) {
                    return;
                }
                try {
                    file.save(toDocument(brewed));
                } catch (IOException | RuntimeException failure) {
                    logger.log(Level.SEVERE, "Could not persist the brewed effect record. "
                            + "New brewing credits may be lost after a restart. Cause: " + failure.getMessage(), failure);
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
                logger.warning("The unreadable brewed effect record has been moved aside as \""
                        + moved + "\" so a new one could be written.");
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            logger.log(Level.SEVERE, "The brewed effect record is unreadable and could not be "
                    + "moved aside, so it has been left as it is and the new entry was NOT persisted. "
                    + "Move or delete the file by hand to restore persistence. Cause: "
                    + failure.getMessage(), failure);
            return false;
        }
    }
}
