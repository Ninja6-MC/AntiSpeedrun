package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #35's decision: who comes off the boat, and whether the boat still goes.
 *
 * <p>Riders are plain strings here. That is the point of {@link VehicleTransit} being generic — a
 * {@code Player} cannot be constructed off-server, and the rule has nothing to do with being one.
 */
class VehicleTransitTest {

    @Test
    @DisplayName("a crew that all qualify travels untouched")
    void everyoneQualifies() {
        VehicleTransit.Plan<String> plan =
                VehicleTransit.plan(List.of("ada", "grace"), rider -> false);
        assertTrue(plan.isNoOp());
        assertEquals(List.of(), plan.ejected());
        assertFalse(plan.cancelTransit());
    }

    /**
     * The criterion #35 exists for. One unqualified rider must not strand a qualified one, so the
     * transit is <em>not</em> cancelled and only the unqualified rider comes off.
     */
    @Test
    @DisplayName("a mixed crew: the unqualified rider is ejected and the boat still goes")
    void mixedCrewTravels() {
        Set<String> blocked = Set.of("newbie");
        VehicleTransit.Plan<String> plan =
                VehicleTransit.plan(List.of("veteran", "newbie"), blocked::contains);

        assertEquals(List.of("newbie"), plan.ejected());
        assertFalse(plan.cancelTransit(), "the qualified rider proceeds");
    }

    @Test
    @DisplayName("order is preserved, so the message order matches the ejection order")
    void ejectionOrderFollowsRiderOrder() {
        Set<String> blocked = Set.of("a", "c");
        VehicleTransit.Plan<String> plan =
                VehicleTransit.plan(List.of("a", "b", "c"), blocked::contains);
        assertEquals(List.of("a", "c"), plan.ejected());
        assertFalse(plan.cancelTransit());
    }

    /**
     * The ghost-vehicle case from audit finding R-09. With nobody qualified aboard, letting the
     * transit proceed would put an empty boat in a dimension nobody was allowed to enter — so the
     * event is cancelled. The riders are still ejected, because a rider left sitting in a cancelled
     * portal is a rider being offered the same transit next tick.
     */
    @Test
    @DisplayName("nobody qualifies: the transit is cancelled and every rider still comes off")
    void nobodyQualifies() {
        VehicleTransit.Plan<String> plan =
                VehicleTransit.plan(List.of("newbie", "friend"), rider -> true);

        assertTrue(plan.cancelTransit(), "no empty vehicle is sent through");
        assertEquals(List.of("newbie", "friend"), plan.ejected(),
                "and nobody is left in the portal to be pushed into it again");
        assertFalse(plan.isNoOp());
    }

    @Test
    @DisplayName("a lone unqualified rider is ejected and the transit is cancelled")
    void loneUnqualifiedRider() {
        VehicleTransit.Plan<String> plan = VehicleTransit.plan(List.of("newbie"), rider -> true);
        assertEquals(List.of("newbie"), plan.ejected());
        assertTrue(plan.cancelTransit());
    }

    @Test
    @DisplayName("an empty vehicle is not gated - nobody is being carried anywhere")
    void emptyVehicle() {
        VehicleTransit.Plan<String> plan = VehicleTransit.plan(List.of(), rider -> true);
        assertTrue(plan.isNoOp());
    }

    /**
     * The predicate sends the rejection message in production, so a rider evaluated twice is a
     * rider messaged twice — and one never evaluated is one never told.
     */
    @Test
    @DisplayName("each rider is tested exactly once, in order")
    void predicateRunsOncePerRider() {
        List<String> seen = new ArrayList<>();
        VehicleTransit.plan(List.of("a", "b", "c"), rider -> {
            seen.add(rider);
            return false;
        });
        assertEquals(List.of("a", "b", "c"), seen);
    }

    @Test
    @DisplayName("the ejected list is a copy the caller cannot mutate")
    void planIsImmutable() {
        VehicleTransit.Plan<String> plan = VehicleTransit.plan(List.of("newbie"), rider -> true);
        assertThrows(UnsupportedOperationException.class, () -> plan.ejected().add("gatecrasher"));
    }
}
