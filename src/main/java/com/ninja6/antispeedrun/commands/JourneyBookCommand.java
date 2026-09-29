package com.ninja6.antispeedrun.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * {@code /journeybook} (alias {@code /rulesbook}), {@code /asr book} which delegates to it, and the
 * first-join delivery — Task 2.2.2 (#5).
 *
 * <p>The page text is {@link JourneyBookPages}', generated from the configuration snapshot and unit
 * tested. What is left here is the item: a written book with that text, the configured title and
 * author, and a marker in its persistent data container so a copy the player already carries can be
 * recognised.
 *
 * <h2>First join is a flag, not {@code hasPlayedBefore()}</h2>
 *
 * The issue's acceptance criterion names {@code !player.hasPlayedBefore()}. Finding C-08 replaced
 * that before this task was picked up: it is false for every player who joined before the plugin
 * was installed, so an established server would enable the feature and nobody would ever receive
 * the book. Delivery is decided by the persisted flag in
 * {@link com.ninja6.antispeedrun.storage.JourneyBookStore} instead: absent means not yet delivered,
 * whoever the player is. The flag is set whenever a copy is handed out, including through this
 * command, so a player who fetched one while {@code give-on-first-join} was off is not given a second
 * when an operator turns it on.
 *
 * <h2>Threading</h2>
 *
 * Every touch of the player — their inventory, their persistent data container, a drop at their
 * feet — runs on the <strong>player's own {@code EntityScheduler}</strong>, the same shape as
 * {@link ProgressCommand}. The first-join grant is delayed by one tick (finding R-08: an entity task
 * needs an initial delay of at least one), which also lets the join finish and any kit plugin hand
 * out its items before the book looks for room. The retired callback is {@code null} in both: a
 * player who left before the task ran needs nothing done, and their flag is still absent, so the
 * next join tries again. The configuration snapshot is read once and carried into the task.
 */
public final class JourneyBookCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** What the console is told. */
    static final String CONSOLE_REFUSAL =
            "<red>/journeybook hands the book to the player who runs it, so it needs a player.";

    static final String ALREADY_HELD =
            "<yellow>You already carry the Journey Guide Book. Drop it first if you want a fresh copy.";

    static final String INVENTORY_FULL =
            "<red>Your inventory is full. Make room and run <gold>/journeybook<red> again.";

    static final String HANDED_OUT = "<green>You received the Journey Guide Book.";

    private final AntiSpeedrunPlugin plugin;

    /** Marks an item as a Journey Guide Book this plugin made. */
    private final NamespacedKey marker;

    public JourneyBookCommand(AntiSpeedrunPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.marker = new NamespacedKey(plugin, "journey-book");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        give(sender, plugin.configuration());
        return true;
    }

    /**
     * Hands the sender a copy on request.
     *
     * <p>Package-private and taking the snapshot as an argument so that {@link AntiSpeedrunCommand}
     * can delegate {@code /asr book} here with the snapshot it has already read, as it does for
     * {@code /asr progress}.
     *
     * <p>Refused, rather than duplicated, while the player already carries a copy: the command
     * defaults to {@code true} for everyone, and a book per invocation is a way to fill a chest,
     * or the ground, with written books. With a full inventory the command refuses instead of
     * dropping the book, for the same reason.
     */
    void give(CommandSender sender, PluginConfig config) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MINI.deserialize(CONSOLE_REFUSAL));
            return;
        }
        player.getScheduler().run(plugin, task -> {
            if (!player.isOnline()) {
                return;
            }
            if (carriesCopy(player)) {
                player.sendMessage(MINI.deserialize(ALREADY_HELD));
                return;
            }
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(book(config));
            if (!leftover.isEmpty()) {
                player.sendMessage(MINI.deserialize(INVENTORY_FULL));
                return;
            }
            plugin.journeyBook().markDelivered(player);
            player.sendMessage(MINI.deserialize(HANDED_OUT));
        }, null);
    }

    /**
     * The first-join grant: gives the book to a player who has never been given one, if
     * {@code journey-book.give-on-first-join} is on.
     *
     * <p>Called from the join listener with the snapshot it read. With the setting off nothing is
     * recorded either, so a later switch to {@code true} reaches everyone who has not had a copy.
     * A full inventory drops the book at the player's feet rather than skipping it: this is the one
     * delivery nobody asked for, and a player who never sees it has no reason to know the command
     * exists.
     */
    public void grantOnFirstJoin(Player player, PluginConfig config) {
        Objects.requireNonNull(player, "player");
        if (!config.journeyBook().giveOnFirstJoin()) {
            return;
        }
        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline() || plugin.journeyBook().hasReceived(player)) {
                return;
            }
            ItemStack book = book(config);
            for (ItemStack rest : player.getInventory().addItem(book).values()) {
                player.getWorld().dropItem(player.getLocation(), rest);
            }
            plugin.journeyBook().markDelivered(player);
        }, null, 1L);
    }

    /** The book as it is handed out: a fresh item built from {@code config}. */
    ItemStack book(PluginConfig config) {
        PluginConfig.JourneyBook settings = config.journeyBook();
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();

        meta.title(Component.text(JourneyBookPages.plainTitle(settings.title())));
        meta.author(Component.text(settings.author()));
        // The stored title is plain and capped at 32 characters, so the styled title is carried as
        // the item's name. Not italic: a custom name is italic by default, and the configured
        // title has not asked for it.
        meta.displayName(MINI.deserialize(settings.title())
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));

        List<Component> pages = new ArrayList<>();
        for (String page : JourneyBookPages.pages(config)) {
            pages.add(MINI.deserialize(page));
        }
        meta.pages(pages);
        meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Whether the player's own inventory already holds a book this plugin made. */
    private boolean carriesCopy(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.WRITTEN_BOOK && item.hasItemMeta()
                    && item.getItemMeta().getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) {
                return true;
            }
        }
        return false;
    }

    /** {@code /journeybook} takes no arguments. */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return Collections.emptyList();
    }
}
