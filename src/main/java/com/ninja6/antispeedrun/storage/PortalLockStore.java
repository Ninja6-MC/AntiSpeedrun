package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Durable markers for End exits whose portal blocks this plugin replaced with bedrock. */
public final class PortalLockStore {

    public static final String KEY_PREFIX = "locked-exits.";

    private final Logger logger;
    private final StateFile file;
    private final Executor ioExecutor;
    private final Object writeLock = new Object();
    private volatile Set<UUID> locked = Set.of();
    private volatile boolean unreadable;

    public PortalLockStore(Logger logger, StateFile file, Executor ioExecutor) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.file = Objects.requireNonNull(file, "file");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /** Load before registering the combat listener, so its startup recovery sees every marker. */
    public boolean loadNow() {
        try {
            locked = fromDocument(file.load());
            unreadable = false;
            return true;
        } catch (IOException | RuntimeException failure) {
            unreadable = true;
            logger.log(Level.SEVERE, "Could not read portal-locks.yml; locked End exits may need "
                    + "manual recovery. The unreadable file has not been overwritten.", failure);
            return false;
        }
    }

    public boolean isLocked(UUID world) {
        return locked.contains(Objects.requireNonNull(world, "world"));
    }

    /** Persist the marker before invoking {@code completion}; only success may alter portal blocks. */
    public void arm(UUID world, Consumer<Boolean> completion) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(completion, "completion");
        ioExecutor.execute(() -> {
            boolean saved;
            synchronized (writeLock) {
                if (unreadable) {
                    completion.accept(false);
                    return;
                }
                Set<UUID> next = new HashSet<>(locked);
                next.add(world);
                try {
                    file.save(toDocument(next));
                    locked = Set.copyOf(next);
                    saved = true;
                } catch (IOException | RuntimeException failure) {
                    logger.log(Level.SEVERE, "Could not record the End exit lock; leaving the portal "
                            + "unsealed in world " + world + '.', failure);
                    saved = false;
                }
            }
            completion.accept(saved);
        });
    }

    /** Called only after the active portal has been generated. A failed write keeps recovery armed. */
    public void clear(UUID world) {
        Objects.requireNonNull(world, "world");
        ioExecutor.execute(() -> {
            synchronized (writeLock) {
                if (!locked.contains(world)) {
                    return;
                }
                Set<UUID> next = new HashSet<>(locked);
                next.remove(world);
                try {
                    file.save(toDocument(next));
                    locked = Set.copyOf(next);
                } catch (IOException | RuntimeException failure) {
                    logger.log(Level.SEVERE, "Could not clear the restored End exit marker for "
                            + world + "; recovery will retry on next startup.", failure);
                }
            }
        });
    }

    static Map<String, Object> toDocument(Set<UUID> worlds) {
        Map<String, Object> document = new LinkedHashMap<>();
        worlds.stream().sorted().forEach(world -> document.put(KEY_PREFIX + world, true));
        return document;
    }

    static Set<UUID> fromDocument(Map<String, Object> document) {
        Set<UUID> worlds = new HashSet<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            if (!entry.getKey().startsWith(KEY_PREFIX) || !Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            try {
                worlds.add(UUID.fromString(entry.getKey().substring(KEY_PREFIX.length())));
            } catch (IllegalArgumentException notUuid) {
                // Ignore a hand-edited key, as other state stores do.
            }
        }
        return Set.copyOf(worlds);
    }
}
