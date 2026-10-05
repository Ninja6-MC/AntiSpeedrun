package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link StateFile} whose document carries a top-level {@code state-version}, the state-file
 * counterpart of {@code config-version} in {@code config.yml}.
 *
 * <p>Wraps the file a store writes. Every {@link #save} stamps {@link #STATE_VERSION} as the first
 * key, and every {@link #load} checks the stamp and strips it, so the stores above never see it and
 * their document shapes are unchanged.
 *
 * <h2>Versions</h2>
 *
 * A file with no {@code state-version} was written by v0.2.0 or earlier, whose format is version 1,
 * so it is read as version 1 and {@link #migrate()} writes the stamp. A version above
 * {@link #STATE_VERSION}, or one that is not a whole number of at least 1, is refused with a
 * {@link StateVersionException}: the file is never moved aside or overwritten, and the plugin stops
 * startup, as it does for a {@code config.yml} from a newer build.
 *
 * <p>Version 1 is the only format so far, so migrating is only stamping and the data is untouched.
 * A future format change adds a step to {@link #migrate()} that rewrites the document from the old
 * shape, and takes a backup first as {@code ConfigMigrator} does.
 *
 * <p>Touches the filesystem through the wrapped file: never call it from a Folia region thread.
 */
public final class VersionedStateFile implements StateFile {

    /** The top-level key that carries the format version of a state file. */
    public static final String VERSION_KEY = "state-version";

    /** The format version this build reads and writes. */
    public static final int STATE_VERSION = 1;

    private final String name;
    private final StateFile delegate;

    /**
     * Set once a load found a version this build cannot read. From then on nothing is written to,
     * or moved out of, the file, whatever the store above asks for.
     */
    private volatile boolean refused;

    /**
     * @param name     the file's name, for messages, such as {@code state.yml}
     * @param delegate the file the document is stored in
     */
    public VersionedStateFile(String name, StateFile delegate) {
        this.name = Objects.requireNonNull(name, "name");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** The file's name, for log lines. */
    public String name() {
        return name;
    }

    /**
     * The document without its version key.
     *
     * @throws StateVersionException if the file declares a version this build cannot read
     */
    @Override
    public Map<String, Object> load() throws IOException {
        Map<String, Object> raw = delegate.load();
        checkVersion(raw);
        return withoutVersion(raw);
    }

    /** Writes {@code document} with the current version stamped as its first key. */
    @Override
    public void save(Map<String, Object> document) throws IOException {
        Objects.requireNonNull(document, "document");
        refuseIfUnreadableVersion();
        delegate.save(stamped(document));
    }

    @Override
    public Optional<String> quarantine() throws IOException {
        refuseIfUnreadableVersion();
        return delegate.quarantine();
    }

    /**
     * Brings the file on disk up to {@link #STATE_VERSION}. Call once at startup, before the store
     * that owns the file loads it.
     *
     * @return {@code true} when an unversioned file was stamped; {@code false} when the file is
     *         absent, empty or already current, in which case nothing was written
     * @throws StateVersionException if the file declares a version this build cannot read; nothing
     *                               was written
     * @throws IOException           if the file cannot be read or rewritten
     */
    public boolean migrate() throws IOException {
        Map<String, Object> raw = delegate.load();
        if (raw.isEmpty()) {
            // Absent, or nothing in it: the first save writes the stamp.
            return false;
        }
        if (checkVersion(raw)) {
            return false;
        }
        // Unversioned is the v0.2.0 format, which is version 1: the data stays as it is.
        delegate.save(stamped(withoutVersion(raw)));
        return true;
    }

    /**
     * Refuses a version this build cannot read.
     *
     * @return whether the document declares a version at all
     */
    private boolean checkVersion(Map<String, Object> raw) throws StateVersionException {
        if (!raw.containsKey(VERSION_KEY)) {
            return false;
        }
        Object declared = raw.get(VERSION_KEY);
        if (!(declared instanceof Integer || declared instanceof Long) || ((Number) declared).longValue() < 1) {
            refused = true;
            throw new StateVersionException(name + " has " + VERSION_KEY + ": " + declared + ", which "
                    + "is not a whole number of at least 1. The file has not been changed. Restore the "
                    + "value the plugin wrote, or remove the key to have the file read as version 1.");
        }
        if (((Number) declared).longValue() > STATE_VERSION) {
            refused = true;
            throw new StateVersionException(name + " has " + VERSION_KEY + ": " + declared + ", but "
                    + "this build of AntiSpeedrun only reads versions up to " + STATE_VERSION + ". It was "
                    + "written by a newer version. The file has not been changed. Install that version, "
                    + "or restore a copy of " + name + " this build can read.");
        }
        return true;
    }

    private void refuseIfUnreadableVersion() throws StateVersionException {
        if (refused) {
            throw new StateVersionException(name + " declares a " + VERSION_KEY + " this build cannot "
                    + "read, so it is left exactly as it is and nothing was written to it.");
        }
    }

    private static Map<String, Object> withoutVersion(Map<String, Object> raw) {
        if (!raw.containsKey(VERSION_KEY)) {
            return raw;
        }
        Map<String, Object> document = new LinkedHashMap<>(raw);
        document.remove(VERSION_KEY);
        return Map.copyOf(document);
    }

    private static Map<String, Object> stamped(Map<String, Object> document) {
        Map<String, Object> stamped = new LinkedHashMap<>();
        stamped.put(VERSION_KEY, STATE_VERSION);
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            if (!VERSION_KEY.equals(entry.getKey())) {
                stamped.put(entry.getKey(), entry.getValue());
            }
        }
        return stamped;
    }
}
