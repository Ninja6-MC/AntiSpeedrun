package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent.ItemFrameChangeAction;

class ElytraCollectionListenerTest {
    private static final class TestStack extends ItemStack {
        private final Material type;
        TestStack(Material type) { this.type = type; }
        @Override public Material getType() { return type; }
        @Override public ItemStack clone() { return new TestStack(type); }
    }
    private final Map<NamespacedKey, Object> data = new HashMap<>();
    private Material material = Material.ELYTRA;
    private final UUID playerId = UUID.randomUUID();
    private final AtomicInteger earned = new AtomicInteger();
    private final PersistentDataContainer pdc = (PersistentDataContainer) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{PersistentDataContainer.class}, (p, m, a) -> switch (m.getName()) {
                case "set" -> { data.put((NamespacedKey) a[0], a[2]); yield null; }
                case "has" -> data.containsKey(a[0]);
                case "remove" -> { data.remove(a[0]); yield null; }
                default -> throw new AssertionError(m.getName());
            });
    private final ItemFrame frame = (ItemFrame) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{ItemFrame.class}, (p, m, a) -> switch (m.getName()) {
                case "getPersistentDataContainer" -> pdc;
                case "getItem" -> new TestStack(material);
                case "getType" -> EntityType.ITEM_FRAME;
                default -> throw new AssertionError(m.getName());
            });
    private final Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Player.class}, (p, m, a) -> switch (m.getName()) {
                case "getUniqueId" -> playerId;
                case "hasMetadata" -> false;
                default -> throw new AssertionError(m.getName());
            });
    private final ElytraCollectionListener listener = new ElytraCollectionListener(id -> {
        assertEquals(playerId, id);
        earned.incrementAndGet();
    });
    private PlayerItemFrameChangeEvent event(ItemFrameChangeAction action) {
        return new PlayerItemFrameChangeEvent(player, frame, new TestStack(material), action);
    }
    private void mark() {
        data.put(new NamespacedKey("antispeedrun", "natural-elytra-frame"), (byte) 1);
    }

    @Test void oldAndPlayerFramesCannotEarn() {
        assertFalse(ElytraCollectionListener.qualifies(event(ItemFrameChangeAction.REMOVE)));
        listener.onFrameChange(event(ItemFrameChangeAction.REMOVE));
        assertEquals(0, earned.get());
    }
    @Test void originalRemovalEarnsOnceAndRefillCannotEarn() {
        mark();
        assertTrue(ElytraCollectionListener.qualifies(event(ItemFrameChangeAction.REMOVE)));
        listener.onFrameChange(event(ItemFrameChangeAction.REMOVE));
        assertEquals(1, earned.get());
        listener.onFrameChange(event(ItemFrameChangeAction.PLACE));
        listener.onFrameChange(event(ItemFrameChangeAction.REMOVE));
        assertEquals(1, earned.get());
    }
    @Test void cancellationRetainsMarkerAndNeverEarns() {
        mark();
        PlayerItemFrameChangeEvent event = event(ItemFrameChangeAction.REMOVE);
        event.setCancelled(true);
        listener.onFrameChange(event);
        assertEquals(0, earned.get());
        assertTrue(ElytraCollectionListener.qualifies(event(ItemFrameChangeAction.REMOVE)));
    }
    @Test void rotationPreservesOriginalButReplacementInvalidates() {
        mark();
        listener.onFrameChange(event(ItemFrameChangeAction.ROTATE));
        assertTrue(ElytraCollectionListener.qualifies(event(ItemFrameChangeAction.REMOVE)));
        listener.onFrameChange(event(ItemFrameChangeAction.PLACE));
        assertFalse(ElytraCollectionListener.qualifies(event(ItemFrameChangeAction.REMOVE)));
        assertEquals(0, earned.get());
    }
    @Test void markedFrameWithDifferentItemCannotEarn() {
        mark();
        material = Material.DIAMOND;
        listener.onFrameChange(event(ItemFrameChangeAction.REMOVE));
        assertEquals(0, earned.get());
        assertTrue(data.isEmpty());
    }
}
