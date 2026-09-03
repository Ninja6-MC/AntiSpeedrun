package com.ninja6.antispeedrun.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Adapts a Bukkit {@link ConfigurationSection} to {@link ConfigSection}.
 *
 * <p>This is the only class in the package that touches the server API, and it is deliberately
 * trivial: it declares no defaults, performs no coercion and holds no state beyond the wrapped
 * section, so nothing that needs testing lives behind the {@code compileOnly} dependency.
 */
public final class BukkitConfigSection implements ConfigSection {

    private final ConfigurationSection section;

    public BukkitConfigSection(ConfigurationSection section) {
        this.section = Objects.requireNonNull(section, "section");
    }

    @Override
    public Set<String> keys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(section.getKeys(false)));
    }

    @Override
    public boolean contains(String key) {
        return section.contains(key, true);
    }

    @Override
    public Object get(String key) {
        return section.get(key);
    }

    @Override
    public ConfigSection section(String key) {
        ConfigurationSection child = section.getConfigurationSection(key);
        return child == null ? null : new BukkitConfigSection(child);
    }
}
