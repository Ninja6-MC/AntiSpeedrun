package com.ninja6.antispeedrun.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A {@link ConfigSection} backed by a plain nested {@code Map}, which is what every YAML parser
 * produces. Used by the test suite to drive the real parsing code without a server, and available
 * to any future code path that has a document in hand rather than a {@code FileConfiguration}.
 *
 * <p>The backing map is defensively copied and wrapped unmodifiable on construction, so the
 * section cannot be changed after it is handed out. Document order is preserved.
 *
 * <p>Keys are literal, as {@link ConfigSection} requires: a key containing {@code '.'} names one
 * child, and is never treated as a path into nested sections. A key carrying no value is dropped,
 * also as {@link ConfigSection} requires; see {@link #of(Map)} for why that is not optional.
 */
public final class MapConfigSection implements ConfigSection {

    /** A section with no keys. Parsing this yields {@link PluginConfig#defaults()}. */
    public static final MapConfigSection EMPTY = new MapConfigSection(Map.of());

    private final Map<String, Object> values;

    private MapConfigSection(Map<String, Object> values) {
        this.values = values;
    }

    /**
     * Wraps {@code raw}. Keys are stringified; a {@code null} map is treated as an empty document,
     * which is what a YAML parser returns for a file that is entirely comments.
     *
     * <p>A key whose value is {@code null} — a bodiless {@code my-tier:} in the document — is
     * dropped, so it is not reported by {@link #keys()} and does not answer {@link #contains}. That
     * is not a preference: {@code MemorySection.set} removes the key on a null value, so
     * {@link BukkitConfigSection} cannot represent such a key at all, and the runtime behaviour is
     * the one both implementations have to agree on. Keeping it here would mean a bodiless tier
     * parsed as one empty tier under test and as no tier on a server, which is the exact shape of
     * seam bug this package exists to rule out.
     */
    public static MapConfigSection of(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return EMPTY;
        }
        Map<String, Object> copy = new LinkedHashMap<>(raw.size());
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getValue() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy.isEmpty() ? EMPTY : new MapConfigSection(Collections.unmodifiableMap(copy));
    }

    @Override
    public Set<String> keys() {
        return values.keySet();
    }

    @Override
    public Object get(String key) {
        return values.get(key);
    }

    @Override
    public ConfigSection section(String key) {
        return values.get(key) instanceof Map<?, ?> child ? of(child) : null;
    }
}
