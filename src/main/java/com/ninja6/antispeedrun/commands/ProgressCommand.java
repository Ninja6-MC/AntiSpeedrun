package com.ninja6.antispeedrun.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.IdleReminderRules.MilestoneProgress;
import com.ninja6.antispeedrun.progression.Milestone;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * {@code /progress}, and {@code /asr progress} which delegates to it — Task 2.1.2 (#3).
 *
 * <p>The player-facing half of the progression feature: what have I unlocked, what am I working on,
 * and what do I do next. {@code /asr inspect <player>} answers the same question about somebody
 * else, for an operator, and the two are deliberately different commands with different permissions
 * — {@code antispeedrun.progress} defaults to {@code true} because every player is entitled to know
 * what they themselves owe a gate, and {@code antispeedrun.admin.inspect} defaults to {@code op}
 * because reading another player's progression is administration.
 *
 * <p>Everything decidable from values is {@link ProgressCardRenderer}'s, which is unit tested; this
 * class reads the player, picks a style and sends the lines.
 *
 * <h2>Threading</h2>
 *
 * The card is built on the <strong>viewer's own {@code EntityScheduler}</strong>, exactly as
 * {@code /asr inspect} builds its report: {@code ProgressionManager#evaluate} reads the player's
 * statistics and advancement progress, both owned by the region that owns them, and a command
 * handler for a console sender does not run on that region. The configuration snapshot is read once
 * here and carried into the task, so a reload landing mid-flight cannot produce a card built half
 * from each.
 */
public final class ProgressCommand implements CommandExecutor, TabCompleter {

    /** The Floodgate plugin's name in {@code plugin.yml}, lower-case as Bukkit registers it. */
    private static final String FLOODGATE = "floodgate";

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final AntiSpeedrunPlugin plugin;

    public ProgressCommand(AntiSpeedrunPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        show(sender, plugin.configuration());
        return true;
    }

    /**
     * Sends one player their card.
     *
     * <p>Package-private and taking the snapshot as an argument so that {@link AntiSpeedrunCommand}
     * can delegate {@code /asr progress} here without re-reading the configuration — the dispatcher
     * has already read it once at the top of its own {@code onCommand}, and reading it twice inside
     * one decision is what {@code com.ninja6.antispeedrun.config} forbids.
     */
    void show(CommandSender sender, PluginConfig config) {
        if (!(sender instanceof Player player)) {
            // Not an oversight and not worth a fallback: the card is about the sender's own
            // progression, and the console has none. The operator's command is /asr inspect.
            sender.sendMessage(MINI.deserialize(
                    "<red>/progress shows your own progression, so it needs a player. "
                            + "From the console, use <yellow>/asr inspect &lt;player&gt;<red>."));
            return;
        }

        ProgressCardRenderer.Style style = ProgressCardRenderer.styleFor(
                config.progressCard().simpleCard(), isBedrock(player));

        // The viewer's own region: evaluate() reads their statistics and advancement progress.
        // The retired callback is null because a player who left before their own card was built
        // needs nothing done; there is no state to unwind and nobody to apologise to.
        player.getScheduler().run(plugin, task -> {
            List<MilestoneProgress> progress = progressOf(player, config);
            for (String line : ProgressCardRenderer.card(player.getName(), progress, style)) {
                player.sendMessage(MINI.deserialize(line));
            }
            fallbackHints(player, progress);
        }, null);
    }

    /**
     * Every dimension milestone with the verdict on it, in configured order.
     *
     * <p>The same call shape {@code IdleReminderEngine#progressOf} uses, and evaluated through
     * {@link com.ninja6.antispeedrun.progression.ProgressionManager} so it reuses the cached
     * snapshot rather than re-reading the server.
     */
    private List<MilestoneProgress> progressOf(Player player, PluginConfig config) {
        List<Milestone> milestones = Milestone.dimensionGates(config);
        List<MilestoneProgress> progress = new ArrayList<>(milestones.size());
        for (Milestone milestone : milestones) {
            progress.add(new MilestoneProgress(
                    milestone, plugin.progression().evaluate(player, config, milestone)));
        }
        return progress;
    }

    /**
     * Tells the player when a requirement could not be checked at all.
     *
     * <p>Kept off the card proper because it is a statement about the server rather than about
     * them, and because the same hint can come back from more than one milestone; it is deduplicated
     * rather than repeated. {@code /asr inspect} prints the same text for the operator.
     */
    private void fallbackHints(Player player, List<MilestoneProgress> progress) {
        List<String> seen = new ArrayList<>(2);
        for (MilestoneProgress entry : progress) {
            EligibilityResult result = entry.result();
            result.fallbackHint().filter(hint -> !seen.contains(hint)).ifPresent(hint -> {
                seen.add(hint);
                player.sendMessage(MINI.deserialize("  <dark_gray>" + MINI.escapeTags(hint)));
            });
        }
    }

    /**
     * Whether this player is on a Bedrock client, as far as anything without the Floodgate API can
     * tell.
     *
     * <p>Two conditions, and the first is what keeps the second honest: Floodgate has to actually be
     * enabled on this server before a player id is read as one it minted. See
     * {@link ProgressCardRenderer#floodgateId}. On a server with no Floodgate this is a single map
     * lookup that answers false, which is the correct answer there.
     */
    private boolean isBedrock(Player player) {
        return plugin.getServer().getPluginManager().isPluginEnabled(FLOODGATE)
                && ProgressCardRenderer.floodgateId(player.getUniqueId());
    }

    /** {@code /progress} takes no arguments, so a tab press offers nothing rather than player names. */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return Collections.emptyList();
    }
}
