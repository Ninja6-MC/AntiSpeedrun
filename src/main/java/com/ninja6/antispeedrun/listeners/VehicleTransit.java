package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What to do about a vehicle carrying a mixed crew into a portal — #35's whole decision, with no
 * Bukkit type in it.
 *
 * <p>The naive answer is to cancel the transit whenever any rider fails the gate, and it is wrong:
 * a boat is shared, so one unqualified passenger would strand a qualified one who did nothing. The
 * answer #35 asks for is <em>selective</em>: eject the riders who fail, let the vehicle and
 * everyone who passed carry on.
 *
 * <p>That leaves one case worth stating rather than falling into, and {@link Plan#cancelTransit()}
 * is it. When <em>every</em> rider fails, ejecting them all and letting the transit proceed would
 * send an empty boat to the Nether — a vehicle nobody asked to move, in a dimension nobody was
 * allowed to enter, which is exactly the ghost the audit's R-09 warns about. So the transit is
 * cancelled in that case, and the riders are still ejected, because a rider left sitting in a
 * cancelled portal is a rider being pushed into it again next tick.
 */
public final class VehicleTransit {

    private VehicleTransit() {
    }

    /**
     * The decision for one vehicle.
     *
     * @param ejected        the riders to remove from the vehicle and reposition, in the order they
     *                       were given. Never {@code null}; empty means nothing to do
     * @param cancelTransit  whether the portal event itself must be cancelled
     * @param <T>            whatever the caller uses to represent a rider; {@code Player} in
     *                       production, a test double otherwise
     */
    public record Plan<T>(List<T> ejected, boolean cancelTransit) {

        public Plan {
            ejected = List.copyOf(Objects.requireNonNull(ejected, "ejected"));
        }

        /** Whether the vehicle may transit untouched — nothing to eject and nothing to cancel. */
        public boolean isNoOp() {
            return ejected.isEmpty() && !cancelTransit;
        }
    }

    /**
     * Triages {@code riders} against {@code blocked}.
     *
     * <p>{@code blocked} is evaluated exactly once per rider and in order, because in production it
     * is a progression evaluation against a per-player cache and because the message a blocked
     * rider is shown is sent from the same pass.
     *
     * @param riders  every player riding the vehicle, directly or through another passenger. An
     *                empty list yields a no-op plan: an empty boat is not gated, since nobody is
     *                being carried anywhere they have not earned
     * @param blocked whether this rider fails the gate — already accounting for bypasses
     * @return the plan; never {@code null}
     */
    public static <T> Plan<T> plan(List<T> riders, Predicate<? super T> blocked) {
        Objects.requireNonNull(riders, "riders");
        Objects.requireNonNull(blocked, "blocked");

        if (riders.isEmpty()) {
            return new Plan<>(List.of(), false);
        }
        List<T> ejected = new ArrayList<>(riders.size());
        for (T rider : riders) {
            if (blocked.test(rider)) {
                ejected.add(rider);
            }
        }
        if (ejected.isEmpty()) {
            return new Plan<>(List.of(), false);
        }
        // Cancel only when nobody qualified is left to carry. With a mixed crew the vehicle goes,
        // which is the point of the exercise.
        return new Plan<>(ejected, ejected.size() == riders.size());
    }

    /**
     * Where one ejected rider is to be put back.
     *
     * @param rider    the rider to dismount
     * @param returnTo where to place them, <strong>captured before the transit resolved</strong>
     * @param <T>      the rider representation
     * @param <P>      the position representation; {@code Location} in production
     */
    public record Ejection<T, P>(T rider, P returnTo) {

        public Ejection {
            Objects.requireNonNull(rider, "rider");
            Objects.requireNonNull(returnTo, "returnTo");
        }
    }

    /**
     * Fixes each ejected rider's return position <em>now</em>, before the portal transfer resolves.
     *
     * <p>This method is one line long and exists entirely for its timing, so it is worth being
     * blunt about what it is defending against. The dismount and the teleport cannot happen inline
     * — audit finding R-09 is explicit that mutating a passenger list while the portal transfer is
     * resolving produces ghost entities — so they are deferred by a tick. But when a mixed crew
     * travels, the transit is <em>not</em> cancelled, and by the time that deferred work runs the
     * rider may already be standing in the dimension the gate just refused them. A deferred task
     * that asks "where is this rider?" gets the answer "in the Nether", and repositioning them two
     * blocks behind that is a chauffeur service into a sealed dimension rather than a gate.
     *
     * <p>So the question is asked here instead, on the event thread, while the answer is still the
     * Overworld. {@code captureReturnPoint} is invoked eagerly, once per ejected rider, before this
     * method returns; the deferred task is handed a position and never computes one.
     *
     * @param plan               the triage from {@link #plan}
     * @param captureReturnPoint reads the rider's current position and works out where to put them
     *                           back. Called immediately, not later
     * @return one order per ejected rider, in plan order. Empty when nothing is to be ejected
     */
    public static <T, P> List<Ejection<T, P>> orders(Plan<T> plan,
                                                     Function<? super T, ? extends P> captureReturnPoint) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(captureReturnPoint, "captureReturnPoint");

        List<Ejection<T, P>> orders = new ArrayList<>(plan.ejected().size());
        for (T rider : plan.ejected()) {
            orders.add(new Ejection<>(rider, captureReturnPoint.apply(rider)));
        }
        return List.copyOf(orders);
    }
}
