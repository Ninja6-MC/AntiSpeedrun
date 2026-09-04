package com.ninja6.antispeedrun;

import java.io.File;
import java.io.IOException;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.ninja6.antispeedrun.config.BukkitConfigSection;
import com.ninja6.antispeedrun.config.ConfigLoadException;
import com.ninja6.antispeedrun.config.ConfigSection;
import com.ninja6.antispeedrun.config.ConfigSnapshotHolder;
import com.ninja6.antispeedrun.config.ConfigSource;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.BukkitAdvancementLookup;
import com.ninja6.antispeedrun.progression.PlayerStateRegistry;
import com.ninja6.antispeedrun.progression.ProgressionListener;
import com.ninja6.antispeedrun.progression.ProgressionManager;

/**
 * AntiSpeedrun - Unified anti-speedrun, dimension progression gates,
 * anti-cheese, and multi-dragon boss combat scaling for PaperMC &amp; Folia.
 *
 * <p>Configuration is read through {@link #configuration()}, which returns the live immutable
 * snapshot. Every handler reads it <strong>once</strong> per event and uses that local for the
 * whole handler body; see {@code com.ninja6.antispeedrun.config} for the threading contract.
 */
public final class AntiSpeedrunPlugin extends JavaPlugin {

    /**
     * Volatile, not merely assigned once: this field is written on the enable thread and read from
     * every Folia region thread, and nothing else in this class establishes a happens-before edge
     * for it. The snapshot graph below the reference is safely published by the holder's own
     * volatile write, but that says nothing about the reference to the holder itself. It cannot be
     * {@code final} because {@code onEnable} is not a constructor, so {@code volatile} is the
     * remaining way to publish it safely — and this class is the pattern every listener added later
     * will copy.
     */
    private volatile ConfigSnapshotHolder configHolder;

    /**
     * Every per-player map in the plugin registers here, so {@code PlayerQuitEvent} clears all of
     * them in one call and a feature added later cannot forget to (finding R-08). Assigned in
     * {@code onEnable} and read from every region thread, hence {@code volatile} for the same
     * reason as {@link #configHolder}.
     */
    private volatile PlayerStateRegistry playerState;

    private volatile ProgressionManager progression;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Start on the shipped defaults so a broken file at startup degrades to known-good values
        // instead of disabling the plugin; the reload below replaces them when the file is valid.
        this.configHolder = new ConfigSnapshotHolder(getLogger(), PluginConfig.defaults());
        if (!reloadConfiguration()) {
            getLogger().warning("Running on the shipped default configuration until config.yml loads.");
            // The rejected-reload path logs why the file failed, but nothing has yet said what the
            // fallback actually leaves running -- notably that the defaults gate no items at all.
            configHolder.logWarnings(configHolder.get().warnings());
        }

        this.playerState = new PlayerStateRegistry();
        this.progression = new ProgressionManager(
                getLogger(), new BukkitAdvancementLookup(getLogger()), playerState);
        getServer().getPluginManager().registerEvents(new ProgressionListener(this, progression), this);

        getLogger().info("AntiSpeedrun enabled successfully.");
    }

    @Override
    public void onDisable() {
        // Handlers are unregistered by the server before this runs, so nothing can repopulate the
        // maps; clearing them keeps a /reload cycle from leaving the previous run's entries behind.
        if (playerState != null) {
            playerState.forgetAll();
        }
        getLogger().info("AntiSpeedrun disabled.");
    }

    /** The progression service. Gates, commands and the progress card all evaluate through it. */
    public ProgressionManager progression() {
        return progression;
    }

    /** The registry every per-player map belongs to. Register here, get quit cleanup for free. */
    public PlayerStateRegistry playerState() {
        return playerState;
    }

    /**
     * The live configuration snapshot. Read once per event; never twice inside one decision.
     */
    public PluginConfig configuration() {
        return configHolder.get();
    }

    /**
     * The holder itself, for the {@code /asr reload} dispatcher (#40).
     */
    public ConfigSnapshotHolder configHolder() {
        return configHolder;
    }

    /**
     * Re-reads {@code config.yml} and swaps the snapshot in one assignment.
     *
     * @return {@code true} if the new snapshot is live, {@code false} if it was rejected and the
     *         previous one remains live. Never disables the plugin.
     */
    public boolean reloadConfiguration() {
        boolean swapped = configHolder.reload(fileSource());
        if (swapped && progression != null) {
            // A new configuration can require advancements no live snapshot ever queried, and can
            // enable a gate whose unlock has never been announced. Both are cache state, not
            // configuration state, so the swap alone does not fix them.
            progression.onConfigurationReloaded();
        }
        return swapped;
    }

    /**
     * Reads {@code config.yml} with {@link YamlConfiguration#load(File)} rather than
     * {@code reloadConfig()}, because the latter swallows a syntax error and hands back an empty
     * configuration — which would silently reset every key to its default. This surfaces the
     * failure as a {@link ConfigLoadException} so the previous snapshot can be kept instead.
     */
    private ConfigSource fileSource() {
        final File file = new File(getDataFolder(), "config.yml");
        return () -> {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(file);
            } catch (IOException | InvalidConfigurationException failure) {
                throw new ConfigLoadException(
                        file.getPath() + " could not be parsed: " + failure.getMessage(), failure);
            }
            ConfigSection root = new BukkitConfigSection(yaml);
            return root;
        };
    }
}
