package com.ninja6.antispeedrun;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.ninja6.antispeedrun.config.BukkitConfigSection;
import com.ninja6.antispeedrun.config.ConfigLoadException;
import com.ninja6.antispeedrun.config.ConfigSection;
import com.ninja6.antispeedrun.config.ConfigSnapshotHolder;
import com.ninja6.antispeedrun.config.ConfigSource;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.gating.GateCollisionException;
import com.ninja6.antispeedrun.gating.ItemGateTable;
import com.ninja6.antispeedrun.gating.MaterialGates;
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

    /**
     * The compiled item-gate lookup, rebuilt from whichever snapshot is live. Volatile for the same
     * reason as {@link #configHolder}: written on the enable and command threads, read from every
     * region thread. Never mutated in place — a recompile replaces the whole table in one write.
     */
    private volatile ItemGateTable<Material> itemGates = MaterialGates.empty();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Start on the shipped defaults so a broken file at startup degrades to known-good values
        // instead of disabling the plugin; the reload below replaces them when the file is valid.
        this.configHolder = new ConfigSnapshotHolder(getLogger(), PluginConfig.defaults());
        // Runs before anything else is built, so a startup doomed by a tier collision does not
        // register listeners it is about to tear down. Progression does not exist yet at this
        // point, which is why applyConfiguration's refresh of it is null-guarded rather than
        // unconditional -- priming online players is done explicitly further down instead.
        switch (applyConfiguration()) {
            case APPLIED -> {
                // Nothing more to do: the snapshot and the gates it compiled to are both live.
            }
            case CONFIG_REJECTED -> {
                getLogger().warning(
                        "Running on the shipped default configuration until config.yml loads.");
                // The rejected-reload path logs why the file failed, but nothing has yet said what
                // the fallback actually leaves running -- notably that the defaults gate no items
                // at all, which is what the empty table this field starts on already expresses.
                configHolder.logWarnings(configHolder.get().warnings());
            }
            case GATES_REJECTED -> {
                // An unresolvable tier collision is not a recoverable parse problem. At startup
                // there is no previous table to keep, and staying up with every item ungated is
                // precisely the silent failure audit finding R-11 objects to -- so this one
                // condition does stop the plugin, where a malformed config.yml deliberately
                // does not.
                getLogger().severe("AntiSpeedrun will not start while item-progression.gated-items "
                        + "contains an unresolvable tier collision. Fix config.yml and restart.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        this.playerState = new PlayerStateRegistry();
        this.progression = new ProgressionManager(
                getLogger(), new BukkitAdvancementLookup(getLogger()), playerState);
        getServer().getPluginManager().registerEvents(new ProgressionListener(this, progression), this);

        // Players already online -- a hot install, or a /reload -- never fire PlayerJoinEvent for
        // this listener, so without priming here their first advancement would announce every gate
        // they had already cleared.
        primeOnlinePlayers(configuration());

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
     * The compiled item-gate lookup for the live snapshot. Read once per event, exactly like
     * {@link #configuration()}. This table is always compiled from the snapshot
     * {@link #configuration()} returns: a reload that cannot compile publishes neither, so the two
     * never come to rest out of step, and the {@code ItemTier} {@link ItemGateTable#tierFor} hands
     * back is always one the live snapshot contains.
     *
     * <p>This is the entry point the pickup and container listeners (#12, #9) need:
     * {@code itemGates().isGated(material)} and {@code itemGates().tierFor(material)}.
     */
    public ItemGateTable<Material> itemGates() {
        return itemGates;
    }

    /**
     * Re-reads {@code config.yml} and applies it, all or nothing: the snapshot, the item gate table
     * compiled from it, and the progression cache built against it.
     *
     * @return {@code true} if the new snapshot and its compiled gates are live, {@code false} if
     *         either was rejected — in which case <em>nothing</em> was applied and the previous
     *         snapshot, table and progression cache all remain live together. Never disables the
     *         plugin; only the startup path in {@link #onEnable()} does that, and only for a
     *         collision.
     */
    public boolean reloadConfiguration() {
        return applyConfiguration() == ReloadOutcome.APPLIED;
    }

    /** Why a reload attempt ended the way it did. The startup path treats the two failures apart. */
    private enum ReloadOutcome {
        /** The new snapshot and the gates compiled from it are both live. */
        APPLIED,
        /** {@code config.yml} could not be parsed; nothing changed. Recoverable, never fatal. */
        CONFIG_REJECTED,
        /** The file parsed but its tiers collide; nothing changed. Fatal at startup only. */
        GATES_REJECTED
    }

    /**
     * Re-reads {@code config.yml}, compiles the item gates from the <em>candidate</em> snapshot, and
     * publishes both only if each succeeded.
     *
     * <p>The compile deliberately happens before the snapshot is published. Compiling afterwards
     * would leave a rejected collision with the operator's new configuration live beside the old
     * material assignments — {@code tierFor} would hand a listener an {@code ItemTier} that is not
     * in the live snapshot at all, and the two would stay out of step until the next successful
     * reload. {@link ConfigSnapshotHolder#reload(ConfigSource, ConfigSnapshotHolder.SnapshotBinding)}
     * exists for exactly this ordering.
     *
     * <p>Nothing is ever mutated in place, so {@code /asr reload} flushes the old table simply by
     * making it unreachable.
     *
     * <p>Four things happen here, and the order is the point: parse the candidate, compile the gate
     * table <em>from the candidate</em>, publish both, then refresh what was derived from the
     * previous configuration. The progression cache is refreshed last and only on the applied path.
     * Invalidating it after a rejected reload would throw away good captures because a file failed
     * to parse — the mirror image of publishing a snapshot beside a stale table, and wrong for the
     * same reason. It is also why {@code primeOnlinePlayers} is handed {@link #configuration()},
     * the snapshot that is now live, rather than the candidate it was compiled from.
     */
    private ReloadOutcome applyConfiguration() {
        List<String> gateWarnings = new ArrayList<>();
        AtomicBoolean collided = new AtomicBoolean();

        Optional<ItemGateTable<Material>> compiled = configHolder.reload(fileSource(), candidate -> {
            try {
                return MaterialGates.compile(candidate.itemProgression(), gateWarnings);
            } catch (GateCollisionException collision) {
                collided.set(true);
                throw collision;
            }
        });

        if (compiled.isEmpty()) {
            return collided.get() ? ReloadOutcome.GATES_REJECTED : ReloadOutcome.CONFIG_REJECTED;
        }

        this.itemGates = compiled.get();
        configHolder.logWarnings(gateWarnings);
        getLogger().info("Item gates compiled: " + compiled.get().size() + " materials across "
                + configHolder.get().itemProgression().gatedItems().size() + " tiers.");

        if (progression != null) {
            // Null during onEnable, which calls this before the manager exists and primes online
            // players itself afterwards.
            //
            // A new configuration can require advancements no live snapshot ever queried, so every
            // capture is stale. Dropping them is cache state, not configuration state, so the swap
            // alone does not fix it.
            progression.onConfigurationReloaded();
            // Re-prime rather than reset: an emptied "already told" set would make the next
            // advancement any online player earns re-announce every gate they cleared weeks ago,
            // once per reload, to everyone. Priming dispatches through each player's
            // EntityScheduler, so it must be given the configuration that is live by then.
            primeOnlinePlayers(configuration());
        }
        return ReloadOutcome.APPLIED;
    }

    /**
     * Records what every online player already satisfies, without announcing any of it.
     *
     * <p>Priming reads {@code getStatistic} and {@code getAdvancementProgress}, which on Folia are
     * owned by the player's region — so each player's priming is dispatched to their own
     * {@code EntityScheduler} rather than run in a loop on the calling thread. The retired callback
     * is {@code null} because a player who is gone before the task runs needs nothing done; the
     * {@code isOnline} guard covers the same case for a player who leaves in between.
     */
    private void primeOnlinePlayers(PluginConfig config) {
        for (Player player : getServer().getOnlinePlayers()) {
            player.getScheduler().run(this, task -> {
                if (player.isOnline()) {
                    progression.primeUnlocks(player, config);
                }
            }, null);
        }
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
