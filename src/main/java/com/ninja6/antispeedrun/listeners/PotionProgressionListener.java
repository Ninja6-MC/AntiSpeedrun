package com.ninja6.antispeedrun.listeners;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.TradeSelectEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import net.kyori.adventure.text.Component;
import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.BrewingEffects;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.PersonalCreditAdvancementLookup;
import com.ninja6.antispeedrun.storage.BrewedEffectStore;

/** Personal effect gates apply to recipients, including dispenser and arrow delivery. */
public final class PotionProgressionListener implements Listener {
    private final AntiSpeedrunPlugin plugin;
    private final BrewedEffectStore credits;
    private final Map<UUID, Long> feedback = new ConcurrentHashMap<>();

    public PotionProgressionListener(AntiSpeedrunPlugin plugin, BrewedEffectStore credits) {
        this.plugin = plugin;
        this.credits = credits;
    }
    private boolean active(Player player, PluginConfig config) {
        return config.potionProgression().enabled()
                && (config.itemProgression().personalCreditScope() == PluginConfig.CreditScope.ALL
                    || player.hasPermission(PersonalCreditAdvancementLookup.REQUIRED_PERMISSION))
                && !player.hasPermission(ItemProgressionListener.BYPASS_PERMISSION)
                && !plugin.bypasses().hasBypass(player, System.currentTimeMillis());
    }
    private boolean locked(Player player, String effect, PluginConfig config) {
        return PotionGateRules.locked(effect, config.potionProgression().gatedEffects(),
                id -> credits.hasBrewed(player.getUniqueId(), id));
    }
    private String missing(Player player, ItemStack item) {
        PluginConfig config = plugin.configuration();
        if (!active(player, config)) return null;
        return PotionEffects.ids(item).stream().filter(id -> locked(player, id, config))
                .sorted().findFirst().orElse(null);
    }
    private void notify(Player player, String effect, String source) {
        long now = System.currentTimeMillis();
        long cooldown = plugin.configuration().itemProgression().feedbackCooldownSeconds() * 1000L;
        Long previous = feedback.get(player.getUniqueId());
        if (previous != null && now - previous < cooldown) return;
        feedback.put(player.getUniqueId(), now);
        player.sendActionBar(Component.text(source + "Potion of " + effect.replace('_', ' ')
                + " requires brewing it yourself."));
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        String effect = missing(event.getPlayer(), event.getItem().getItemStack());
        if (effect != null) { event.setCancelled(true); notify(event.getPlayer(), effect, ""); }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryType type = event.getView().getTopInventory().getType();
        if (type == InventoryType.CRAFTING || type == InventoryType.CREATIVE) return;
        boolean top = event.getRawSlot() >= 0
                && event.getRawSlot() < event.getView().getTopInventory().getSize();
        ItemStack item = switch (ItemGateRules.withdrawn(
                InventoryGestures.of(event.getAction(), event.getClick()), top)) {
            case CLICKED_SLOT -> event.getCurrentItem();
            case CURSOR -> event.getCursor();
            case NONE -> null;
        };
        String effect = missing(player, item);
        if (effect != null) { event.setCancelled(true); notify(player, effect, ""); }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrade(TradeSelectEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        var recipes = event.getMerchant().getRecipes();
        if (event.getIndex() < 0 || event.getIndex() >= recipes.size()) return;
        String effect = missing(player, recipes.get(event.getIndex()).getResult());
        if (effect != null) { event.setCancelled(true); notify(player, effect, ""); }
    }
    /** Mixed potions are consumed normally; the effect event filters only locked benefits. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent event) {
        String effect = missing(event.getPlayer(), event.getItem());
        if (effect != null && onlyLocked(event.getPlayer(), PotionEffects.effects(event.getItem()))) {
            event.setCancelled(true);
            notify(event.getPlayer(), effect, "");
        }
    }
    private boolean onlyLocked(Player player, List<PotionEffect> effects) {
        PluginConfig config = plugin.configuration();
        return active(player, config) && !effects.isEmpty() && effects.stream().allMatch(effect ->
                locked(player, BrewingEffects.canonical(effect.getType().getKey().getKey()), config));
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        for (var entity : event.getAffectedEntities()) {
            if (!(entity instanceof Player player) || !plugin.getServer().isOwnedByCurrentRegion(player)) continue;
            String missing = missing(player, event.getPotion().getItem());
            if (missing == null) continue;
            if (onlyLocked(player, PotionEffects.effects(event.getPotion().getItem()))) event.setIntensity(player, 0);
            String source = event.getPotion().getShooter() instanceof Player thrower ? thrower.getName() + "'s " : "";
            notify(player, missing, source);
        }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        var cloud = event.getEntity();
        java.util.ArrayList<PotionEffect> effects = new java.util.ArrayList<>();
        if (cloud.getBasePotionType() != null) effects.addAll(cloud.getBasePotionType().getPotionEffects());
        effects.addAll(cloud.getCustomEffects());
        event.getAffectedEntities().removeIf(entity -> {
            if (!(entity instanceof Player player) || !plugin.getServer().isOwnedByCurrentRegion(player)) return false;
            PluginConfig config = plugin.configuration();
            if (!active(player, config)) return false;
            String missing = effects.stream().map(effect -> BrewingEffects.canonical(effect.getType().getKey().getKey()))
                    .filter(id -> locked(player, id, config)).findFirst().orElse(null);
            if (missing == null) return false;
            notify(player, missing, "");
            return onlyLocked(player, effects);
        });
    }
    /** Bukkit exposes each effect separately, preserving harmful/healing parts of custom mixtures. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getNewEffect() == null) return;
        switch (event.getCause()) {
            case POTION_DRINK, POTION_SPLASH, AREA_EFFECT_CLOUD, ARROW -> { }
            default -> { return; }
        }
        PluginConfig config = plugin.configuration();
        String effect = BrewingEffects.canonical(event.getNewEffect().getType().getKey().getKey());
        if (active(player, config) && locked(player, effect, config)) {
            event.setCancelled(true);
            notify(player, effect, "");
        }
    }
    @EventHandler
    public void onQuit(PlayerQuitEvent event) { feedback.remove(event.getPlayer().getUniqueId()); }
}
