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
    private final SecondaryRoster roster = new SecondaryRoster();

    @Test
    @DisplayName("a fight tracking its untagged primary, or nothing readable, has adopted nothing")
    void primaryOrNothingIsNoAdoption() {
        FightAdoption adoption = new FightAdoption();
        assertFalse(adoption.observe(primary, false, roster).adopted());
        assertFalse(adoption.observe(null, false, roster).adopted());
        assertFalse(adoption.observe(primary, false, roster).adopted());
        assertFalse(adoption.takeDemoted(primary));
    }

    @Test
    @DisplayName("a tagged secondary as the fight's dragon is an adoption that demotes the last primary seen")
    void secondaryTrackedDemotesThePrimary() {
        FightAdoption adoption = new FightAdoption();
        adoption.observe(primary, false, roster);
        // The primary unloads: nothing readable for a while.
        adoption.observe(null, false, roster);

        FightAdoption.Outcome outcome = adoption.observe(secondary, true, roster);

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
        adoption.observe(primary, false, roster);
        adoption.observe(secondary, true, roster);

        // Its tag is gone now, so the next look sees an untagged dragon.
        assertFalse(adoption.observe(secondary, false, roster).adopted());
        UUID another = UUID.randomUUID();
        FightAdoption.Outcome second = adoption.observe(another, true, roster);
        assertTrue(second.adopted());
        assertEquals(secondary, second.demoted());
    }

    @Test
    @DisplayName("an adoption before any primary was seen demotes nothing")
    void adoptionWithoutAKnownPrimary() {
        FightAdoption adoption = new FightAdoption();
        FightAdoption.Outcome outcome = adoption.observe(secondary, true, roster);
        assertTrue(outcome.adopted());
        assertNull(outcome.demoted());
        assertFalse(adoption.takeDemoted(secondary));
    }

    @Test
    @DisplayName("the same dragon seen again with its tag still on is not its own replacement")
    void sameDragonIsNotDemoted() {
        FightAdoption adoption = new FightAdoption();
        adoption.observe(secondary, true, roster);
        FightAdoption.Outcome again = adoption.observe(secondary, true, roster);
        assertTrue(again.adopted());
        assertNull(again.demoted());
    }

    @Test
    @DisplayName("the replaced primary counts while still unloaded, so the adopted dragon is held")
    void replacedPrimaryHoldsTheAdoptedDragonWhileUnloaded() {
        FightAdoption adoption = new FightAdoption();
        // B is the only secondary left; the primary then unloads and the fight adopts B.
        roster.track(secondary);
        adoption.observe(primary, false, roster);
        adoption.observe(null, false, roster);
        adoption.observe(secondary, true, roster);

        assertEquals(1, roster.living(), "the unloaded old primary is the one living secondary");
        assertTrue(DragonReconciliationRules.refusesDeath(false, roster.living()),
                "B, the primary now, cannot die before the old primary does");

        // Its chunk loads: tagged and tracked again by the load handler, counted once.
        assertTrue(adoption.takeDemoted(primary));
        roster.track(primary);
        assertEquals(1, roster.living());
        assertFalse(adoption.release(primary, roster), "a tagged secondary leaves through the roster");

        roster.forget(primary);
        assertFalse(DragonReconciliationRules.refusesDeath(false, roster.living()));
    }

    @Test
    @DisplayName("a replaced primary removed before its chunk loads stops holding the adopted dragon")
    void replacedPrimaryRemovedUnloadedIsReleased() {
        FightAdoption adoption = new FightAdoption();
        adoption.observe(primary, false, roster);
        roster.track(secondary);
        adoption.observe(secondary, true, roster);

        assertTrue(adoption.release(primary, roster));
        assertEquals(0, roster.living());
        assertFalse(adoption.release(primary, roster));
        assertFalse(adoption.release(secondary, roster), "only replaced primaries are released this way");
    }
}
