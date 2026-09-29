package com.ninja6.antispeedrun.listeners;

/**
 * Which stack a right click on an Allay moves, with no Bukkit type in the signature (#17).
 *
 * <p>Mirrors the branch order of the vanilla Allay interaction, which is the only thing that decides
 * what an empty or occupied hand means:
 *
 * <ul>
 *   <li>the Allay holds nothing and the player's hand does: the Allay takes one of the player's
 *       stack, so the player's item is {@link Transfer#GIVEN};</li>
 *   <li>the Allay holds something, the player clicked with the main hand, and that hand is empty:
 *       the Allay hands its item back, so the Allay's item is {@link Transfer#RETURNED};</li>
 *   <li>anything else moves nothing.</li>
 * </ul>
 *
 * <p>Vanilla checks one case before these: a dancing Allay offered an amethyst shard duplicates
 * itself instead of taking the shard. Dancing is not modelled, so that click is read as a hand-off
 * of the shard. It only matters on a server that gates {@code AMETHYST_SHARD}, and there the player
 * is being refused a material they may not hold anyway.
 *
 * <p>An Allay collecting items off the ground is not a player interaction and never reaches this
 * rule, so Allay sorters and note-block delivery chains are untouched. What an Allay delivers lands
 * on the ground as an item entity, where the pickup gate applies.
 */
public final class AllayHandoffRules {

    private AllayHandoffRules() {
    }

    /** Whose stack a click moves. */
    public enum Transfer {

        /** Nothing moves. */
        NONE,

        /** The player's held stack goes to the Allay. Test the player's item. */
        GIVEN,

        /** The Allay's held stack comes back to the player. Test the Allay's item. */
        RETURNED
    }

    /**
     * @param allayHandEmpty  the Allay's main hand holds nothing
     * @param playerHandEmpty the hand the player clicked with holds nothing
     * @param mainHand        the player clicked with their main hand
     */
    public static Transfer transfer(boolean allayHandEmpty, boolean playerHandEmpty,
                                    boolean mainHand) {
        if (allayHandEmpty) {
            return playerHandEmpty ? Transfer.NONE : Transfer.GIVEN;
        }
        return mainHand && playerHandEmpty ? Transfer.RETURNED : Transfer.NONE;
    }
}
