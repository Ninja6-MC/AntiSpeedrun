package com.ninja6.antispeedrun.progression;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a player should be told on join, given what they satisfy now and what they were last told.
 *
 * <p>Split out of {@link ProgressionManager} for the reason {@link MilestoneEvaluator} is: this is
 * the decision, and it is a pure function of two sets, whereas the manager is Bukkit-bound and
 * {@code paper-api} is {@code compileOnly} and never reaches the test classpath.
 *
 * <h2>The problem this solves — issue #84</h2>
 *
 * {@code PlayerAdvancementDoneEvent} announces every gate whose last outstanding requirement is an
 * advancement, and {@link UnlockWatch} announces the gate whose last outstanding requirement is a
 * duration that elapses <em>while the player is online</em>. Neither covers
 * {@code require-account-age-days}, which advances mostly while the player is away: by the time
 * they log back in the gate is already open, {@code primeUnlocks} records it silently, and the
 * watch is never armed because the milestone is already in the "already told" set. The player is
 * told nothing, ever, about that gate.
 *
 * <p>A join cannot tell "cleared while you were away" from "cleared weeks ago" out of thin air —
 * both look identical to a fresh evaluation, which is exactly why {@code primeUnlocks} is silent.
 * The missing fact is what the player has previously been <em>told</em>, and that is what
 * {@link AnnouncedUnlockStore} now persists on the player, alongside the bypass grant and the
 * journey-book flag from #57. With it, the join decision is a set difference.
 *
 * <h2>What a first join with no record does — the wipe question</h2>
 *
 * Nothing. An absent record is not an empty one: it means this plugin has never written the
 * player's record, which is true of every player on the server the day the feature is installed and
 * of everyone again after a playerdata wipe. Announcing the difference against an empty set there
 * would congratulate an established population on gates they cleared months ago, en masse, which is
 * the failure {@code primeUnlocks} exists to prevent. So the first join seeds the record silently
 * and announces from the second join onward. The cost is one missed announcement for a gate that
 * happened to clear during that very first absence; the alternative is a server-wide false
 * congratulation on rollout, which is worse and much more visible.
 *
 * <p>An <em>empty</em> stored record, by contrast, is a real fact — "you have been told about
 * nothing" — and the difference against it is announced normally.
 *
 * @param announce milestone ids to announce now, in the order they were supplied; empty on a first
 *                 prime
 * @param record   the ids to persist as "this player has now been told about these"
 */
public record AnnouncedUnlocks(List<String> announce, Set<String> record) {

    /**
     * Separates ids in the persisted form.
     *
     * <p>A newline rather than a comma: milestone ids are configuration-derived and a separator has
     * to be a character an id cannot contain. Ids are {@code dimension:nether}-shaped today and item
     * tiers will be named in {@code config.yml}, where a comma is entirely plausible and a newline
     * is not.
     */
    public static final String SEPARATOR = "\n";

    public AnnouncedUnlocks {
        announce = List.copyOf(Objects.requireNonNull(announce, "announce"));
        record = Set.copyOf(Objects.requireNonNull(record, "record"));
    }

    /**
     * The join decision.
     *
     * @param stored      the player's persisted record, or empty when they have none at all. The
     *                    distinction is the whole point; see the class javadoc
     * @param nowEligible ids the player currently satisfies, in configured order
     */
    public static AnnouncedUnlocks onJoin(Optional<Set<String>> stored, List<String> nowEligible) {
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(nowEligible, "nowEligible");

        Set<String> eligible = new LinkedHashSet<>(nowEligible);
        if (stored.isEmpty()) {
            return new AnnouncedUnlocks(List.of(), eligible);
        }

        Set<String> alreadyTold = stored.get();
        List<String> announce = new ArrayList<>();
        for (String id : eligible) {
            if (!alreadyTold.contains(id)) {
                announce.add(id);
            }
        }
        return new AnnouncedUnlocks(announce, eligible);
    }

    /**
     * Parses a persisted record.
     *
     * @param stored the stored string, or {@code null} when the player has no record
     * @return the recorded ids, or empty when {@code stored} is {@code null}. An empty string parses
     *         to a present, empty set — see the class javadoc
     */
    public static Optional<Set<String>> decode(String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String token : stored.split(SEPARATOR, -1)) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                ids.add(trimmed);
            }
        }
        return Optional.of(Set.copyOf(ids));
    }

    /** Renders {@code ids} for storage. The inverse of {@link #decode}. */
    public static String encode(Collection<String> ids) {
        return String.join(SEPARATOR, Objects.requireNonNull(ids, "ids"));
    }
}
