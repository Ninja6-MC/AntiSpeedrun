package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.listeners.NaturalTrimLootRules.Looter;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.TrimProgressionManager;
import com.ninja6.antispeedrun.storage.ExploredStructureStore;

/**
 * The natural loot lock, {@code trim-progression.gate-natural-trim-chests} (#198): a gated smithing
 * template generated into a structure chest, or a chest minecart, for a player who has not explored
 * the structure it comes from is removed from that loot.
 *
 * <p>The loot is generated once, when the container is first opened or broken, so a template
 * removed here is gone for everyone. A player who opens the chest after earning the structure finds
 * the rest of the loot as rolled.
 *
 * <h2>Who is judged</h2>
 *
 * <ul>
 *   <li><strong>A player owned by this region</strong> — the usual case, since a player opens or
 *       breaks a container within reach of it. Evaluated live through
 *       {@link TrimProgressionManager}, and the answer recorded in {@link ExploredStructureStore}.
 *       The item bypass ({@code antispeedrun.bypass.items} or a {@code /asr bypass} grant) waives
 *       the lock.</li>
 *   <li><strong>A player owned by another region</strong> — judged from
 *       {@link ExploredStructureStore}, as a Crafter's owner is, because their advancements cannot
 *       be read from here. Bypasses are not read either.</li>
 *   <li><strong>No player</strong> — a hopper or hopper minecart pulling from an unopened
 *       container, or a non-player breaking one. Gated templates are refused, the same rule as a
 *       Crafter with no recorded owner.</li>
 * </ul>
 *
 * <p>Loot a plugin generates by calling {@code LootTable#fillInventory} is not natural loot and is
 * left alone.
 *
 * <h2>Folia</h2>
 *
 * The event fires on the region owning the container. {@code Bukkit.isOwnedByCurrentRegion} decides
 * whether the player may be evaluated inline; otherwise the handler reads one volatile field of the
 * store. File writes go to the store's I/O executor.
 */
public final class NaturalTrimLootListener implements Listener {

    private final AntiSpeedrunPlugin plugin;

    private final TrimProgressionManager trims;

    private final ExploredStructureStore explored;

    public NaturalTrimLootListener(AntiSpeedrunPlugin plugin, ExploredStructureStore explored) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.explored = Objects.requireNonNull(explored, "explored");
        this.trims = new TrimProgressionManager(plugin.progression());
    }

    /** Removes the gated templates the looter has not earned from freshly generated loot. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLootGenerate(LootGenerateEvent event) {
        if (event.isPlugin()) {
            return;
        }
        PluginConfig config = plugin.configuration();
        if (!NaturalTrimLootRules.locked(config)) {
            return;
        }
        Entity entity = event.getEntity();
        Player player = entity instanceof Player p ? p : null;
        Looter looter;
        if (player == null) {
            looter = Looter.NONE;
        } else if (Bukkit.isOwnedByCurrentRegion(player)) {
            if (waived(player)) {
                return;
            }
            looter = Looter.LIVE;
        } else {
            looter = Looter.RECORDED;
        }
        UUID id = player == null ? null : player.getUniqueId();
        Predicate<Milestone> live = milestone -> {
            EligibilityResult outcome = trims.evaluate(player, config, milestone);
            explored.record(id, milestone.id(), outcome.eligible());
            return outcome.eligible();
        };
        NaturalTrimLootRules.strip(event.getLoot(), ItemStack::getType,
                NaturalTrimLootRules.earned(looter, id, live, explored::hasExplored));
    }

    private boolean waived(Player player) {
        return ItemGateRules.waived(
                player.hasPermission(ItemProgressionListener.BYPASS_PERMISSION),
                plugin.bypasses().hasBypass(player, System.currentTimeMillis()));
    }
}
