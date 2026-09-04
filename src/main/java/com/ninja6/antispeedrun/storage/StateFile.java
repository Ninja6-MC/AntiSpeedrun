package com.ninja6.antispeedrun.storage;

import java.io.IOException;
import java.util.Map;

/**
 * Where server-wide state is read from and written to, as a flat document of dotted keys.
 *
 * <p>The seam that keeps the storage layer testable. {@code paper-api} is {@code compileOnly}, so
 * Bukkit's {@code YamlConfiguration} is not on the test classpath; everything above this interface
 * is plain JDK code and everything Bukkit-bound is behind it, in {@link YamlStateFile}.
 *
 * <p>Flat, not nested: values are scalars and keys carry their own path, as in
 * {@code dimension-unlocks.nether}. Nested mappings would force the adapter to walk
 * {@code ConfigurationSection} objects and would make the in-memory test double a different shape
 * from the real thing.
 *
 * <h2>Threading</h2>
 *
 * Implementations touch the filesystem, so no method here may be called from a Folia region thread.
 * {@link DimensionUnlockStore} owns that rule; see its constructor.
 */
public interface StateFile {

    /**
     * The persisted document, or an empty map when nothing has been written yet.
     *
     * @throws IOException if the file exists but cannot be read or parsed
     */
    Map<String, Object> load() throws IOException;

    /**
     * Replaces the persisted document wholesale. Keys absent from {@code document} are removed.
     *
     * @throws IOException if the file cannot be written
     */
    void save(Map<String, Object> document) throws IOException;
}
