package com.ninja6.antispeedrun.listeners;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.commands.JourneyBookCommand;

/**
 * Hands the Journey Guide Book to a player the first time they join with it enabled (#5).
 *
 * <p>One event, and no decision of its own: whether the player has already had a copy, and the
 * scheduling onto their region, are {@link JourneyBookCommand#grantOnFirstJoin}'s. Players already
 * online when the plugin enables fire no join for this listener; they receive the book on their
 * next join, since their delivered flag is still absent.
 */
public final class JourneyBookListener implements Listener {

    private final AntiSpeedrunPlugin plugin;
    private final JourneyBookCommand book;

    public JourneyBookListener(AntiSpeedrunPlugin plugin, JourneyBookCommand book) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.book = Objects.requireNonNull(book, "book");
    }

    /** {@code MONITOR}: it cancels nothing, and the grant itself runs a tick later. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        book.grantOnFirstJoin(event.getPlayer(), plugin.configuration());
    }
}
