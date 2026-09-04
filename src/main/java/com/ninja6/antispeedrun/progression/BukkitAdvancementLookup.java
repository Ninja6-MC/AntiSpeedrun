package com.ninja6.antispeedrun.progression;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

/**
 * {@link AdvancementLookup} backed by the running server.
 *
 * <p>Two null returns matter here and neither is documented as such at the call site, which is why
 * this is a named class rather than a lambda:
 *
 * <ul>
 *   <li>{@code NamespacedKey.fromString} returns {@code null} — it does not throw — for a key that
 *       is not {@code namespace:path}, so a typo in {@code config.yml} arrives as {@code null}.</li>
 *   <li>{@code Bukkit.getAdvancement} returns {@code null} for a well-formed key that names no
 *       loaded advancement. That covers a renamed vanilla advancement, a datapack that removes the
 *       tree, and a server started with advancements switched off.</li>
 * </ul>
 *
 * Either yields {@link State#UNRESOLVABLE}, logged once per key so a misconfigured server says so
 * in the log exactly once rather than on every pickup attempt.
 */
public final class BukkitAdvancementLookup implements AdvancementLookup {

    private final Logger logger;

    /**
     * Keys already reported as unresolvable. Concurrent because the first lookup of a given key can
     * happen on any region thread; the set is bounded by the number of distinct keys in
     * {@code config.yml}, so it needs no eviction.
     */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    public BukkitAdvancementLookup(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public State state(Player player, String key) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");

        NamespacedKey namespaced = NamespacedKey.fromString(key);
        if (namespaced == null) {
            report(key, "is not a valid namespaced key (expected namespace:path)");
            return State.UNRESOLVABLE;
        }

        Advancement advancement = Bukkit.getAdvancement(namespaced);
        if (advancement == null) {
            report(key, "names no advancement on this server; it may have been renamed, removed by "
                    + "a datapack, or the server may be running without vanilla advancements");
            return State.UNRESOLVABLE;
        }

        return player.getAdvancementProgress(advancement).isDone() ? State.EARNED : State.NOT_EARNED;
    }

    private void report(String key, String reason) {
        if (reported.add(key)) {
            logger.log(Level.WARNING, "require-advancements entry \"{0}\" {1}. "
                    + "That requirement will be skipped rather than blocking players forever.",
                    new Object[] {key, reason});
        }
    }
}
