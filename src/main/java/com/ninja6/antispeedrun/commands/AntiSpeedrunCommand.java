package com.ninja6.antispeedrun.commands;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.logging.Level;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PluginConfig.Profile;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.PlayerProgressionSnapshot;
import com.ninja6.antispeedrun.storage.BypassStore;
import com.ninja6.antispeedrun.storage.DimensionUnlock;
import com.ninja6.antispeedrun.storage.ProfileApplier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * {@code /antispeedrun} (alias {@code /asr}) — the administrative dispatcher, Task 8.1.1 (#40).
 *
 * <p>Five subcommands: {@code reload}, {@code profile apply}, {@code unlock}, {@code bypass} and
 * {@code inspect}. Each is gated on its own {@code antispeedrun.admin.*} node from
 * {@code plugin.yml}, mapped once in {@link Subcommand}; the parsing and the completion grammar
 * live in {@link Subcommand}, {@link BypassDuration} and {@link CommandCompletion}, which are
 * Bukkit-free and unit tested. What is left here is dispatch, threading and message rendering.
 *
 * <h2>Threading — the part that is easy to get wrong on Folia</h2>
 *
 * A command handler runs on the caller's region thread (the global region thread for console), so
 * this class does almost nothing where it is invoked:
 *
 * <ul>
 *   <li>{@code reload} hops to the <strong>global region scheduler</strong>. It re-reads
 *       {@code config.yml} and re-primes every online player, which is server-wide work and does
 *       not belong on one player's region.</li>
 *   <li>{@code profile apply} does its file work on the <strong>async scheduler</strong> — copying
 *       a backup and writing a configuration is blocking I/O and must never sit on a region — then
 *       hops to the global region scheduler to apply it.</li>
 *   <li>{@code bypass} and {@code inspect} run on the <strong>target player's
 *       {@code EntityScheduler}</strong>. Both touch state owned by that player's region: their
 *       persistent data container, their statistics and their advancement progress.</li>
 *   <li>{@code unlock} mutates one volatile reference here and hands its file write to the store,
 *       which queues it asynchronously.</li>
 * </ul>
 *
 * <p>The configuration snapshot is read <strong>once</strong>, at the top of
 * {@link #onCommand}, and that local is what every branch and every dispatched task uses — the
 * contract stated on {@code AntiSpeedrunPlugin#configuration()}. The one deliberate exception is
 * the completion of a reload, which reports on the snapshot that is live afterwards because that
 * is the thing it just changed.
 */
public final class AntiSpeedrunCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final String PREFIX = "<gray>[<gold>AntiSpeedrun<gray>]<reset> ";

    private final AntiSpeedrunPlugin plugin;

    public AntiSpeedrunCommand(AntiSpeedrunPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    // -----------------------------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        PluginConfig config = plugin.configuration();

        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        Optional<Subcommand> parsed = Subcommand.parse(args[0]);
        if (parsed.isEmpty()) {
            reply(sender, "<red>Unknown subcommand <yellow>" + escape(args[0]) + "<red>.");
            sendUsage(sender, label);
            return true;
        }

        Subcommand subcommand = parsed.get();
        if (!sender.hasPermission(subcommand.permission())) {
            reply(sender, "<red>You do not have permission to use <yellow>" + subcommand.usage()
                    + "<red>. Required: <gray>" + subcommand.permission());
            return true;
        }

        switch (subcommand) {
            case RELOAD -> reload(sender);
            case PROFILE -> profile(sender, args);
            case UNLOCK -> unlock(sender, args);
            case BYPASS -> bypass(sender, args);
            case INSPECT -> inspect(sender, config, args);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return CommandCompletion.complete(args,
                subcommand -> sender.hasPermission(subcommand.permission()),
                visiblePlayerNames(sender));
    }

    /**
     * Online players the sender is allowed to be told about.
     *
     * <p>Filtered through {@code canSee} so a vanished staff member is not disclosed by a tab
     * press; console sees everyone.
     */
    private List<String> visiblePlayerNames(CommandSender sender) {
        Player viewer = sender instanceof Player player ? player : null;
        List<String> names = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (viewer == null || viewer.canSee(online)) {
                names.add(online.getName());
            }
        }
        return names;
    }

    // -----------------------------------------------------------------------------------------
    // reload
    // -----------------------------------------------------------------------------------------

    /**
     * Re-reads and applies {@code config.yml}.
     *
     * <p>The whole sequence — parse, recompile the item gates, refresh the progression cache,
     * re-prime online players, and leave everything untouched if any of it fails — already lives in
     * {@code AntiSpeedrunPlugin.reloadConfiguration()}. This reports its outcome and nothing more;
     * duplicating any part of it here is how the command and the startup path drift.
     */
    private void reload(CommandSender sender) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            boolean applied = plugin.reloadConfiguration();
            if (!applied) {
                reply(sender, "<red>config.yml was rejected and has <bold>not</bold> been applied. "
                        + "The previous configuration is still live; see the server log for the cause.");
                return;
            }
            // Deliberately the post-reload snapshot: the warning count being reported is a fact
            // about the file that was just applied, not about the one it replaced.
            List<String> warnings = plugin.configuration().warnings();
            if (warnings.isEmpty()) {
                reply(sender, "<green>Configuration reloaded. <gray>Item gates: "
                        + plugin.itemGates().size() + " materials.");
            } else {
                reply(sender, "<yellow>Configuration reloaded with " + warnings.size()
                        + " warning(s). <gray>Item gates: " + plugin.itemGates().size()
                        + " materials. See the server log for details.");
            }
        });
    }

    // -----------------------------------------------------------------------------------------
    // profile apply
    // -----------------------------------------------------------------------------------------

    private void profile(CommandSender sender, String[] args) {
        if (args.length < 3 || !"apply".equalsIgnoreCase(args[1])) {
            reply(sender, "<red>Usage: <yellow>" + Subcommand.PROFILE.usage());
            return;
        }

        Profile profile;
        try {
            profile = Profile.valueOf(args[2].trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            reply(sender, "<red>Unknown profile <yellow>" + escape(args[2]) + "<red>. Choose one of "
                    + "<yellow>CASUAL<red>, <yellow>SMP_STANDARD<red> or <yellow>HARDCORE<red>.");
            return;
        }
        if (profile == Profile.CUSTOM) {
            reply(sender, "<red>CUSTOM is not a preset. It is what <yellow>profile<red> reads as once "
                    + "you have hand-edited config.yml away from a preset, so there is nothing to apply.");
            return;
        }

        reply(sender, "<gray>Backing up config.yml and applying <yellow>" + profile.name() + "<gray>...");
        // Blocking I/O: copy the backup, write the new configuration. Never on a region thread.
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> applyProfile(sender, profile));
    }

    private void applyProfile(CommandSender sender, Profile profile) {
        String resource = ProfileApplier.resourcePath(profile);
        InputStream preset = plugin.getResource(resource);
        if (preset == null) {
            plugin.getLogger().severe("Preset resource " + resource + " is missing from the plugin jar.");
            reply(sender, "<red>The <yellow>" + profile.name() + "<red> preset is missing from the "
                    + "plugin jar (" + resource + "). config.yml has not been touched.");
            return;
        }

        Optional<java.nio.file.Path> backup;
        try {
            backup = ProfileApplier.apply(
                    preset,
                    plugin.getDataFolder().toPath().resolve("config.yml"),
                    plugin.getDataFolder().toPath().resolve(ProfileApplier.BACKUP_DIRECTORY),
                    Instant.now(),
                    ZoneId.systemDefault());
        } catch (IOException failure) {
            plugin.getLogger().log(Level.SEVERE, "Could not apply profile " + profile.name(), failure);
            reply(sender, "<red>Could not apply <yellow>" + profile.name() + "<red>: "
                    + escape(String.valueOf(failure.getMessage()))
                    + ". <gray>The backup, if one was taken, is intact.");
            return;
        }

        String backupNote = backup
                .map(path -> " <gray>Backup: <white>" + escape(path.getFileName().toString()))
                .orElse(" <gray>No previous config.yml to back up.");

        // The file is on disk; applying it is a configuration swap and belongs on the global region.
        plugin.getServer().getGlobalRegionScheduler().run(plugin, applyTask -> {
            if (plugin.reloadConfiguration()) {
                reply(sender, "<green>Applied profile <yellow>" + profile.name() + "<green>."
                        + backupNote);
            } else {
                reply(sender, "<red>The <yellow>" + profile.name() + "<red> preset was written to "
                        + "config.yml but could not be loaded, so the previous configuration is still "
                        + "live. See the server log." + backupNote);
            }
        });
    }

    // -----------------------------------------------------------------------------------------
    // unlock
    // -----------------------------------------------------------------------------------------

    private void unlock(CommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, "<red>Usage: <yellow>" + Subcommand.UNLOCK.usage());
            return;
        }
        Optional<DimensionUnlock> dimension = DimensionUnlock.parse(args[1]);
        if (dimension.isEmpty()) {
            reply(sender, "<red>Unknown dimension <yellow>" + escape(args[1])
                    + "<red>. Choose <yellow>nether<red> or <yellow>end<red>.");
            return;
        }

        boolean relock = args.length >= 3 && "lock".equalsIgnoreCase(args[2]);
        DimensionUnlock target = dimension.get();

        if (relock) {
            boolean changed = plugin.dimensionUnlocks().lock(target);
            reply(sender, changed
                    ? "<yellow>" + target.displayName() + " is gated by progression again."
                    : "<gray>" + target.displayName() + " was not manually unlocked; nothing changed.");
            return;
        }

        boolean changed = plugin.dimensionUnlocks().unlock(target, System.currentTimeMillis());
        reply(sender, changed
                ? "<green>" + target.displayName() + " is now open to everyone. "
                        + "<gray>This survives a restart; undo it with <white>/asr unlock "
                        + target.argument() + " lock<gray>."
                : "<gray>" + target.displayName() + " was already unlocked.");
    }

    // -----------------------------------------------------------------------------------------
    // bypass
    // -----------------------------------------------------------------------------------------

    private void bypass(CommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, "<red>Usage: <yellow>" + Subcommand.BYPASS.usage());
            return;
        }
        Optional<Player> target = onlineTarget(sender, args[1]);
        if (target.isEmpty()) {
            return;
        }

        long requested = BypassDuration.DEFAULT_MILLIS;
        if (args.length >= 3) {
            OptionalLong parsedDuration = BypassDuration.parse(args[2]);
            if (parsedDuration.isEmpty()) {
                reply(sender, "<red>Could not read <yellow>" + escape(args[2]) + "<red> as a duration. "
                        + "Try <yellow>30m<red>, <yellow>2h<red>, <yellow>1d<red>, "
                        + "<yellow>permanent<red> or <yellow>off<red>.");
                return;
            }
            requested = parsedDuration.getAsLong();
        }

        Player player = target.get();
        BypassStore store = plugin.bypasses();
        long duration = requested;
        long expiresAt = duration == BypassDuration.PERMANENT
                ? BypassStore.PERMANENT
                : System.currentTimeMillis() + duration;

        // The grant is written to the player's persistent data container, which their region owns.
        player.getScheduler().run(plugin, task -> {
            if (duration == BypassDuration.REVOKE) {
                boolean held = store.revoke(player);
                reply(sender, held
                        ? "<yellow>Revoked " + escape(player.getName()) + "'s bypass grant."
                        : "<gray>" + escape(player.getName()) + " held no bypass grant.");
                return;
            }
            store.grant(player, expiresAt);
            reply(sender, "<green>Granted <yellow>" + escape(player.getName())
                    + "<green> a bypass for <yellow>" + BypassDuration.format(duration)
                    + "<green>. <gray>It is stored on the player and survives a restart.");
        }, () -> reply(sender, "<red>" + escape(player.getName())
                + " left before the bypass could be applied."));
    }

    // -----------------------------------------------------------------------------------------
    // inspect
    // -----------------------------------------------------------------------------------------

    private void inspect(CommandSender sender, PluginConfig config, String[] args) {
        if (args.length < 2) {
            reply(sender, "<red>Usage: <yellow>" + Subcommand.INSPECT.usage());
            return;
        }
        Optional<Player> target = onlineTarget(sender, args[1]);
        if (target.isEmpty()) {
            return;
        }
        Player player = target.get();

        // Capturing progression reads statistics and advancement progress, both region-owned. The
        // configuration local read at the top of onCommand is carried in rather than re-read, so
        // the whole report is evaluated against one snapshot even if a reload lands mid-flight.
        player.getScheduler().run(plugin, task -> reportOn(sender, config, player),
                () -> reply(sender, "<red>" + escape(player.getName())
                        + " left before their progression could be read."));
    }

    private void reportOn(CommandSender sender, PluginConfig config, Player player) {
        PlayerProgressionSnapshot snapshot = plugin.progression().snapshot(player, config);

        reply(sender, "<gold><bold>" + escape(player.getName()) + "<reset> <gray>— progression");
        reply(sender, "<gray>Playtime: <white>" + String.format(Locale.ROOT, "%.1f", snapshot.playtimeHours())
                + "h<gray>; tenure: <white>"
                + (snapshot.accountAgeKnown() ? snapshot.accountAgeDays() + "d" : "not recorded")
                + "<gray>; bypass: <white>"
                + (plugin.bypasses().hasBypass(player, System.currentTimeMillis())
                        ? "granted" : player.hasPermission("antispeedrun.bypass") ? "by permission" : "no"));

        for (Milestone milestone : Milestone.dimensionGates(config)) {
            EligibilityResult result = plugin.progression().evaluate(player, config, milestone);
            boolean overridden = DimensionUnlock.byMilestone(milestone.id())
                    .map(plugin.dimensionUnlocks()::isUnlocked)
                    .orElse(false);
            if (result.eligible()) {
                reply(sender, "  <green>✔ " + milestone.displayName() + "<gray> — unlocked"
                        + (overridden ? " <dark_gray>(also open server-wide)" : ""));
            } else if (overridden) {
                reply(sender, "  <yellow>◆ " + milestone.displayName()
                        + "<gray> — not earned, but open server-wide via <white>/asr unlock");
            } else {
                reply(sender, "  <red>✘ " + milestone.displayName() + "<gray> — " + outstanding(result));
            }
            result.fallbackHint().ifPresent(hint -> reply(sender, "    <dark_gray>" + escape(hint)));
        }

        if (Milestone.dimensionGates(config).isEmpty()) {
            reply(sender, "  <gray>No dimension gate is enabled in the live configuration.");
        }
    }

    /** A one-line description of what a player still owes a gate. */
    private static String outstanding(EligibilityResult result) {
        List<String> parts = new ArrayList<>();
        if (!result.missingAdvancements().isEmpty()) {
            parts.add("needs " + String.join(", ", result.missingAdvancements()));
        }
        if (result.missingPlaytimeHours() > 0.0D) {
            parts.add(String.format(Locale.ROOT, "%.1fh more playtime", result.missingPlaytimeHours()));
        }
        if (result.missingAccountAgeDays() > 0) {
            parts.add(result.missingAccountAgeDays() + " more day(s) of tenure");
        }
        return parts.isEmpty() ? "not yet eligible" : escape(String.join("; ", parts));
    }

    // -----------------------------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Resolves an online player by exact name, telling the sender when it cannot.
     *
     * <p>Online only, and this is a real limitation rather than an omission: both {@code bypass} and
     * {@code inspect} read or write state the server keeps in the player's own region — their
     * persistent data container, their statistics — and neither is reachable for a player who is not
     * loaded. Offline support means an {@code OfflinePlayer} playerdata read, which is blocking I/O
     * with different failure modes; it is not folded in silently here.
     */
    private Optional<Player> onlineTarget(CommandSender sender, String name) {
        Player player = plugin.getServer().getPlayerExact(name);
        if (player == null) {
            reply(sender, "<red>No online player named <yellow>" + escape(name)
                    + "<red>. This command works on players who are online.");
            return Optional.empty();
        }
        return Optional.of(player);
    }

    private void sendUsage(CommandSender sender, String label) {
        reply(sender, "<gold><bold>AntiSpeedrun<reset> <gray>administration");
        boolean any = false;
        for (Subcommand subcommand : Subcommand.values()) {
            if (sender.hasPermission(subcommand.permission())) {
                any = true;
                reply(sender, "  <yellow>" + subcommand.usage().replace("/asr", "/" + label));
            }
        }
        if (!any) {
            reply(sender, "  <red>You have permission to use none of its subcommands.");
        }
    }

    private void reply(CommandSender sender, String miniMessage) {
        sender.sendMessage(render(miniMessage));
    }

    private static Component render(String miniMessage) {
        return MINI.deserialize(PREFIX + miniMessage);
    }

    /**
     * Neutralises MiniMessage markup in text that came from outside.
     *
     * <p>Player names, configuration values and exception messages all end up inside a string that
     * is about to be deserialised. A name or an advancement key containing {@code <} would
     * otherwise be parsed as a tag — at best mangling the line, at worst letting an argument inject
     * formatting into an operator's console.
     */
    private static String escape(String raw) {
        return MINI.escapeTags(raw);
    }
}
