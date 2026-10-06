package com.ninja6.antispeedrun.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A credit change refreshes the player's gates the way an advancement does (#216). */
class CreditRefreshTest {

    /** A player answering only identity and {@code isOnline}; anything else fails the test. */
    private static Player player(UUID id, AtomicBoolean online) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isOnline" -> online.get();
                    case "getUniqueId" -> id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "player(" + id + ")";
                    default -> throw new AssertionError("player was read: " + method.getName());
                });
    }

    /** The server as the refresh sees it, recording what it was asked to do. */
    private static final class FakeHost implements CreditRefresh.Host {
        final Map<UUID, Player> online = new HashMap<>();
        boolean ownedHere;
        boolean retired;
        final List<Runnable> scheduled = new ArrayList<>();

        @Override
        public Player online(UUID player) {
            return online.get(player);
        }

        @Override
        public boolean ownedHere(Player player) {
            return ownedHere;
        }

        @Override
        public boolean schedule(Player player, Runnable task) {
            if (retired) {
                return false;
            }
            scheduled.add(task);
            return true;
        }
    }

    private final FakeHost host = new FakeHost();
    private final List<String> log = new ArrayList<>();
    private final CreditRefresh refresh = new CreditRefresh(host,
            id -> log.add("invalidate " + id),
            player -> log.add("refresh " + player.getUniqueId()));

    @Test
    @DisplayName("on the player's own region the gate is invalidated and announced in the same call")
    void ownedRegionRefreshesInline() {
        UUID id = UUID.randomUUID();
        host.online.put(id, player(id, new AtomicBoolean(true)));
        host.ownedHere = true;

        refresh.changed(id);

        assertEquals(List.of("invalidate " + id, "refresh " + id), log);
        assertTrue(host.scheduled.isEmpty());
    }

    @Test
    @DisplayName("off the player's region the cache drops now and the announcement goes to their scheduler")
    void otherRegionHandsOff() {
        UUID id = UUID.randomUUID();
        host.online.put(id, player(id, new AtomicBoolean(true)));
        host.ownedHere = false;

        refresh.changed(id);
        assertEquals(List.of("invalidate " + id), log, "nothing evaluates off the owning region");
        assertEquals(1, host.scheduled.size());

        host.scheduled.get(0).run();
        assertEquals(List.of("invalidate " + id, "refresh " + id), log);
    }

    @Test
    @DisplayName("a player who quits before the handed-off task runs is not evaluated")
    void quitBeforeTheTaskRuns() {
        UUID id = UUID.randomUUID();
        AtomicBoolean online = new AtomicBoolean(true);
        host.online.put(id, player(id, online));

        refresh.changed(id);
        online.set(false);
        host.scheduled.get(0).run();

        assertEquals(List.of("invalidate " + id), log);
    }

    @Test
    @DisplayName("an offline player only has the cache dropped; the next join announces")
    void offlinePlayerIsInvalidatedOnly() {
        UUID id = UUID.randomUUID();

        refresh.changed(id);

        assertEquals(List.of("invalidate " + id), log);
        assertTrue(host.scheduled.isEmpty());
    }

    @Test
    @DisplayName("a retired scheduler is not an error")
    void retiredScheduler() {
        UUID id = UUID.randomUUID();
        host.online.put(id, player(id, new AtomicBoolean(true)));
        host.retired = true;

        refresh.changed(id);

        assertEquals(List.of("invalidate " + id), log);
    }
}
