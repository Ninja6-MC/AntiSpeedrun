package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.NamespacedKey;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.progression.TrimProgressionManager;
import com.ninja6.antispeedrun.storage.ExploredStructureStore;
import com.ninja6.antispeedrun.storage.StateFile;

/** When a player's position or loot proves an Ancient City, and what is recorded (#221). */
class AncientCityRulesTest {

    /** A piece the size of an Ancient City room, at the depth one generates at. */
    private static final BoundingBox PIECE = new BoundingBox(100, -52, 200, 140, -22, 217);

    private static final String CITY = TrimProgressionManager.ANCIENT_CITY.id();

    @Nested
    @DisplayName("throttle, dimension and depth")
    class WorthLooking {

        @Test
        @DisplayName("each player is looked at every 40 ticks, about every 2 seconds")
        void period() {
            assertEquals(40L, AncientCityRules.PERIOD_TICKS);
        }

        @Test
        @DisplayName("an unrecorded survival player in the overworld below y 0 is looked at")
        void deepOverworld() {
            assertTrue(AncientCityRules.worthLooking(false, true, -1, false));
            assertTrue(AncientCityRules.worthLooking(false, true, -52, false));
            assertTrue(AncientCityRules.worthLooking(false, true, -64, false));
        }

        @Test
        @DisplayName("y 0 and above is never Deep Dark depth")
        void shallow() {
            assertFalse(AncientCityRules.worthLooking(false, true, 0, false));
            assertFalse(AncientCityRules.worthLooking(false, true, 64, false));
            assertFalse(AncientCityRules.worthLooking(false, true, 100, false));
        }

        @Test
        @DisplayName("the Nether and the End are never looked at, at any depth")
        void otherDimensions() {
            assertFalse(AncientCityRules.worthLooking(false, false, -30, false));
            assertFalse(AncientCityRules.worthLooking(false, false, 30, false));
        }

        @Test
        @DisplayName("a player already recorded, or a spectator, costs no lookup")
        void recordedOrSpectator() {
            assertFalse(AncientCityRules.worthLooking(true, true, -30, false));
            assertFalse(AncientCityRules.worthLooking(false, true, -30, true));
        }
    }

    @Nested
    @DisplayName("inside a piece")
    class Inside {

        @Test
        @DisplayName("both corners of a piece count, as vanilla's location check counts them")
        void cornersIncluded() {
            assertTrue(AncientCityRules.inside(List.of(PIECE), 100, -52, 200));
            assertTrue(AncientCityRules.inside(List.of(PIECE), 140, -22, 217));
            assertTrue(AncientCityRules.inside(List.of(PIECE), 120, -37, 208));
        }

        @Test
        @DisplayName("one block outside on any axis does not")
        void outside() {
            assertFalse(AncientCityRules.inside(List.of(PIECE), 99, -37, 208));
            assertFalse(AncientCityRules.inside(List.of(PIECE), 141, -37, 208));
            assertFalse(AncientCityRules.inside(List.of(PIECE), 120, -53, 208));
            assertFalse(AncientCityRules.inside(List.of(PIECE), 120, -21, 208));
            assertFalse(AncientCityRules.inside(List.of(PIECE), 120, -37, 199));
            assertFalse(AncientCityRules.inside(List.of(PIECE), 120, -37, 218));
        }

        @Test
        @DisplayName("any one of several pieces is enough; none at all is never inside")
        void severalPieces() {
            BoundingBox other = new BoundingBox(0, -40, 0, 10, -30, 10);
            assertTrue(AncientCityRules.inside(List.of(other, PIECE), 5, -35, 5));
            assertFalse(AncientCityRules.inside(List.of(), 5, -35, 5));
        }
    }

    @Nested
    @DisplayName("loot")
    class Loot {

        @Test
        @DisplayName("both Ancient City chest tables count, and nothing else")
        void tables() {
            assertTrue(AncientCityRules.cityLoot(NamespacedKey.minecraft("chests/ancient_city")));
            assertTrue(AncientCityRules.cityLoot(NamespacedKey.minecraft("chests/ancient_city_ice_box")));
            assertFalse(AncientCityRules.cityLoot(NamespacedKey.minecraft("chests/bastion_treasure")));
            assertFalse(AncientCityRules.cityLoot(new NamespacedKey("custom", "chests/ancient_city")));
            assertFalse(AncientCityRules.cityLoot(null));
        }
    }

    @Nested
    @DisplayName("the record")
    class Record {

        private final Map<String, Object> saved = new HashMap<>();
        private final ExploredStructureStore store = new ExploredStructureStore(
                Logger.getLogger("test"), new StateFile() {
                    @Override
                    public Map<String, Object> load() {
                        return Map.copyOf(saved);
                    }

                    @Override
                    public void save(Map<String, Object> document) {
                        saved.clear();
                        saved.putAll(document);
                    }

                    @Override
                    public Optional<String> quarantine() {
                        return Optional.empty();
                    }
                }, Runnable::run);
        private final AncientCityTracker tracker = new AncientCityTracker(store);
        private final List<String> lookups = new ArrayList<>();
        private final UUID player = UUID.randomUUID();

        private AncientCityTracker.PieceLookup city() {
            return (x, z) -> {
                lookups.add(x + "," + z);
                return List.of(PIECE);
            };
        }

        @Test
        @DisplayName("standing in a piece records the Ancient City and writes it to the file once")
        void entering() {
            assertTrue(tracker.observe(player, true, false, 120, -37, 208, city()));
            assertTrue(store.hasExplored(player, CITY));
            assertEquals(List.of(CITY), saved.get(ExploredStructureStore.KEY_PREFIX + player));
            assertFalse(tracker.observe(player, true, false, 121, -37, 208, city()), "already recorded");
            assertEquals(1, lookups.size(), "a recorded player costs no further lookup");
        }

        @Test
        @DisplayName("Deep Dark depth beside a city, but outside its pieces, records nothing")
        void besideTheCity() {
            assertFalse(tracker.observe(player, true, false, 120, -60, 208, city()));
            assertFalse(store.hasExplored(player, CITY));
            assertEquals(1, lookups.size());
            assertTrue(saved.isEmpty());
        }

        @Test
        @DisplayName("above the depth or outside the overworld, the structure lookup is never asked")
        void noLookup() {
            assertFalse(tracker.observe(player, true, false, 120, 64, 208, city()));
            assertFalse(tracker.observe(player, false, false, 120, -37, 208, city()));
            assertFalse(tracker.observe(player, true, true, 120, -37, 208, city()));
            assertTrue(lookups.isEmpty());
            assertFalse(store.hasExplored(player, CITY));
        }

        @Test
        @DisplayName("Ancient City loot is kept under its own id; other loot and NPCs record nothing")
        void loot() {
            assertFalse(tracker.looted(player, NamespacedKey.minecraft("chests/simple_dungeon"), true));
            assertFalse(tracker.looted(player, NamespacedKey.minecraft("chests/ancient_city"), false));
            assertFalse(store.hasExplored(player, TrimProgressionManager.ANCIENT_CITY_LOOT_ID));
            assertTrue(tracker.looted(player, NamespacedKey.minecraft("chests/ancient_city"), true));
            assertTrue(store.hasExplored(player, TrimProgressionManager.ANCIENT_CITY_LOOT_ID));
            assertFalse(store.hasExplored(player, CITY), "loot is not the same record as entering");
            assertEquals(List.of(TrimProgressionManager.ANCIENT_CITY_LOOT_ID),
                    saved.get(ExploredStructureStore.KEY_PREFIX + player));
        }
    }
}
