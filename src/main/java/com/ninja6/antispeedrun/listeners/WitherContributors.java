package com.ninja6.antispeedrun.listeners;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** The deduplicated UUIDs stored on a Wither until its confirmed death. */
final class WitherContributors {
    private WitherContributors() {
    }

    static boolean counts(double finalDamage, boolean player, boolean npc) {
        return Double.isFinite(finalDamage) && finalDamage > 0.0D
                && PersonalCreditRules.creditable(player, npc);
    }

    static Set<UUID> decode(long[] stored) {
        Set<UUID> players = new LinkedHashSet<>();
        if (stored != null && stored.length % 2 == 0) {
            for (int i = 0; i < stored.length; i += 2) {
                players.add(new UUID(stored[i], stored[i + 1]));
            }
        }
        return players;
    }

    static long[] encode(Set<UUID> players) {
        long[] stored = new long[players.size() * 2];
        int i = 0;
        for (UUID player : players) {
            stored[i++] = player.getMostSignificantBits();
            stored[i++] = player.getLeastSignificantBits();
        }
        return stored;
    }
}
