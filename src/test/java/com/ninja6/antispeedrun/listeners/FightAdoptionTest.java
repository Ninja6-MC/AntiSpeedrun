package com.ninja6.antispeedrun.listeners;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FightAdoptionTest {

    private final UUID primary = UUID.randomUUID();
    private final UUID secondary = UUID.randomUUID();

    @Test
    @DisplayName("a fight tracking its untagged primary, or nothing readable, has adopted nothing")
    void primaryOrNothingIsNoAdoption() {
        FightAdoption adoption = new FightAdoption();
        assertFalse(adoption.observe(primary, false).adopted());
        assertFalse(adoption.observe(null, false).adopted());
        assertFalse(adoption.observe(primary, false).adopted());
        assertFalse(adoption.takeDemoted(primary));
    }

    @Test
    @DisplayName("a tagged secondary as the fight's dragon is an adoption that demotes the last primary seen")
    void secondaryTrackedDemotesThePrimary() {
        FightAdoption adoption = new FightAdoption();
        adoption.observe(primary, false);
        // The primary unloads: nothing readable for a while.
        adoption.observe(null, false);

        FightAdoption.Outcome outcome = adoption.observe(secondary, true);

        assertTrue(outcome.adopted());
        assertEquals(primary, outcome.demoted());
        assertTrue(adoption.takeDemoted(primary), "the replaced primary is tagged when it loads");
        assertFalse(adoption.takeDemoted(primary), "and only once");
        assertFalse(adoption.takeDemoted(secondary));
    }

    @Test
    @DisplayName("the promoted dragon is the primary from then on")
    void promotedDragonIsThePrimary() {
        FightAdoption adoption = new FightAdoption();
        adoption.observe(primary, false);
        adoption.observe(secondary, true);

        // Its tag is gone now, so the next look sees an untagged dragon.
        assertFalse(adoption.observe(secondary, false).adopted());
        UUID another = UUID.randomUUID();
        FightAdoption.Outcome second = adoption.observe(another, true);
        assertTrue(second.adopted());
        assertEquals(secondary, second.demoted());
    }

    @Test
    @DisplayName("an adoption before any primary was seen demotes nothing")
    void adoptionWithoutAKnownPrimary() {
        FightAdoption adoption = new FightAdoption();
        FightAdoption.Outcome outcome = adoption.observe(secondary, true);
        assertTrue(outcome.adopted());
        assertNull(outcome.demoted());
        assertFalse(adoption.takeDemoted(secondary));
    }

    @Test
    @DisplayName("the same dragon seen again with its tag still on is not its own replacement")
    void sameDragonIsNotDemoted() {
        FightAdoption adoption = new FightAdoption();
        adoption.observe(secondary, true);
        FightAdoption.Outcome again = adoption.observe(secondary, true);
        assertTrue(again.adopted());
        assertNull(again.demoted());
    }
}
