package com.ninja6.antispeedrun.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Typed reads against one {@link ConfigSection}, with the fallback policy applied in exactly one
 * place.
 *
 * <p>The policy, which the whole model relies on:
 * <ul>
 *   <li>an <strong>absent</strong> key falls back to the shipped default, silently;</li>
 *   <li>a key present with the <strong>wrong type</strong> falls back to the shipped default and
 *       records a warning naming the full key path, what was expected and what was found;</li>
 *   <li>an <strong>unknown</strong> key is ignored and records a warning, so a typo in a key name
 *       is visible rather than silently doing nothing.</li>
 * </ul>
 *
 * <p>Package-private on purpose: it is parsing scaffolding, not part of the configuration
 * contract that the rest of the plugin reads.
 */
final class ConfigReader {

    private final ConfigSection section;
    private final String path;
    private final List<String> warnings;

    ConfigReader(ConfigSection section, String path, List<String> warnings) {
        this.section = section == null ? MapConfigSection.EMPTY : section;
        this.path = path;
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    /** A reader for the child mapping at {@code key}; never null, absent children read as empty. */
    ConfigReader child(String key) {
        ConfigSection child = section.section(key);
        if (child == null && section.contains(key) && section.get(key) != null) {
            warn(key, "a section", section.get(key));
        }
        return new ConfigReader(child, qualify(key), warnings);
    }

    String string(String key, String def) {
        Object raw = section.get(key);
        if (raw == null) {
            return def;
        }
        if (raw instanceof String value) {
            return value;
        }
        if (raw instanceof Number || raw instanceof Boolean || raw instanceof Character) {
            return String.valueOf(raw);
        }
        warn(key, "a string", raw);
        return def;
    }

    boolean bool(String key, boolean def) {
        Object raw = section.get(key);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        warn(key, "a boolean", raw);
        return def;
    }

    int integer(String key, int def) {
        Object raw = section.get(key);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number value) {
            return value.intValue();
        }
        warn(key, "an integer", raw);
        return def;
    }

    double decimal(String key, double def) {
        Object raw = section.get(key);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number value) {
            return value.doubleValue();
        }
        warn(key, "a number", raw);
        return def;
    }

    /**
     * Reads a list of strings. Absent or wrong-typed reads fall back to {@code def}; individual
     * non-scalar elements are dropped with a warning rather than failing the whole list.
     */
    List<String> strings(String key, List<String> def) {
        Object raw = section.get(key);
        if (raw == null) {
            return List.copyOf(def);
        }
        if (!(raw instanceof List<?> list)) {
            warn(key, "a list of strings", raw);
            return List.copyOf(def);
        }
        List<String> parsed = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof String value) {
                parsed.add(value);
            } else if (element instanceof Number || element instanceof Boolean) {
                parsed.add(String.valueOf(element));
            } else {
                warnings.add(qualify(key) + ": dropped a list entry that is not a string (found "
                        + describe(element) + ")");
            }
        }
        return List.copyOf(parsed);
    }

    /** Reads an enum constant case-insensitively, falling back with a warning that lists the options. */
    <E extends Enum<E>> E enumValue(String key, Class<E> type, E def) {
        Object raw = section.get(key);
        if (raw == null) {
            return def;
        }
        if (raw instanceof String value) {
            String normalised = value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(normalised)) {
                    return constant;
                }
            }
            warnings.add(qualify(key) + ": \"" + value + "\" is not one of "
                    + Arrays.toString(type.getEnumConstants()) + "; using the default " + def);
            return def;
        }
        warn(key, "one of " + Arrays.toString(type.getEnumConstants()), raw);
        return def;
    }

    /**
     * Reads a value that must be strictly positive, falling back with a warning otherwise. Used for
     * the documented {@code multiplier > 0.0} invariant.
     */
    double positiveDecimal(String key, double def) {
        double value = decimal(key, def);
        if (!(value > 0.0D) || !Double.isFinite(value)) {
            warnings.add(qualify(key) + ": must be greater than 0.0 (found " + value
                    + "); using the default " + def);
            return def;
        }
        return value;
    }

    /** Reads a value clamped to be at least {@code min}, warning when the configured value is below it. */
    int atLeast(String key, int def, int min) {
        int value = integer(key, def);
        if (value < min) {
            warnings.add(qualify(key) + ": must be at least " + min + " (found " + value
                    + "); using the default " + def);
            return def;
        }
        return value;
    }

    /** The keys declared on this section, in document order. */
    Set<String> keys() {
        return section.keys();
    }

    /** Warns about every key on this section that is not in {@code known}. */
    void expect(String... known) {
        Set<String> recognised = Set.of(known);
        for (String key : section.keys()) {
            if (!recognised.contains(key)) {
                warnings.add("unknown key " + qualify(key) + " is not used by AntiSpeedrun and was ignored");
            }
        }
    }

    private void warn(String key, String expected, Object found) {
        warnings.add(qualify(key) + ": expected " + expected + " but found " + describe(found)
                + "; using the default");
    }

    private String qualify(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static String describe(Object value) {
        if (value == null) {
            return "nothing";
        }
        if (value instanceof ConfigSection || value instanceof java.util.Map<?, ?>) {
            return "a section";
        }
        if (value instanceof List<?>) {
            return "a list";
        }
        return value.getClass().getSimpleName() + " \"" + value + "\"";
    }
}
