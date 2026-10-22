package com.ninja6.antispeedrun.probe;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Filter;
import java.util.logging.LogRecord;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.listeners.ItemProgressionListener;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

/** Actual pickup decisions and actual file reloads; proxy entities never touch live region state. */
final class GateSnapshotProbe {
    private final AntiSpeedrunPlugin target;
    private final ItemProgressionListener listener;
    private final Path config;
    private int checks;

    GateSnapshotProbe(AntiSpeedrunPlugin target) {
        this.target = target;
        this.config = target.getDataFolder().toPath().resolve("config.yml");
        this.listener = HandlerList.getRegisteredListeners(target).stream()
                .map(registered -> registered.getListener())
                .filter(ItemProgressionListener.class::isInstance)
                .map(ItemProgressionListener.class::cast).findFirst().orElseThrow();
    }

    static void start(JavaPlugin observer) {
        AntiSpeedrunPlugin target = (AntiSpeedrunPlugin) Bukkit.getPluginManager().getPlugin("AntiSpeedrun");
        observer.getServer().getAsyncScheduler().runNow(observer, task -> {
            try {
                int checks = new GateSnapshotProbe(target).run();
                observer.getLogger().info("ASRPROBE gate-snapshot passed=true checks=" + checks);
            } catch (Throwable failure) {
                observer.getLogger().info("ASRPROBE gate-snapshot passed=false detail="
                        + failure.getClass().getSimpleName() + ":" + failure.getMessage());
            }
        });
    }

    private int run() throws Exception {
        String original = Files.readString(config);
        try {
            String a = document("A", "DIAMOND", true, false, 1, 60);
            paused(a, document("B", "DIAMOND", true, false, 0, 0), true, false);
            paused(a, document("B", "COAL", true, false, 1, 0), true, false);
            paused(a, document("B", "DIAMOND", false, false, 1, 0), true, false);
            // Vanilla advancement earned, but no personal credit: the captured policy must win.
            paused(document("A", "DIAMOND", true, false, 0, 0),
                    document("B", "DIAMOND", true, true, 0, 0), false, true);
            apply(a);
            rejected("config-version: 2\nitem-progression: [\n", "ConfigLoadException");
            rejected(a.replace("gated-items:\n", "gated-items:\n    collision:\n"
                    + "      match-patterns: [DIAMOND]\n      require-playtime-hours: 1\n"
                    + "      require-advancements: [minecraft:nether/find_fortress]\n"),
                    "GateCollisionException");
            Decision throttled = new Decision(null, null);
            listener.onAttemptPickup(throttled.event());
            listener.onAttemptPickup(throttled.event());
            check(throttled.messages.size() == 1, "duplicate refusal was not throttled");
            concurrent();
            return checks;
        } finally {
            Files.writeString(config, original);
            if (!target.reloadConfiguration()) throw new AssertionError("original config did not restore");
        }
    }

    private void paused(String before, String after, boolean oldDenied, boolean newDenied) throws Exception {
        apply(before);
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        Decision held = new Decision(captured, resume);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> pending = executor.submit(() -> listener.onAttemptPickup(held.event()));
        try {
            check(captured.await(10, TimeUnit.SECONDS), "pickup never reached its material getter");
            apply(after);
            resume.countDown();
            pending.get(10, TimeUnit.SECONDS);
            check(held.event.isCancelled() == oldDenied, "paused eligibility did not retain A");
            check(held.delay.get() == (oldDenied ? 40 : 0), "paused pickup delay did not retain A");
            check(oldDenied ? held.messages.equals(List.of("A:hint-A")) : held.messages.isEmpty(),
                    "paused feedback did not retain A: " + held.messages);
            Decision fresh = new Decision(null, null, held.id);
            listener.onAttemptPickup(fresh.event());
            check(fresh.event.isCancelled() == newDenied, "fresh eligibility did not use B");
            check(newDenied ? fresh.messages.equals(List.of("B:hint-B")) : fresh.messages.isEmpty(),
                    "fresh feedback did not use B: " + fresh.messages);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            check(executor.awaitTermination(10, TimeUnit.SECONDS), "paused reader did not terminate");
            target.playerState().forget(held.id);
        }
    }

    private void rejected(String document, String expected) throws Exception {
        var previous = target.itemGateSnapshot();
        List<LogRecord> diagnostics = new ArrayList<>();
        Filter previousFilter = target.getLogger().getFilter();
        // Capture deliberate rejection diagnostics locally so the normal clean-log check stays useful.
        target.getLogger().setFilter(record -> { diagnostics.add(record); return false; });
        try {
            Files.writeString(config, document);
            check(!target.reloadConfiguration(), "invalid reload was accepted");
        } finally {
            target.getLogger().setFilter(previousFilter);
        }
        check(diagnostics.stream().anyMatch(record -> record.getThrown() != null
                && record.getThrown().getClass().getSimpleName().equals(expected)),
                "expected rejection diagnostic missing: " + expected);
        check(target.itemGateSnapshot() == previous && target.configuration() == previous.config(),
                "rejected reload changed the active publication");
        Decision decision = new Decision(null, null);
        listener.onAttemptPickup(decision.event());
        check(decision.event.isCancelled() && decision.messages.equals(List.of("A:hint-A")),
                "rejected reload changed the actual pickup decision");
        target.playerState().forget(decision.id);
    }

