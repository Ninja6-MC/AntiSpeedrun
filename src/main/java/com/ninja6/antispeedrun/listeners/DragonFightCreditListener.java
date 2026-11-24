package com.ninja6.antispeedrun.listeners;

import java.util.Set;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import com.ninja6.antispeedrun.progression.CreditRefresh;
import com.ninja6.antispeedrun.storage.CreditSource;
import com.ninja6.antispeedrun.storage.DragonContributorStore;
import com.ninja6.antispeedrun.storage.PersonalCredit;
import com.ninja6.antispeedrun.storage.PersonalCreditStore;
import com.ninja6.antispeedrun.storage.RespawnCooldownStore;

/**
 * Credits {@link PersonalCredit#DRAGON_FIGHT} to every real player who contributed to a dragon fight
 * that was then won (#278). The decisions are in {@link DragonFightRules}.
 *
 * <h2>Contributors</h2>
 *
 * A contributor dealt positive, uncancelled damage, after damage caps settle, to any Ender Dragon in
 * an End world: the fight's primary or one of its secondaries. Damage is attributed as for a Wither:
 * the damage source's causing entity, so an arrow counts for its shooter and an unattributed
 * explosion counts for nobody. Being nearby, a cancelled hit and a hit for zero do not count, and nor
 * does a player carrying {@code NPC} metadata. Contributors are kept per world in
 * {@link DragonContributorStore}, so a restart or an unloaded dragon loses nothing.
 *
 * <h2>Victory</h2>
 *
 * The fight is won when its primary dragon, any dragon in an End world not tagged as a secondary,
 * actually dies: the death event is not cancelled by the time every other handler has run. While
 * secondaries live, {@link BossCombatListener} cancels the primary's death, so a secondary's death,
 * and the refused primary death before it, never complete a fight. That covers the world's first
 * fight, a fight resummoned with four End crystals, and a scaled fight alike. Every contributor is
 * credited at once, including players who have logged off.
 *
 * <p>A primary removed any other way than by dying, unloading or its chunk's player leaving, such as
 * by another plugin, abandons the fight: its contributors are discarded and earn nothing.
 *
 * <p>The same victory stamps each contributor's respawn cooldown in {@link RespawnCooldownStore}
 * (#272), once per won fight and never for a secondary's death. The stamp and the credit are
 * separate records: the credit is lifetime qualification for the outer End, the stamp only
 * decides when the player may next start a respawn by entering the End.
 *
 * <p>Recording stays active with the outer End rule off, like every other personal credit, so a
 * server that turns the rule on later has the fights already won on record.
 *
 * <h2>Folia</h2>
 *
 * Every handler runs on the region owning the dragon. It reads the dragon, the causing player's
 * UUID and thread-safe NPC metadata, and writes the two stores, which accept any thread;
 * {@link CreditRefresh} schedules online players on their own regions.
 */
public final class DragonFightCreditListener implements Listener {

    private final NamespacedKey secondaryDragon;
    private final DragonContributorStore contributors;
    private final PersonalCreditStore credits;
    private final RespawnCooldownStore cooldowns;
    private final CreditRefresh refresh;

    public DragonFightCreditListener(Plugin plugin, DragonContributorStore contributors,
                                     PersonalCreditStore credits, RespawnCooldownStore cooldowns,
                                     CreditRefresh refresh) {
        this.secondaryDragon = new NamespacedKey(plugin, BossCombatListener.SECONDARY_DRAGON_KEY);
        this.contributors = contributors;
        this.credits = credits;
        this.cooldowns = cooldowns;
        this.refresh = refresh;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)
                || !(event.getDamageSource().getCausingEntity() instanceof Player player)
                || !DragonFightRules.contributes(inEnd(dragon), event.getFinalDamage(), true,
                        player.hasMetadata(PersonalCreditListener.NPC_METADATA))) {
            return;
        }
        contributors.add(dragon.getWorld().getUID(), player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)
                || !DragonFightRules.wins(inEnd(dragon), isSecondary(dragon))) {
            return;
        }
        Set<UUID> players = contributors.take(dragon.getWorld().getUID());
        long victory = System.currentTimeMillis();
        for (UUID player : players) {
            cooldowns.stamp(player, victory);
            if (credits.record(player, PersonalCredit.DRAGON_FIGHT, CreditSource.ACTION)) {
                refresh.changed(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemoved(EntityRemoveEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon
                && DragonFightRules.abandons(inEnd(dragon), isSecondary(dragon), event.getCause().name())) {
            contributors.take(dragon.getWorld().getUID());
        }
    }

    private static boolean inEnd(EnderDragon dragon) {
        return dragon.getWorld().getEnvironment() == World.Environment.THE_END;
    }

    private boolean isSecondary(EnderDragon dragon) {
        return dragon.getPersistentDataContainer().has(secondaryDragon, PersistentDataType.BYTE);
    }
}
