package com.ninja6.antispeedrun.listeners;

import java.util.Locale;

/**
 * Who contributed to a dragon fight and when it was won (#278), kept apart from the server types
 * they are decided about so they can be tested without a server. {@link DragonFightCreditListener}
 * applies them.
 */
final class DragonFightRules {

    private DragonFightRules() {
    }

    /**
     * Whether a hit on an Ender Dragon makes its attacker a contributor: in an End world, positive
     * and finite after every other handler, and from a real player rather than an NPC. A cancelled
     * hit never reaches this question.
     */
    static boolean contributes(boolean inEnd, double finalDamage, boolean player, boolean npc) {
        return inEnd && WitherContributors.counts(finalDamage, player, npc);
    }

    /**
     * Whether an uncancelled Ender Dragon death wins its fight: the primary, in an End world. A
     * secondary's death never does; the primary's death is cancelled while any secondary lives.
     */
    static boolean wins(boolean inEnd, boolean secondary) {
        return inEnd && !secondary;
    }

    /**
     * Whether a dragon's removal abandons its fight: the primary, in an End world, removed by anything
     * but its death, an unload or its last player leaving. Those three leave the fight as it was;
     * its death has already been handled as a victory.
     */
    static boolean abandons(boolean inEnd, boolean secondary, String causeName) {
        if (!inEnd || secondary) {
            return false;
        }
        String cause = causeName == null ? "" : causeName.toUpperCase(Locale.ROOT);
        return !cause.equals("DEATH") && !DragonReconciliationRules.survivesRemoval(cause);
    }
}