    private void concurrent() throws Exception {
        apply(document("A", "DIAMOND", true, false, 1, 0));
        AtomicBoolean finished = new AtomicBoolean();
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<Integer>> readers = new ArrayList<>();
        try {
            for (int reader = 0; reader < 4; reader++) {
                readers.add(executor.submit(() -> {
                    Decision decision = new Decision(null, null);
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("writer did not start");
                    int count = 0;
                    try {
                        do {
                            decision.messages.clear();
                            decision.event = null;
                            decision.delay.set(0);
                            listener.onAttemptPickup(decision.event());
                            if (!decision.event.isCancelled() || decision.delay.get() != 40
                                    || decision.messages.size() != 1
                                    || !List.of("A:hint-A", "B:hint-B").contains(decision.messages.getFirst())) {
                                throw new AssertionError("mixed concurrent decision: " + decision.messages);
                            }
                            count++;
                            Thread.sleep(1);
                        } while ((!finished.get() || count < 50) && count < 3000);
                        if (!finished.get()) throw new AssertionError("writer exceeded reader bound");
                        return count;
                    } finally {
                        target.playerState().forget(decision.id);
                    }
                }));
            }
            check(ready.await(10, TimeUnit.SECONDS), "concurrent readers did not start");
            start.countDown();
            for (int write = 0; write < 20; write++) {
                apply(document(write % 2 == 0 ? "B" : "A", "DIAMOND", true, false, 1, 0));
            }
            finished.set(true);
            for (Future<Integer> reader : readers) check(reader.get(15, TimeUnit.SECONDS) >= 50,
                    "reader did not exercise enough actual decisions");
        } finally {
            finished.set(true);
            start.countDown();
            executor.shutdownNow();
            check(executor.awaitTermination(10, TimeUnit.SECONDS), "concurrent readers did not terminate");
        }
    }

    private void apply(String document) throws Exception {
        Files.writeString(config, document);
        check(target.reloadConfiguration(), "valid reload rejected");
    }

    private void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
        checks++;
    }

    private static String document(String marker, String material, boolean enabled, boolean credit,
                                   int hours, int cooldown) {
        return "config-version: 2\nprofile: CUSTOM\nitem-progression:\n  enabled: " + enabled
                + "\n  drop-recall-enabled: false\n  require-personal-credit: " + credit
                + "\n  personal-credit-scope: ALL\n  feedback-cooldown-seconds: " + cooldown
                + "\n  rejection-message: '" + marker + ":{REQUIREMENT}'\n  gated-items:\n    "
                + marker + ":\n      match-patterns: [" + material + "]\n      require-advancements:"
                + " [minecraft:story/mine_stone]\n      require-playtime-hours: " + hours
                + "\n      hint: hint-" + marker + "\n";
    }

    private static String text(Component component) {
        StringBuilder text = new StringBuilder(component instanceof TextComponent literal ? literal.content() : "");
        component.children().forEach(child -> text.append(text(child)));
        return text.toString();
    }

    private static final class Decision {
        private final UUID id;
        private final AtomicInteger delay = new AtomicInteger();
        private final List<String> messages = new ArrayList<>();
        private final Player player;
        private final Item item;
        private PlayerAttemptPickupItemEvent event;

        Decision(CountDownLatch captured, CountDownLatch resume) { this(captured, resume, UUID.randomUUID()); }

        Decision(CountDownLatch captured, CountDownLatch resume, UUID id) {
            this.id = id;
            PersistentDataContainer data = proxy(PersistentDataContainer.class, (p, method, args) ->
                    method.getName().equals("has") ? false : null);
            AdvancementProgress advancement = proxy(AdvancementProgress.class, (p, method, args) ->
                    method.getName().equals("isDone") ? true : null);
            player = proxy(Player.class, (p, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "SnapshotProbe";
                case "isOnline" -> true;
                case "hasPermission", "hasMetadata" -> false;
                case "getStatistic" -> 0;
                case "getFirstPlayed" -> 1000L;
                case "getPersistentDataContainer" -> data;
                case "getAdvancementProgress" -> advancement;
                case "sendActionBar" -> { messages.add(text((Component) args[0])); yield null; }
                case "sendMessage" -> null;
                default -> throw new AssertionError("unexpected player read " + method.getName());
            });
            item = proxy(Item.class, (p, method, args) -> switch (method.getName()) {
                case "getItemStack" -> {
                    if (captured != null) {
                        captured.countDown();
                        if (!resume.await(10, TimeUnit.SECONDS)) throw new AssertionError("reader never resumed");
                    }
                    yield new ItemStack(Material.DIAMOND);
                }
                case "setPickupDelay" -> { delay.set((Integer) args[0]); yield null; }
                default -> throw new AssertionError("unexpected item read " + method.getName());
            });
        }

        PlayerAttemptPickupItemEvent event() {
            if (event == null) event = new PlayerAttemptPickupItemEvent(player, item, 0);
            return event;
        }

        private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
        }
    }
}
