package com.ninja6.antispeedrun.listeners;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
 * <p>Also cancels bed and Respawn Anchor explosion damage to the same two bosses (#39, Task 7.1.1),
 * off unless {@code anti-cheese.block-bed-anchor-boss-damage}. Such a hit has no causing entity and
 * block explosion damage carries no block, so it is recognised by its damage type,
 * {@code BAD_RESPAWN_POINT}; see {@link BedAnchorDamageRules}. TNT, arrows and melee are not that type.
 * With no causing entity there is no player to check, so this rule cannot be bypassed.
 *
 * <p>Also refuses an End Crystal placed on the exit portal's centre column (#25, Task 7.1.3), off
 * unless {@code anti-cheese.block-exit-portal-crystal-place}. The four ritual positions on the
 * portal's rim are never refused; see {@link ExitPortalCrystalRules}.
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
 * <h2>Hits in the same tick</h2>
 *
 * Every hit a boss takes in one tick of its world's game time draws on one budget of the cap,
 * recorded in a {@link BossDamageLedger} once the event has gone through. A later hit in that
 * tick is clamped to what is left and cancelled once nothing is, so a stack of explosions
 * detonating together removes at most the cap (#203). Hits the bypass waives are neither capped
 * nor counted.
 *
 * <h2>Folia</h2>
 *
 * Called on the region owning the victim. The handlers read the event and the configuration
 * snapshot, touch only the victim's ledger entry, and schedule nothing.
 */
public final class AntiCheeseListener implements Listener {

    /** Waives the cap for the player causing the damage, as it waives every section 7 rule. */
    public static final String BYPASS_PERMISSION = EyeThrowListener.BYPASS_PERMISSION;

    private final AntiSpeedrunPlugin plugin;

    private final BossDamageLedger ledger = new BossDamageLedger();

    public AntiCheeseListener(AntiSpeedrunPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Last among the damage handlers that can still change the amount, so the cap sees the figure
     * every other plugin has settled on.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        LivingEntity boss = boss(event.getEntity());
        if (boss == null) {
            return;
        }
        PluginConfig config = plugin.configuration();
        if (BedAnchorDamageRules.armed(config)
                && event.getDamageSource().getDamageType() == DamageType.BAD_RESPAWN_POINT) {
            event.setCancelled(true);
            return;
        }
        if (!capped(config, event)) {
            return;
        }
        double cap = ledger.remaining(boss.getUniqueId(), boss.getWorld().getGameTime(),
                config.antiCheese().maxSingleHitBossDamage());
        if (!DamageCapRules.exceeds(event.getFinalDamage(), cap) || waived(event)) {
            return;
        }
        if (!DamageCapRules.exceeds(cap, 0.0D)) {
            event.setCancelled(true);
            return;
        }
        // Final damage is not linear in the base (Wither armour is not, absorption is a min()), so
        // the rescale is verified against the event and backed by a clamp that cannot miss.
        DamageCapRules.clampBase(event.getDamage(), base -> {
            event.setDamage(base);
            return event.getFinalDamage();
        }, cap);
    }

    /** Counts a hit that went through against its boss's budget for the tick. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageSettled(EntityDamageEvent event) {
        LivingEntity boss = boss(event.getEntity());
        if (boss == null || !capped(plugin.configuration(), event) || waived(event)) {
            return;
        }
        ledger.record(boss.getUniqueId(), boss.getWorld().getGameTime(), event.getFinalDamage(),
                System.currentTimeMillis());
    }

    /** Drops a dead boss's budget. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossDeath(EntityDeathEvent event) {
        if (boss(event.getEntity()) != null) {
            ledger.forget(event.getEntity().getUniqueId());
        }
    }

    /**
     * Refuses an End Crystal placed on the centre column of an End world. Placement is a right
     * click on a block, handled on the region owning that block.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrystalPlace(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null
                || event.getItem() == null || event.getItem().getType() != Material.END_CRYSTAL
                || block.getWorld().getEnvironment() != World.Environment.THE_END
                || !ExitPortalCrystalRules.isCentreColumn(block.getX(), block.getZ())
                || !ExitPortalCrystalRules.armed(plugin.configuration())) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS_PERMISSION)
                || plugin.bypasses().hasBypass(player, System.currentTimeMillis())) {
            return;
        }
        event.setCancelled(true);
    }

    /** The Ender Dragon or Wither an entity is or belongs to, or null for anything else. */
    private static LivingEntity boss(Entity victim) {
        if (victim instanceof EnderDragonPart part) {
            victim = part.getParent();
        }
        return victim instanceof EnderDragon || victim instanceof Wither ? (LivingEntity) victim : null;
    }

    /** Whether the cap applies to this event: armed, and not {@code /kill}. */
    private static boolean capped(PluginConfig config, EntityDamageEvent event) {
        return DamageCapRules.armed(config)
                && event.getDamageSource().getDamageType() != DamageType.GENERIC_KILL;
    }

    private boolean waived(EntityDamageEvent event) {
        return event.getDamageSource().getCausingEntity() instanceof Player player
                && (player.hasPermission(BYPASS_PERMISSION)
                        || plugin.bypasses().hasBypass(player, System.currentTimeMillis()));
    }
}
