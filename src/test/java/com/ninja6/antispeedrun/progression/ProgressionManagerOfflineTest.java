package com.ninja6.antispeedrun.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;

/**
 * A player who has already left is answered without any read of the {@link Player}: no region owns
 * a removed player under Folia, so {@code getStatistic}, {@code getFirstPlayed} and
 * {@code getAdvancementProgress} have no thread on which they are legal (#85, finding 3).
 */
class ProgressionManagerOfflineTest {

    private static final long NOW = 1_000_000_000_000L;

    /** An offline player on which every call but identity and {@code isOnline} fails the test. */
    private static Player offlinePlayer(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isOnline" -> false;
                    case "getUniqueId" -> id;
                    case "getName" -> "Departed";
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "offlinePlayer(" + id + ")";
                    default -> throw new AssertionError("offline player was read: " + method.getName());
                });
    }

    private static ProgressionManager manager() {
        AdvancementLookup lookup = (player, key) -> {
            throw new AssertionError("advancement lookup on an offline player: " + key);
        };
        return new ProgressionManager(Logger.getLogger("test"), lookup, new PlayerStateRegistry(),
                Duration.ofMinutes(1), () -> NOW);
    }

    @Test
    @DisplayName("an offline player is evaluated without reading the player, and fails closed")
    void offlineEvaluationReadsNothing() {
        ProgressionManager manager = manager();
        Player player = offlinePlayer(UUID.randomUUID());
        PluginConfig config = PluginConfig.defaults();

        EligibilityResult advancement = manager.evaluate(player, config,
                new MilestoneRequirement(List.of("minecraft:story/enter_the_nether"), 0.0D, 0));
        assertFalse(advancement.eligible());
        assertEquals(List.of("minecraft:story/enter_the_nether"), advancement.missingAdvancements());
        assertTrue(advancement.unresolvableAdvancements().isEmpty());

        assertFalse(manager.evaluate(player, config, new MilestoneRequirement(List.of(), 2.0D, 0))
                .eligible());

        EligibilityResult age = manager.evaluate(player, config,
                new MilestoneRequirement(List.of(), 0.0D, 3));
        assertFalse(age.eligible());
        assertFalse(age.accountAgeUnknown());
    }

    @Test
    @DisplayName("an offline answer is not cached")
    void offlineAnswerIsNotCached() {
        ProgressionManager manager = manager();
        UUID id = UUID.randomUUID();

        manager.snapshot(offlinePlayer(id), PluginConfig.defaults());

        assertTrue(manager.cached(id).isEmpty());
    }
}
