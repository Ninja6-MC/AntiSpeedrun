package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
}
