package com.ninja6.antispeedrun.listeners;

import java.util.Set;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import com.ninja6.antispeedrun.progression.CreditRefresh;
import com.ninja6.antispeedrun.storage.CreditSource;
import com.ninja6.antispeedrun.storage.PersonalCredit;
import com.ninja6.antispeedrun.storage.PersonalCreditStore;

/**
 * Credits every real player who damaged a Wither that subsequently died. Damage and death are
 * observed after cancellation and damage caps settle. The damage source attributes ordinary
 * projectiles to their shooter; nearby players and unattributed environmental damage earn nothing.
 *
 * <p>Contributors live on the Wither's PDC, surviving chunk unloads and restarts. Access is always
 * on the Wither's owning region. Only a player's UUID and thread-safe NPC metadata are read from
 * the causing entity; the store accepts offline UUIDs and CreditRefresh schedules online players
 * on their own regions. Recording stays active with gates disabled, like other personal credits.
 */
public final class WitherCreditListener implements Listener {
    private final NamespacedKey contributors;
    private final PersonalCreditStore credits;
    private final CreditRefresh refresh;

    public WitherCreditListener(Plugin plugin, PersonalCreditStore credits, CreditRefresh refresh) {
        this.contributors = new NamespacedKey(plugin, "wither_contributors");
        this.credits = credits;
        this.refresh = refresh;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Wither wither)
                || !(event.getDamageSource().getCausingEntity() instanceof Player player)
                || !WitherContributors.counts(event.getFinalDamage(), true,
                        player.hasMetadata(PersonalCreditListener.NPC_METADATA))) {
            return;
        }
        PersistentDataContainer data = wither.getPersistentDataContainer();
        Set<UUID> players = WitherContributors.decode(data.get(contributors, PersistentDataType.LONG_ARRAY));
        if (players.add(player.getUniqueId())) {
            data.set(contributors, PersistentDataType.LONG_ARRAY, WitherContributors.encode(players));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Wither wither)) {
            return;
        }
        PersistentDataContainer data = wither.getPersistentDataContainer();
        Set<UUID> players = WitherContributors.decode(data.get(contributors, PersistentDataType.LONG_ARRAY));
        data.remove(contributors);
        for (UUID player : players) {
            if (credits.record(player, PersonalCredit.KILL_WITHER, CreditSource.ACTION)) {
                refresh.changed(player);
            }
        }
    }
}
