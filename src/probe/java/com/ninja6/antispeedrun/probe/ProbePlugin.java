package com.ninja6.antispeedrun.probe;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EnderDragonPart;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.TradeSelectEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The integration harness's observer (scripts/integration/README.md). It changes nothing: every
 * handler runs at MONITOR and only logs one {@code ASRPROBE} line, which the harness parses.
 *
 * <p>The damage line reports what AntiSpeedrun's own handler compares against, the damage type and
 * the final damage, so a probe can tell "the rule refused this hit" apart from "the hit never
 * landed". The pickup, click, trade, portal and world lines do the same for the gates.
 *
 * <p>The console-only {@code /asrprobe} command answers what vanilla commands cannot on every
 * platform: Folia has no {@code /data}, so an entity's health and a player's whereabouts are read
 * here, each on the thread that owns the entity. Its {@code teleport} is the one thing the plugin
 * does rather than observes: a plugin teleport, which the deliberate-teleport contract (#135) is
 * about.
 */
public final class ProbePlugin extends JavaPlugin implements Listener {

    private static final String BYPASS = "antispeedrun.bypass.anticheese";

    /** The scoreboard tag a probe gives every entity it summons and later asks about. */
    private static final String TAG = "asrp";

    /** Tagged entities in a loaded world, so a health query never searches a world. */
    private final Map<UUID, Entity> tracked = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("asrprobe answers the integration harness's console only.");
            return true;
        }
        if (args.length == 3 && args[0].equals("health")) {
            health(args[1].toUpperCase(Locale.ROOT), args[2]);
        } else if (args.length == 3 && args[0].equals("where")) {
            where(args[1], args[2]);
        } else if (args.length == 6 && args[0].equals("teleport")) {
            teleport(args);
        } else if (args.length == 9 && (args[0].equals("near") || args[0].equals("remove"))) {
            near(args);
        } else {
            log("usage health <entity-type> <query> | where <player> <query> "
                    + "| teleport <player> <world> <x> <y> <z> "
                    + "| near|remove <dimension> <x> <y> <z> <radius> <entity-type> <item-or-any> <query>");
        }
        return true;
    }

    /**
     * Counts the entities of a type within a radius of a point, on the thread that owns the point.
     * Folia refuses a positional selector from the console, which runs on no region; for an item,
     * only stacks of the named material count. {@code remove} also removes what it counts.
     */
    private void near(String[] args) {
        String query = args[8];
        NamespacedKey key = NamespacedKey.fromString(args[1]);
        World world = key == null ? null : Bukkit.getWorld(key);
        if (world == null) {
            log("near query=" + query + " unknown-world");
            return;
        }
        Location at = new Location(world, Double.parseDouble(args[2]), Double.parseDouble(args[3]),
                Double.parseDouble(args[4]));
        double radius = Double.parseDouble(args[5]);
        String type = args[6].toUpperCase(Locale.ROOT);
        String material = args[7].toUpperCase(Locale.ROOT);
        boolean remove = args[0].equals("remove");
        Bukkit.getRegionScheduler().execute(this, at, () -> {
            int count = 0;
            for (Entity entity : world.getNearbyEntities(at, radius, radius, radius)) {
                if (!entity.getType().name().equals(type)
                        || entity.getLocation().distance(at) > radius) {
                    continue;
                }
                if (!material.equals("ANY") && !(entity instanceof Item item
                        && item.getItemStack().getType().name().equals(material))) {
                    continue;
                }
                count++;
                if (remove) {
                    entity.remove();
                }
            }
            log("near query=" + query + " count=" + count);
        });
    }

    /** Logs the health of each tagged entity of {@code type}, or that there is none. */
    private void health(String type, String query) {
        int found = 0;
        for (Entity entity : tracked.values()) {
            if (!entity.getType().name().equals(type)) {
                continue;
            }
            found++;
            UUID id = entity.getUniqueId();
            Runnable gone = () -> log("health query=" + query + " uuid=" + id + " alive=false health=none");
            if (entity.getScheduler().run(this, task -> {
                boolean alive = entity.isValid() && !entity.isDead();
                String health = entity instanceof LivingEntity living ? format(living.getHealth()) : "none";
                log("health query=" + query + " uuid=" + id + " alive=" + alive + " health=" + health);
            }, gone) == null) {
                gone.run();
            }
        }
        if (found == 0) {
            log("health query=" + query + " none");
        }
    }

    /** Logs where a player is and what they hold, as the server has it. */
    private void where(String name, String query) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            log("where query=" + query + " offline");
            return;
        }
        Runnable gone = () -> log("where query=" + query + " offline");
        if (player.getScheduler().run(this, task -> {
            Location at = player.getLocation();
            log("where query=" + query + " world=" + at.getWorld().getName()
                    + " environment=" + at.getWorld().getEnvironment().name()
                    + " x=" + format(at.getX()) + " y=" + format(at.getY()) + " z=" + format(at.getZ())
                    + " vehicle=" + name(player.getVehicle())
                    + " dead=" + player.isDead()
                    + " op=" + player.isOp()
                    + " bypass=" + player.hasPermission("antispeedrun.bypass")
                    + " bypass-gates=" + player.hasPermission("antispeedrun.bypass.gates")
                    + " bypass-items=" + player.hasPermission("antispeedrun.bypass.items"));
        }, gone) == null) {
            gone.run();
        }
    }

    /** A plugin teleport, the kind another plugin's {@code /spawn} or {@code /home} performs. */
    private void teleport(String[] args) {
        Player player = Bukkit.getPlayerExact(args[1]);
        World world = Bukkit.getWorld(args[2]);
        if (player == null || world == null) {
            log("teleport player=" + args[1] + " world=" + args[2] + " result=unknown-target");
            return;
        }
        Location to = new Location(world, Double.parseDouble(args[3]), Double.parseDouble(args[4]),
                Double.parseDouble(args[5]));
        player.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(
                moved -> log("teleport player=" + player.getName() + " world=" + world.getName()
                        + " result=" + moved));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdded(EntityAddToWorldEvent event) {
        Entity entity = event.getEntity();
        if (entity.getScoreboardTags().contains(TAG)) {
            tracked.put(entity.getUniqueId(), entity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemoved(EntityRemoveFromWorldEvent event) {
        tracked.remove(event.getEntity().getUniqueId());
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        ItemStack stack = event.getItem().getItemStack();
        log("pickup player=" + event.getPlayer().getName() + " item=" + stack.getType().name()
                + " count=" + stack.getAmount() + " cancelled=" + event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        InventoryType top = event.getView().getTopInventory().getType();
        if (top == InventoryType.CRAFTING) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        log("click player=" + event.getWhoClicked().getName() + " top=" + top.name()
                + " slot=" + event.getRawSlot() + " action=" + event.getAction().name()
                + " item=" + (current == null ? "none" : current.getType().name())
                + " cancelled=" + event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTradeSelect(TradeSelectEvent event) {
        int index = event.getIndex();
        List<MerchantRecipe> recipes = event.getMerchant().getRecipes();
        String result = index >= 0 && index < recipes.size()
                ? recipes.get(index).getResult().getType().name() : "none";
        log("trade-select player=" + event.getWhoClicked().getName() + " index=" + index
                + " result=" + result + " cancelled=" + event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPortal(PlayerPortalEvent event) {
        log("portal player=" + event.getPlayer().getName() + " cause=" + event.getCause().name()
                + " to=" + worldOf(event.getTo()) + " cancelled=" + event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityPortal(EntityPortalEvent event) {
        log("entity-portal type=" + event.getEntity().getType().name()
                + " to=" + worldOf(event.getTo()) + " cancelled=" + event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        log("world player=" + event.getPlayer().getName() + " from=" + event.getFrom().getName()
                + " to=" + event.getPlayer().getWorld().getName());
    }

    private void log(String line) {
        getLogger().info("ASRPROBE " + line);
    }

    private static String worldOf(Location location) {
        return location == null || location.getWorld() == null ? "none" : location.getWorld().getName();
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
