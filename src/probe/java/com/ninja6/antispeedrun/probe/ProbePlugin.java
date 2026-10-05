package com.ninja6.antispeedrun.probe;

import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EnderDragonPart;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The integration harness's observer (scripts/integration/README.md). It changes nothing: every
 * handler runs at MONITOR and only logs one {@code ASRPROBE} line, which the harness parses.
 *
 * <p>The damage line reports what AntiSpeedrun's own handler compares against, the damage type and
 * the final damage, so a probe can tell "the rule refused this hit" apart from "the hit never
 * landed".
 */
public final class ProbePlugin extends JavaPlugin implements Listener {

    private static final String BYPASS = "antispeedrun.bypass.anticheese";

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ServerLoadEvent event) {
        Plugin target = Bukkit.getPluginManager().getPlugin("AntiSpeedrun");
        log("server name=" + Bukkit.getName() + " version=" + quote(Bukkit.getVersion())
                + " bukkit=" + Bukkit.getBukkitVersion()
                + " antispeedrun=" + (target == null ? "missing" : target.getDescription().getVersion())
                + " enabled=" + (target != null && target.isEnabled()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        log("join name=" + player.getName() + " op=" + player.isOp()
                + " bypass=" + player.hasPermission("antispeedrun.bypass")
                + " anticheese-bypass=" + player.hasPermission(BYPASS));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        Entity victim = entity instanceof EnderDragonPart part ? part.getParent() : entity;
        if (!(victim instanceof EnderDragon) && !(victim instanceof Wither)) {
            return;
        }
        // A part's entity id is its dragon's plus one (head) to eight, in vanilla's part order.
        String part = entity instanceof EnderDragonPart ? "+" + (entity.getEntityId() - victim.getEntityId()) : "none";
        DamageSource source = event.getDamageSource();
        DamageType type = source.getDamageType();
        LivingEntity living = (LivingEntity) victim;
        log("damage victim=" + victim.getType().name() + " uuid=" + victim.getUniqueId() + " part=" + part
                + " type=" + key(type)
                + " bad-respawn-point=" + (type == DamageType.BAD_RESPAWN_POINT)
                + " causing=" + name(source.getCausingEntity())
                + " direct=" + name(source.getDirectEntity())
                + " cause=" + event.getCause().name()
                + " base=" + format(event.getDamage())
                + " final=" + format(event.getFinalDamage())
                + " cancelled=" + event.isCancelled()
                + " health=" + format(living.getHealth()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCrystal(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || event.getItem() == null
                || event.getItem().getType() != Material.END_CRYSTAL) {
            return;
        }
        log("crystal-click player=" + event.getPlayer().getName()
                + " world=" + block.getWorld().getName()
                + " block=" + block.getX() + "," + block.getY() + "," + block.getZ()
                + " clicked=" + block.getType().name()
                + " use-item=" + event.useItemInHand().name()
                + " cancelled=" + (event.useItemInHand() == Event.Result.DENY));
    }

    /**
     * {@code asrprobe parts}: where each part of every dragon tagged {@code asrp} is, so a probe
     * can aim a hit at one. Read on the dragon's own scheduler, as Folia requires.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !args[0].equals("parts")) {
            return false;
        }
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (EnderDragon dragon : world.getEntitiesByClass(EnderDragon.class)) {
                if (!dragon.getScoreboardTags().contains("asrp")) {
                    continue;
                }
                dragon.getScheduler().run(this, task -> {
                    StringBuilder line = new StringBuilder("parts dragon=" + dragon.getUniqueId()
                            + " at=" + point(dragon.getLocation().toVector()));
                    for (org.bukkit.entity.ComplexEntityPart part : dragon.getParts()) {
                        line.append(" +").append(part.getEntityId() - dragon.getEntityId()).append('=')
                                .append(point(part.getBoundingBox().getCenter()));
                    }
                    log(line.toString());
                }, null);
            }
        }
        return true;
    }

    private static String point(org.bukkit.util.Vector v) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", v.getX(), v.getY(), v.getZ());
    }

    private void log(String line) {
        getLogger().info("ASRPROBE " + line);
    }

    private static String key(DamageType type) {
        try {
            return type.getKey().toString();
        } catch (RuntimeException | LinkageError e) {
            return String.valueOf(type).toLowerCase(Locale.ROOT);
        }
    }

    private static String name(Entity entity) {
        return entity == null ? "none" : entity.getType().name();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "'") + "\"";
    }
}
