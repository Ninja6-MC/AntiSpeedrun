package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

class PotionGateRulesTest {
    @Test void convertedGiftAndDurationOrAmplifierChangesEarnNothing() {
        assertEquals(Set.of(), PotionGateRules.introduced(Set.of("strength"), Set.of("strength")));
        assertEquals(Set.of("strength"), PotionGateRules.introduced(Set.of(), Set.of("strength")));
        assertEquals(Set.of("invisibility"), PotionGateRules.introduced(Set.of("night_vision"), Set.of("invisibility")));
    }
    @Test void mixedPotionOnlyLosesItsLockedBenefit() {
        Set<String> configured = Set.of("strength", "instant_health", "poison", "slowness");
        assertTrue(PotionGateRules.locked("strength", configured, id -> false));
        assertFalse(PotionGateRules.locked("instant_health", configured, id -> false));
        assertFalse(PotionGateRules.locked("poison", configured, id -> false));
        assertFalse(PotionGateRules.locked("slowness", configured, id -> false));
        assertFalse(PotionGateRules.locked("strength", configured, id -> true));
        assertFalse(PotionGateRules.locked("speed", configured, id -> false));
    }
    @Test void loaderClaimsAreBoundedAndConsumedPerBrew() {
        UUID player = UUID.randomUUID();
        var claim = BrewingLoaderRules.load(Optional.empty(), player, 2);
        assertEquals(player, BrewingLoaderRules.brew(claim).credited().orElseThrow());
        assertEquals(1, BrewingLoaderRules.brew(claim).after().orElseThrow().remaining());
        assertTrue(BrewingLoaderRules.brew(BrewingLoaderRules.brew(claim).after()).after().isEmpty());
        assertTrue(BrewingLoaderRules.brew(Optional.empty()).credited().isEmpty());
        assertEquals(1, BrewingLoaderRules.inserted(InventoryAction.PLACE_ONE, Material.SUGAR, 16,
                Material.SUGAR, 63, 64));
        assertEquals(0, BrewingLoaderRules.inserted(InventoryAction.PICKUP_ALL, Material.SUGAR, 16,
                Material.SUGAR, 16, 64));
        assertTrue(BrewingLoaderRules.Stamp.decode(new long[] {1, 2, 0}).isEmpty());
        assertEquals(claim, BrewingLoaderRules.Stamp.decode(claim.orElseThrow().encode()));
    }
    @Test void anotherLoaderReplacesClaim() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var claim = BrewingLoaderRules.load(BrewingLoaderRules.load(Optional.empty(), first, 20), second, 1);
        assertEquals(second, claim.orElseThrow().loader());
        assertEquals(1, claim.orElseThrow().remaining());
    }
}
