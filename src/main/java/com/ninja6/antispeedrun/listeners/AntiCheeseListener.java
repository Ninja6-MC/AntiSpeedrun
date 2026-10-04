package com.ninja6.antispeedrun.listeners;

import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EnderDragonPart;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * Caps the damage one hit can do to the Ender Dragon or the Wither (#24, Task 7.1.2), so a stack of
 * TNT minecarts or an extreme Mace smash cannot skip the fight.
 *
 * <p>Off unless {@code anti-cheese.cap-single-hit-boss-damage} is set. The arithmetic is in
 * {@link DamageCapRules}.
 *
 * <h2>The dragon's parts</h2>
 *
 * Damage to the dragon arrives with an {@link EnderDragonPart} as the entity, never the dragon, so
 * the part is unwrapped to its parent before the victim check.
 *
 * <h2>Final damage</h2>
 *
 * The cap applies to {@code getFinalDamage()}, after armour and resistance, by scaling the base
 * damage. {@code /kill} is left alone: it is an operator's deliberate act, not a hit.
 *
 * <h2>Folia</h2>
 *
 * Called on the region owning the victim. The handler reads the event and the configuration
 * snapshot and schedules nothing.
 */
public final class AntiCheeseListener implements Listener {

    /** Waives the cap for the player causing the damage, as it waives every section 7 rule. */
    public static final String BYPASS_PERMISSION = EyeThrowListener.BYPASS_PERMISSION;

    private final AntiSpeedrunPlugin plugin;

    public AntiCheeseListener(AntiSpeedrunPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Last among the damage handlers that can still change the amount, so the cap sees the figure
     * every other plugin has settled on.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        if (victim instanceof EnderDragonPart part) {
            victim = part.getParent();
        }
        if (!(victim instanceof EnderDragon) && !(victim instanceof Wither)) {
            return;
        }
        PluginConfig config = plugin.configuration();
        if (!DamageCapRules.armed(config)
                || event.getDamageSource().getDamageType() == DamageType.GENERIC_KILL) {
            return;
        }
        double cap = config.antiCheese().maxSingleHitBossDamage();
        if (!DamageCapRules.exceeds(event.getFinalDamage(), cap) || waived(event)) {
            return;
        }
        // Up to three passes: absorption and resistance are linear in the base, but a second pass is cheap
        // insurance against a modifier that is not, and the loop stops as soon as the cap holds.
        for (int pass = 0; pass < 3 && DamageCapRules.exceeds(event.getFinalDamage(), cap); pass++) {
            event.setDamage(DamageCapRules.scaledBase(
                    event.getDamage(), event.getFinalDamage(), cap));
        }
    }

    private boolean waived(EntityDamageEvent event) {
        return event.getDamageSource().getCausingEntity() instanceof Player player
                && (player.hasPermission(BYPASS_PERMISSION)
                        || plugin.bypasses().hasBypass(player, System.currentTimeMillis()));
    }
}
