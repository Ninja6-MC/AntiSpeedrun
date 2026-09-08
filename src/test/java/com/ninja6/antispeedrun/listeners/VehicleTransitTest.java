package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    /**
     * The outcome, not the plan.
     *
     * <p>The plan tests above were all green while a blocked rider in a mixed crew was in fact
     * carried into the Nether: the triage was right and the deferred work asked "where is this
     * rider?" after the transit had already answered "the Nether". A test of the plan cannot see
     * that. These run the whole sequence — triage, capture, transit, ejection — against a rider
     * that records the dimension it is in, and assert where each one finishes.
     */
    @Nested
    @DisplayName("the outcome of a portal transit")
    class Outcome {

        private static final String OVERWORLD = "OVERWORLD";
        private static final String NETHER = "NETHER";

        /** A rider that knows which dimension it is in, and can be moved between them. */
        private static final class Rider {
            private final String name;
            private final boolean qualified;
            private String dimension = OVERWORLD;

            Rider(String name, boolean qualified) {
                this.name = name;
                this.qualified = qualified;
            }
        }

        /**
         * Runs a transit the way {@code ProgressionGateListener} does, in the same order.
         *
         * <p>The order is the entire subject of the test, so it is spelled out rather than helped:
         * triage, then capture each ejected rider's return point <em>while they are still in the
         * source dimension</em>, then let the transit happen, then run the deferred ejections.
         *
         * <p><strong>This is a mirror, and the listener owns the original.</strong> A {@code
         * Player} cannot be constructed off a server, so this cannot call {@code
         * ProgressionGateListener#ejectAndReposition}; it re-enacts that method's sequence instead.
         * The consequence is worth being blunt about: restoring the old, defective ordering
         * <em>here</em> makes these tests fail, but restoring it in the listener would not, because
         * the listener is not what runs. So the two are kept in step by hand — a change to
         * {@code ejectAndReposition}'s sequence must be made here in the same commit, and that
         * method's javadoc says so from the other side. {@link #returnPointIsCapturedEagerly} is
         * the assertion that does bear on production code directly, pinning
         * {@link VehicleTransit#orders} rather than this re-enactment.
         */
        private void runTransit(List<Rider> riders) {
            VehicleTransit.Plan<Rider> plan = VehicleTransit.plan(riders, rider -> !rider.qualified);

            List<VehicleTransit.Ejection<Rider, String>> orders =
                    VehicleTransit.orders(plan, rider -> rider.dimension);

            if (!plan.cancelTransit()) {
                for (Rider rider : riders) {
                    rider.dimension = NETHER;
                }
            }
            // Next tick, on each rider's own region.
            for (VehicleTransit.Ejection<Rider, String> order : orders) {
                order.rider().dimension = order.returnTo();
            }
        }

        /**
         * B1. The boat goes, the qualified rider goes with it, and the blocked rider does not —
         * which is the whole of what #35 asks for and what the plan-level tests could not see.
         */
        @Test
        @DisplayName("a blocked rider in a mixed crew does not end up in the gated dimension")
        void mixedCrewLeavesTheBlockedRiderBehind() {
            Rider veteran = new Rider("veteran", true);
            Rider newbie = new Rider("newbie", false);

            runTransit(List.of(veteran, newbie));

            assertEquals(NETHER, veteran.dimension, "the qualified rider is not stranded");
            assertEquals(OVERWORLD, newbie.dimension,
                    "the blocked rider must not be carried through the gate");
        }

        @Test
        @DisplayName("a lone blocked rider stays put, and so does the boat")
        void loneRiderStaysPut() {
            Rider newbie = new Rider("newbie", false);
            runTransit(List.of(newbie));
            assertEquals(OVERWORLD, newbie.dimension);
        }

        @Test
        @DisplayName("an all-blocked crew all stay behind")
        void everyoneBlocked() {
            Rider one = new Rider("one", false);
            Rider two = new Rider("two", false);
            runTransit(List.of(one, two));
            assertEquals(OVERWORLD, one.dimension);
            assertEquals(OVERWORLD, two.dimension);
        }

        @Test
        @DisplayName("a fully qualified crew travels together and nobody is sent back")
        void everyoneQualified() {
            Rider one = new Rider("one", true);
            Rider two = new Rider("two", true);
            runTransit(List.of(one, two));
            assertEquals(NETHER, one.dimension);
            assertEquals(NETHER, two.dimension);
        }

        /**
         * The mechanism, asserted directly as well as through the outcome above: the return point
         * is read before the transit, not when the ejection runs. Deferring this one call is
         * precisely the defect, so it gets its own test rather than only being implied.
         */
        @Test
        @DisplayName("the return point is captured eagerly, before the transit can change it")
        void returnPointIsCapturedEagerly() {
            Rider newbie = new Rider("newbie", false);
            VehicleTransit.Plan<Rider> plan =
                    VehicleTransit.plan(List.of(new Rider("veteran", true), newbie),
                            rider -> !rider.qualified);

            List<VehicleTransit.Ejection<Rider, String>> orders =
                    VehicleTransit.orders(plan, rider -> rider.dimension);

            // The transit happens only now. A lazily captured return point would follow it.
            newbie.dimension = NETHER;

            assertEquals(1, orders.size(), "every blocked rider gets exactly one order");
            assertEquals(OVERWORLD, orders.get(0).returnTo(),
                    "the captured position predates the transit");
        }

        @Test
        @DisplayName("no blocked rider's ejection is dropped")
        void everyBlockedRiderGetsAnOrder() {
            List<Rider> riders = List.of(new Rider("a", false), new Rider("b", true),
                    new Rider("c", false));
            VehicleTransit.Plan<Rider> plan = VehicleTransit.plan(riders, rider -> !rider.qualified);
            List<VehicleTransit.Ejection<Rider, String>> orders =
                    VehicleTransit.orders(plan, rider -> rider.dimension);

            assertEquals(plan.ejected().size(), orders.size());
            assertEquals(List.of("a", "c"), orders.stream().map(o -> o.rider().name).toList());
        }

        @Test
        @DisplayName("a plan with nothing to eject produces no orders")
        void noOrdersForANoOpPlan() {
            VehicleTransit.Plan<Rider> plan =
                    VehicleTransit.plan(List.of(new Rider("a", true)), rider -> !rider.qualified);
            assertTrue(VehicleTransit.orders(plan, rider -> rider.dimension).isEmpty());
        }
    }
}
