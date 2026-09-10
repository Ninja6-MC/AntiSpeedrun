package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.storage.DimensionUnlock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
     * that. These run the whole sequence — triage, capture, transit, the arrival backstop, then the
     * deferred ejection — against a rider that records the dimension it is in, and assert where each
     * one finishes.
     *
     * <p>#100 added the backstop to that sequence, and it belongs here rather than only in
     * {@code DimensionGateRulesTest}: what is worth asserting is not what the verdict is but that
     * the two mechanisms do not fight. A blocked rider in a mixed crew really is in the Nether when
     * the world change fires, so a backstop that did not know the vehicle path already had them
     * would move them a second time, somewhere else.
     */
    @Nested
    @DisplayName("the outcome of a portal transit")
    class Outcome {

        /**
         * Somewhere a rider can be: a name for the assertion message, the world's identity, and
         * which gate an arrival there has to clear ({@code null} for an ungated one).
         *
         * <p>The world is a component in its own right because that is the whole of finding 1: a
         * gate is a <em>kind</em> of dimension and several worlds share one, so a note that names
         * only the gate names no destination at all.
         */
        private record Where(String name, UUID world, DimensionUnlock gate) {

            static Where gated(String name, DimensionUnlock gate) {
                return new Where(name, UUID.randomUUID(), gate);
            }
        }

        private static final Where OVERWORLD = new Where("OVERWORLD", UUID.randomUUID(), null);
        private static final Where NETHER = Where.gated("NETHER", DimensionUnlock.NETHER);

        /** A multiverse server's second Nether. Same gate, different destination. */
        private static final Where OTHER_NETHER =
                Where.gated("OTHER_NETHER", DimensionUnlock.NETHER);

        private static final Where THE_END = Where.gated("THE_END", DimensionUnlock.THE_END);

        /**
         * Where {@code ProgressionGateListener}'s backstop puts a player it has to send back: the
         * spawn of the world they came from. Distinct from {@link #OVERWORLD} on purpose — a rider
         * the vehicle path already handled must finish where <em>it</em> put them, so a test can
         * tell "not double-handled" from "handled twice and happened to end up nearby".
         */
        private static final Where OVERWORLD_SPAWN =
                new Where("OVERWORLD_SPAWN", OVERWORLD.world(), null);

        /** The instant every step below happens at, unless a test moves it on. */
        private static final long NOW = 1_000_000L;

        /**
         * The listener's decision ledger — {@code ProgressionGateListener#decisions}, one map per
         * player, and the rules over it are the production ones in {@link DimensionGateRules}.
         *
         * <p>An earlier revision of this harness held a bare {@code Set<Rider>}, with no per-gate
         * key, no destination and no timestamp. That could not express either way a note escapes the
         * transit that wrote it, so both were invisible here while being live in the listener. The
         * ledger is now the real shape, and the writing and spending of a note go through
         * {@link DimensionGateRules#note} and {@link DimensionGateRules#consume} rather than through
         * a stand-in.
         *
         * <p>An instance field rather than a local, because its lifetime is the point: a note
         * written by one transit and not consumed is still there when the next one arrives, which is
         * exactly what {@link #cancelledTransitRecordsNoExemption} is about. JUnit builds a fresh
         * {@code Outcome} per test, so nothing leaks between them.
         */
        private final Map<Rider, Map<DimensionUnlock, DimensionGateRules.Decision>> ledger =
                new HashMap<>();

        /** The clock every step reads. A test that cares about staleness moves it on by hand. */
        private long clock = NOW;

        /** A rider that knows where it is, and can be moved. */
        private static final class Rider {
            private final String name;
            private final boolean qualified;
            private Where where = OVERWORLD;

            /**
             * Whether the backstop acted on this rider. Recorded separately from
             * {@link #where} because the deferred ejection runs <em>after</em> the world change
             * and would overwrite the position: without this, a rider moved twice and a rider moved
             * once finish in the same place, and the double-handling #100 forbids would be
             * invisible.
             */
            private boolean backstopped;

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
            runTransit(riders, NETHER);
        }

        private void runTransit(List<Rider> riders, Where destination) {
            VehicleTransit.Plan<Rider> plan = VehicleTransit.plan(riders, rider -> !rider.qualified);

            List<VehicleTransit.Ejection<Rider, Where>> orders =
                    VehicleTransit.orders(plan, rider -> rider.where);

            if (!plan.cancelTransit()) {
                // Every ejected rider is about to be carried into the gated dimension for a tick
                // before the deferred ejection puts them back, so the listener notes an exemption
                // for each -- only when the vehicle actually moves, and against the world the
                // portal actually resolved to. #100.
                for (VehicleTransit.Ejection<Rider, Where> order : orders) {
                    note(order.rider(), destination);
                }
                for (Rider rider : riders) {
                    rider.where = destination;
                    arrive(rider);
                }
            }
            // Next tick, on each rider's own region.
            for (VehicleTransit.Ejection<Rider, Where> order : orders) {
                order.rider().where = order.returnTo();
            }
        }

        /**
         * {@code ProgressionGateListener#noteDecision}, over the production ledger rules.
         *
         * <p>The gate comes from the destination rather than being passed separately, because that
         * is what the listener does: it asks {@code gatedDestination} which gate the arrival has to
         * clear and notes <em>that</em> gate against <em>that</em> world.
         */
        private void note(Rider rider, Where destination) {
            DimensionGateRules.note(ledger.computeIfAbsent(rider, r -> new HashMap<>()),
                    destination.gate(), destination.world(), clock);
        }

        /**
         * {@code ProgressionGateListener#onPlayerTeleportSettled}, re-enacted — a cross-dimension
         * teleport whose cause this plugin does not regulate.
         *
         * <p>The handler runs at {@code MONITOR} with {@code ignoreCancelled}, so it is reached only
         * when the teleport is going ahead, and {@code getTo()} is by then the destination after any
         * redirect. Both of those are the point: {@code wentAhead} false means no note is written at
         * all, and {@code destination} is whatever the teleport finally resolved to rather than what
         * it was aimed at when the gate looked.
         */
        private void settledTeleport(Rider rider, Where destination, boolean wentAhead) {
            if (!wentAhead) {
                return;
            }
            note(rider, destination);
            rider.where = destination;
            arrive(rider);
        }

        /**
         * The Folia transit nothing reports — #100.
         *
         * <p>PaperMC/Folia#453: a vehicle carrying a passenger through a portal fires neither
         * {@code EntityPortalEvent} nor {@code PlayerPortalEvent}, so there is no triage, no
         * capture, no cancellation and no ejection. Everyone simply arrives, and the world-change
         * backstop is the only thing that runs.
         */
        private void runUnreportedTransit(List<Rider> riders) {
            runUnreportedTransit(riders, NETHER);
        }

        private void runUnreportedTransit(List<Rider> riders, Where destination) {
            for (Rider rider : riders) {
                rider.where = destination;
                arrive(rider);
            }
        }

        /**
         * {@code ProgressionGateListener#onPlayerChangedWorld}, re-enacted over a rider double.
         *
         * <p>The same mirror caveat as {@link #runTransit} applies, and for the same reason: a
         * {@code Player} cannot be constructed off a server. What is <em>not</em> a mirror is the
         * verdict itself — that is the real {@link DimensionGateRules#arrival}, so the part of the
         * backstop that decides anything is the production code. This method contributes the
         * bookkeeping around it: the note is consumed rather than merely read, and the return puts
         * the player at the source world's spawn.
         */
        private void arrive(Rider rider) {
            DimensionUnlock gate = rider.where.gate();
            if (gate == null) {
                // gatedDestination answers empty: leaving the Nether, a multiverse Overworld hop, a
                // datapack dimension. The backstop does not run at all.
                return;
            }
            boolean decided =
                    DimensionGateRules.consume(ledger.get(rider), gate, rider.where.world(), clock);
            DimensionGateRules.Arrival verdict =
                    DimensionGateRules.arrival(decided, false, rider.qualified);
            if (verdict == DimensionGateRules.Arrival.REJECTED) {
                rider.backstopped = true;
                rider.where = OVERWORLD_SPAWN;
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

            assertEquals(NETHER, veteran.where, "the qualified rider is not stranded");
            assertEquals(OVERWORLD, newbie.where,
                    "the blocked rider must not be carried through the gate");
        }

        @Test
        @DisplayName("a lone blocked rider stays put, and so does the boat")
        void loneRiderStaysPut() {
            Rider newbie = new Rider("newbie", false);
            runTransit(List.of(newbie));
            assertEquals(OVERWORLD, newbie.where);
        }

        @Test
        @DisplayName("an all-blocked crew all stay behind")
        void everyoneBlocked() {
            Rider one = new Rider("one", false);
            Rider two = new Rider("two", false);
            runTransit(List.of(one, two));
            assertEquals(OVERWORLD, one.where);
            assertEquals(OVERWORLD, two.where);
        }

        @Test
        @DisplayName("a fully qualified crew travels together and nobody is sent back")
        void everyoneQualified() {
            Rider one = new Rider("one", true);
            Rider two = new Rider("two", true);
            runTransit(List.of(one, two));
            assertEquals(NETHER, one.where);
            assertEquals(NETHER, two.where);
        }

        /**
         * #100's first acceptance criterion. On Folia the vehicle carries the crew across and the
         * server tells the plugin nothing until the world has already changed, so every step above
         * is skipped and the backstop is the only thing between a blocked rider and the Nether.
         */
        @Test
        @DisplayName("a transit no portal event reported is still caught on arrival")
        void unreportedTransitIsBackstopped() {
            Rider veteran = new Rider("veteran", true);
            Rider newbie = new Rider("newbie", false);

            runUnreportedTransit(List.of(veteran, newbie));

            assertEquals(NETHER, veteran.where,
                    "the qualified rider earned this and must not be bounced");
            assertFalse(veteran.backstopped, "and must not even be considered for a return");
            assertEquals(OVERWORLD_SPAWN, newbie.where,
                    "nothing cleared this rider for the Nether, so the backstop returns them");
            assertTrue(newbie.backstopped);
        }

        /**
         * #100's second acceptance criterion, and the case the backstop is most likely to get wrong.
         * On Paper the mixed crew's transit is not cancelled, so the blocked rider is genuinely in
         * the Nether when the world change fires — the exemption {@code onEntityPortal} recorded is
         * the only thing that stops the backstop repositioning them on top of the ejection.
         */
        @Test
        @DisplayName("a rider the vehicle path already ejected is not handled twice")
        void ejectedRiderIsNotDoubleHandled() {
            Rider veteran = new Rider("veteran", true);
            Rider newbie = new Rider("newbie", false);

            runTransit(List.of(veteran, newbie));

            assertFalse(newbie.backstopped,
                    "the backstop must not touch a rider the vehicle path is already returning");
            assertEquals(OVERWORLD, newbie.where,
                    "the ejection's captured return point, not the backstop's world spawn");
            assertNotEquals(OVERWORLD_SPAWN, newbie.where);
        }

        /**
         * The other half of "only when the vehicle really moves": an all-blocked crew's transit is
         * cancelled, nobody arrives anywhere, and so no exemption is recorded. One that was would
         * sit there covering the next unreported transit for the rest of its window.
         */
        @Test
        @DisplayName("a cancelled transit leaves no exemption behind for a later arrival")
        void cancelledTransitRecordsNoExemption() {
            Rider newbie = new Rider("newbie", false);
            runTransit(List.of(newbie));
            assertEquals(OVERWORLD, newbie.where);

            // The Folia transit the plugin is never told about, moments later. If the cancelled
            // transit above had left a note, this would sail through.
            runUnreportedTransit(List.of(newbie));
            assertEquals(OVERWORLD_SPAWN, newbie.where);
        }

        /**
         * <strong>Finding 1.</strong> A note is good for the transit that wrote it and for no other.
         *
         * <p>The gate is a kind of dimension, so a multiverse server's two Nether worlds share one.
         * A decision taken about a teleport into the second one says nothing about an arrival in the
         * first — and the arrival in the first is the transit Folia never reported. Keyed on the
         * gate alone, as an earlier revision was, the note here is spent on the wrong arrival and
         * the player is in the Nether ungated.
         */
        @Test
        @DisplayName("an orphaned note is not spendable on an unreported transit elsewhere")
        void anOrphanedNoteIsNotSpentOnAnotherDestination() {
            Rider newbie = new Rider("newbie", false);

            // A decision taken about a transit into the server's second Nether, whose arrival never
            // came -- the shape every orphan has, however it was produced. It is still live.
            note(newbie, OTHER_NETHER);

            // Moments later a boat carries the player through a portal into the main Nether and
            // Folia reports nothing at all.
            runUnreportedTransit(List.of(newbie), NETHER);

            assertTrue(newbie.backstopped,
                    "nothing decided this arrival, so the backstop must catch it");
            assertEquals(OVERWORLD_SPAWN, newbie.where);
        }

        /**
         * <strong>Finding 1, the other half.</strong> One decision must not clear both gates.
         *
         * <p>An earlier revision wrote a note for <em>every</em> gate when a portal event arrived
         * with no resolved destination, on the reasoning that #92's fail-open should survive the
         * backstop. That made the fail-open route-independent: an unresolved Nether portal handed
         * out an End exemption as well. Nothing writes for an unresolved destination now, and a note
         * that is written names one gate.
         */
        @Test
        @DisplayName("a note for one gate does not clear the other")
        void aNoteForOneGateDoesNotClearTheOther() {
            Rider newbie = new Rider("newbie", false);
            note(newbie, NETHER);

            runUnreportedTransit(List.of(newbie), THE_END);

            assertTrue(newbie.backstopped, "the End gate was never decided for this player");
            assertEquals(OVERWORLD_SPAWN, newbie.where);
        }

        /**
         * <strong>Finding 2.</strong> A teleport a later handler cancels leaves nothing behind.
         *
         * <p>The exemption used to be recorded at {@code HIGH}, on the intention. A protection
         * plugin cancelling the teleport at {@code HIGHEST} then left a live exemption nobody
         * consumed, spendable for the rest of its window on exactly the arrival below.
         */
        @Test
        @DisplayName("a teleport cancelled after the gate looked leaves no exemption behind")
        void aCancelledTeleportLeavesNoExemption() {
            Rider newbie = new Rider("newbie", false);

            settledTeleport(newbie, NETHER, false);
            assertEquals(OVERWORLD, newbie.where, "the teleport did not happen");

            runUnreportedTransit(List.of(newbie));
            assertTrue(newbie.backstopped, "the orphaned exemption must not cover this");
            assertEquals(OVERWORLD_SPAWN, newbie.where);
        }

        /**
         * <strong>Finding 2, the mirror.</strong> A teleport redirected after the gate looked is
         * noted where it actually lands, so the legitimate arrival is not bounced — and the
         * destination it was aimed at is not thereby cleared.
         */
        @Test
        @DisplayName("a redirected teleport is noted where it lands, not where it was aimed")
        void aRedirectedTeleportIsNotedAtItsRealDestination() {
            Rider newbie = new Rider("newbie", false);

            // Aimed at the main Nether at HIGH; a hub plugin sends it to the other one at HIGHEST.
            settledTeleport(newbie, OTHER_NETHER, true);
            assertFalse(newbie.backstopped,
                    "the arrival that really happened is the one the note has to cover");

            newbie.where = OVERWORLD;
            runUnreportedTransit(List.of(newbie));
            assertTrue(newbie.backstopped,
                    "and the destination it was merely aimed at was never cleared");
        }

        /**
         * The staleness bound, over the ledger rather than over {@code decisionHolds} alone: a note
         * nobody consumed is no use to an arrival a window later.
         */
        @Test
        @DisplayName("a note the arrival never came for expires rather than covering a later one")
        void aNoteExpiresBeforeTheNextArrival() {
            Rider newbie = new Rider("newbie", false);
            note(newbie, NETHER);

            clock += DimensionGateRules.DECISION_WINDOW_MILLIS;
            runUnreportedTransit(List.of(newbie));

            assertTrue(newbie.backstopped);
            assertEquals(OVERWORLD_SPAWN, newbie.where);
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

            List<VehicleTransit.Ejection<Rider, Where>> orders =
                    VehicleTransit.orders(plan, rider -> rider.where);

            // The transit happens only now. A lazily captured return point would follow it.
            newbie.where = NETHER;

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
            List<VehicleTransit.Ejection<Rider, Where>> orders =
                    VehicleTransit.orders(plan, rider -> rider.where);

            assertEquals(plan.ejected().size(), orders.size());
            assertEquals(List.of("a", "c"), orders.stream().map(o -> o.rider().name).toList());
        }

        @Test
        @DisplayName("a plan with nothing to eject produces no orders")
        void noOrdersForANoOpPlan() {
            VehicleTransit.Plan<Rider> plan =
                    VehicleTransit.plan(List.of(new Rider("a", true)), rider -> !rider.qualified);
            assertTrue(VehicleTransit.orders(plan, rider -> rider.where).isEmpty());
        }
    }
}
