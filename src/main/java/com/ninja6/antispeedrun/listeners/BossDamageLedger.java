package com.ninja6.antispeedrun.listeners;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Final damage each capped boss has taken in the current tick, so the single-hit cap (#24) holds
 * for a burst of hits landing together (#203).
 *
 * <p>Clamping the first explosion of a same-tick TNT minecart stack lowers the damage the boss
 * records for its invulnerability window, so the explosions after it in that tick exceed the
 * recorded figure and land as further capped hits. With the cap off the window absorbs them.
 * Counting every hit in the tick against one budget of the cap restores that: the burst removes at
 * most the cap, as a single hit does.
 *
 * <p>Bukkit-free: the listener supplies the boss's id and the world's game time. A boss's damage
 * events run on the thread owning it, so one entry is never written by two threads at once; the
 * map itself is shared across Folia regions and is concurrent.
 */
final class BossDamageLedger {

    /** Entries older than this, in milliseconds, are dropped once the ledger grows past {@link #PRUNE_AT}. */
    static final long STALE_MILLIS = 10_000L;

    /** Size above which stale entries are pruned on the next record. */
    static final int PRUNE_AT = 32;

    private record Spent(long tick, double damage, long recordedAt) {
    }

    private final Map<UUID, Spent> spent = new ConcurrentHashMap<>();

    /** Final damage already recorded against {@code boss} in {@code tick}; zero for any other tick. */
    double spent(UUID boss, long tick) {
        Spent entry = spent.get(boss);
        return entry != null && entry.tick() == tick ? entry.damage() : 0.0D;
    }

    /** What is left of {@code cap} for {@code boss} in {@code tick}, never below zero. */
    double remaining(UUID boss, long tick, double cap) {
        return Math.max(0.0D, cap - spent(boss, tick));
    }

    /**
     * Adds a hit's final damage to {@code boss}'s total for {@code tick}, starting a new total when
     * the tick has moved on.
     *
     * @param now wall-clock time in milliseconds, used only to prune entries for bosses gone quiet
     */
    void record(UUID boss, long tick, double damage, long now) {
        if (!(damage > 0.0D)) {
            return;
        }
        spent.merge(boss, new Spent(tick, damage, now), (old, hit) -> old.tick() == hit.tick()
                ? new Spent(hit.tick(), old.damage() + hit.damage(), now)
                : hit);
        if (spent.size() > PRUNE_AT) {
            spent.values().removeIf(entry -> now - entry.recordedAt() > STALE_MILLIS);
        }
    }

    /** Drops {@code boss}'s entry, when it dies. */
    void forget(UUID boss) {
        spent.remove(boss);
    }

    /** Number of bosses with an entry. */
    int size() {
        return spent.size();
    }
}
