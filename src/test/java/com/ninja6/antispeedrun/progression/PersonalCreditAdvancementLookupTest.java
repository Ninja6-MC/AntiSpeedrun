package com.ninja6.antispeedrun.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PossessionAdvancements;
import com.ninja6.antispeedrun.progression.AdvancementLookup.State;
import com.ninja6.antispeedrun.storage.CreditSource;
import com.ninja6.antispeedrun.storage.PersonalCredit;
import com.ninja6.antispeedrun.storage.PersonalCreditStore;
import com.ninja6.antispeedrun.storage.StateFile;

/** The personal-credit decorator over {@link AdvancementLookup} (#215). */
class PersonalCreditAdvancementLookupTest {

    private static final String MINE_STONE = "minecraft:story/mine_stone";
    private static final String SMELT_IRON = "minecraft:story/smelt_iron";
    private static final String MINE_DIAMOND = "minecraft:story/mine_diamond";
    private static final String ENTER_NETHER = "minecraft:story/enter_the_nether";

    private static final class InMemoryStateFile implements StateFile {
        @Override
        public Map<String, Object> load() {
            return Map.of();
        }

        @Override
        public void save(Map<String, Object> document) {
        }

        @Override
        public Optional<String> quarantine() {
            return Optional.empty();
        }
    }

