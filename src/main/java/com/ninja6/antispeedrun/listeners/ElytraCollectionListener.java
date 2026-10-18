package com.ninja6.antispeedrun.listeners;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.world.AsyncStructureGenerateEvent;
import org.bukkit.generator.structure.Structure;
import org.bukkit.persistence.PersistentDataType;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.storage.CreditSource;
import com.ninja6.antispeedrun.storage.PersonalCredit;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent.ItemFrameChangeAction;

/**
 * Marks only entities supplied by natural End City generation, never guesses from loaded frames.
 * The transformer edits its supplied entity, without looking up a world, chunk or player from the
 * generation thread. Later frame events read PDC on the entity's owning region. Credit storage and
 * refresh are thread-safe; refresh schedules the player if their region is different.
 */
public final class ElytraCollectionListener implements Listener {
    private static final NamespacedKey NATURAL = new NamespacedKey("antispeedrun", "natural-elytra-frame");
    private final java.util.function.Consumer<java.util.UUID> record;

    public ElytraCollectionListener(AntiSpeedrunPlugin plugin) {
        this(player -> {
            if (plugin.personalCredits().record(player, PersonalCredit.ELYTRA, CreditSource.ACTION)) {
                plugin.creditRefresh().changed(player);
            }
        });
    }

    ElytraCollectionListener(java.util.function.Consumer<java.util.UUID> record) {
        this.record = record;
    }

    /** Always mark, even while the option is off, so later enabling it preserves provenance. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStructure(AsyncStructureGenerateEvent event) {
        if (event.getCause() != AsyncStructureGenerateEvent.Cause.WORLD_GENERATION
                || !event.getStructure().equals(Structure.END_CITY)) {
            return;
        }
        event.setEntityTransformer(NATURAL, (region, x, y, z, entity, allowed) -> {
            if (allowed && entity instanceof ItemFrame frame
                    && frame.getType() == org.bukkit.entity.EntityType.ITEM_FRAME
                    && frame.getItem().getType() == Material.ELYTRA) {
                frame.getPersistentDataContainer().set(NATURAL, PersistentDataType.BYTE, (byte) 1);
            }
            return allowed;
        });
    }

    /** True only while the original generation-marked Elytra remains untouched. */
    public static boolean qualifies(PlayerItemFrameChangeEvent event) {
        return event.getAction() == ItemFrameChangeAction.REMOVE
                && event.getItemStack().getType() == Material.ELYTRA
                && event.getItemFrame().getItem().getType() == Material.ELYTRA
                && event.getItemFrame().getPersistentDataContainer().has(NATURAL, PersistentDataType.BYTE)
                && !event.getPlayer().hasMetadata(PersonalCreditListener.NPC_METADATA);
    }

    /** A cancelled removal consumes neither the marker nor the player's missing credit. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameChange(PlayerItemFrameChangeEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (qualifies(event)) {
            record.accept(event.getPlayer().getUniqueId());
        }
        if (event.getAction() != ItemFrameChangeAction.ROTATE) {
            event.getItemFrame().getPersistentDataContainer().remove(NATURAL);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(HangingPlaceEvent event) {
        event.getEntity().getPersistentDataContainer().remove(NATURAL);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(HangingBreakEvent event) {
        event.getEntity().getPersistentDataContainer().remove(NATURAL);
    }
}