    /** What vanilla says, per key; a key with no entry is NOT_EARNED. */
    private final Map<String, State> vanilla = new HashMap<>();
    private final AtomicReference<PluginConfig> config = new AtomicReference<>();
    private PersonalCreditStore credits;
    private PersonalCreditAdvancementLookup lookup;
    private UUID id;
    private Player player;

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "player(" + id + ")";
                    default -> throw new AssertionError("unexpected player read: " + method.getName());
                });
    }

    private static PluginConfig settings(boolean requireCredit, boolean countLoot) {
        PluginConfig base = PluginConfig.defaults();
        PluginConfig.ItemProgression items = base.itemProgression();
        return new PluginConfig(base.profile(), base.dimensionGates(),
                new PluginConfig.ItemProgression(items.enabled(), items.dropRecallEnabled(),
                        items.gateDispensers(), items.gateNestedBundles(),
                        items.feedbackCooldownSeconds(), items.rejectionMessage(),
                        items.gatedItems(), requireCredit, countLoot),
                base.trimProgression(), base.idleReminder(), base.progressCard(),
                base.journeyBook(), base.bossScaling(), base.antiCheese(),
                base.villagerProgression(), base.warnings());
    }

    @BeforeEach
    void setUp() {
        credits = new PersonalCreditStore(Logger.getLogger("test"), new InMemoryStateFile(), Runnable::run);
        credits.loadNow();
        config.set(settings(true, true));
        lookup = new PersonalCreditAdvancementLookup(
                (p, key) -> vanilla.getOrDefault(key, State.NOT_EARNED), credits, config::get);
        id = UUID.randomUUID();
        player = player(id);
    }

    @Test
    @DisplayName("a protected key is earned with its credit, not with the vanilla advancement")
    void creditDecides() {
        vanilla.put(MINE_STONE, State.EARNED);
        assertEquals(State.NOT_EARNED, lookup.state(player, MINE_STONE), "granted, no credit");

        credits.record(id, PersonalCredit.MINE_STONE, CreditSource.ACTION);
        vanilla.put(MINE_STONE, State.NOT_EARNED);
        assertEquals(State.EARNED, lookup.state(player, MINE_STONE), "revoked, credit recorded");
    }

    @Test
    @DisplayName("smelt_iron needs both the mined and the smelted sub-credit, in either order")
    void smeltIronNeedsBoth() {
        credits.record(id, PersonalCredit.SMELTED_IRON, CreditSource.ACTION);
        assertEquals(State.NOT_EARNED, lookup.state(player, SMELT_IRON));
        credits.record(id, PersonalCredit.MINED_IRON, CreditSource.ACTION);
        assertEquals(State.EARNED, lookup.state(player, SMELT_IRON));
    }

    @Test
    @DisplayName("a credit belongs to the player who earned it")
    void perPlayer() {
        credits.record(UUID.randomUUID(), PersonalCredit.MINE_STONE, CreditSource.ACTION);
        assertEquals(State.NOT_EARNED, lookup.state(player, MINE_STONE));
    }

    @ParameterizedTest
    @EnumSource(PersonalCredit.class)
    @DisplayName("UNRESOLVABLE passes through, credit or not")
    void unresolvablePassesThrough(PersonalCredit credit) {
        vanilla.put(credit.advancement(), State.UNRESOLVABLE);
        for (PersonalCredit each : PersonalCredit.values()) {
            credits.record(id, each, CreditSource.ACTION);
        }
        assertEquals(State.UNRESOLVABLE, lookup.state(player, credit.advancement()));
    }

    @Test
    @DisplayName("every other key is the wrapped lookup's answer")
    void otherKeysPassThrough() {
        assertEquals(State.NOT_EARNED, lookup.state(player, ENTER_NETHER));
        vanilla.put(ENTER_NETHER, State.EARNED);
        assertEquals(State.EARNED, lookup.state(player, ENTER_NETHER));
        vanilla.put(ENTER_NETHER, State.UNRESOLVABLE);
        assertEquals(State.UNRESOLVABLE, lookup.state(player, ENTER_NETHER));
    }

    @ParameterizedTest
    @EnumSource(State.class)
    @DisplayName("with require-personal-credit off, every protected key is the vanilla answer")
    void toggleOffIsVanilla(State answer) {
        config.set(settings(false, true));
        for (PersonalCredit credit : PersonalCredit.values()) {
            credits.record(id, credit, CreditSource.ACTION);
        }
        for (String key : PersonalCreditAdvancementLookup.protectedKeys()) {
            vanilla.put(key, answer);
            assertEquals(answer, lookup.state(player, key), key);
        }
    }

    @Test
    @DisplayName("count-structure-loot relocks and unlocks a loot-only diamond without a restart")
    void lootToggleIsLive() {
        credits.record(id, PersonalCredit.MINE_DIAMOND, CreditSource.LOOT);
        assertEquals(State.EARNED, lookup.state(player, MINE_DIAMOND));

        config.set(settings(true, false));
        assertEquals(State.NOT_EARNED, lookup.state(player, MINE_DIAMOND));

        config.set(settings(true, true));
        assertEquals(State.EARNED, lookup.state(player, MINE_DIAMOND));
    }

    @Test
    @DisplayName("count-structure-loot off relocks a player whose only mined-iron credit came from loot")
    void lootToggleMinedIron() {
        credits.record(id, PersonalCredit.MINED_IRON, CreditSource.LOOT);
        credits.record(id, PersonalCredit.SMELTED_IRON, CreditSource.ACTION);
        assertEquals(State.EARNED, lookup.state(player, SMELT_IRON));

        config.set(settings(true, false));
        assertEquals(State.NOT_EARNED, lookup.state(player, SMELT_IRON));

        credits.record(id, PersonalCredit.MINED_IRON, CreditSource.ACTION);
        assertEquals(State.EARNED, lookup.state(player, SMELT_IRON), "mined it after all");
    }

    @Test
    @DisplayName("loot never stands in for a credit that has no loot path")
    void lootIgnoredForUnlootableCredits() {
        credits.record(id, PersonalCredit.MINE_STONE, CreditSource.LOOT);
        assertEquals(State.NOT_EARNED, lookup.state(player, MINE_STONE));
    }

    @Test
    @DisplayName("the protected keys are exactly the credited keys the config warning knows")
    void protectedKeysMatchConfig() {
        assertEquals(PossessionAdvancements.CREDITED, PersonalCreditAdvancementLookup.protectedKeys());
    }
}
