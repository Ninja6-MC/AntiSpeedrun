package com.ninja6.antispeedrun.listeners;

import java.util.Objects;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;

/**
 * Folds Bukkit's click taxonomy down to the distinctions {@link ItemGateRules} reasons about.
 *
 * <h2>Why this is not in {@link ItemGateRules}, and not in the listener either</h2>
 *
 * It is a decision, so by this package's convention it belongs beside the other decisions in
 * {@link ItemGateRules} — except that class's defining property is that no Bukkit type appears
 * anywhere in its signatures, and this fold is inherently about two Bukkit enums. Putting them
 * there would trade a real invariant for a filing preference.
 *
 * <p>It cannot live in {@link ItemProgressionListener} either, and the reason is worth recording
 * because it is not obvious and it cost a round to discover. That class holds an
 * {@code EnumSet<InventoryType>} as a static field, and {@code InventoryType}'s own static
 * initialiser resolves {@code MenuType} through Paper's registry — which throws
 * {@code IllegalStateException: No RegistryAccess implementation found} off-server. So the listener
 * cannot be <em>class-loaded</em> without a running server, let alone instantiated, and any method
 * on it is untestable no matter how it is scoped. A test that reached for one got
 * {@code ExceptionInInitializerError} before running a line of its own.
 *
 * <p>{@link InventoryAction} and {@link ClickType} carry no such dependency: they are ordinary
 * enums, so this class loads and answers with no server at all. That is the whole reason it is a
 * separate file.
 *
 * <h2>What the fold decides</h2>
 *
 * The action is consulted before the click type, and only for the deposits, because the click type
 * cannot tell a deposit from a pickup: both are a left button on one slot, and which one happened
 * depends on what the cursor was carrying. A left-click on a container slot is
 * {@link InventoryAction#PICKUP_ALL} with an empty cursor and {@link InventoryAction#PLACE_ALL}
 * with a full one — same button, same slot, opposite meaning. Answering from the click type alone
 * is what let a player be refused for merging their own stack into a chest slot that already held
 * one.
 */
public final class InventoryGestures {

    private InventoryGestures() {
    }

    /**
     * The gesture a click represents.
     *
     * <p>The three {@code PLACE_*} actions are the whole deposit set — all of the cursor, part of
     * it, or one item — and {@link InventoryAction#NOTHING} is a click the server resolved to no
     * movement at all. Everything else falls through to the click type.
     *
     * <p>{@code default} is {@link ItemGateRules.Gesture#DIRECT} rather than {@code INERT}, which
     * is the conservative direction for an unrecognised or future <em>click type</em>: on a
     * container slot holding a gated stack it is treated as a withdrawal and refused rather than
     * waved through. It costs nothing, because a slot the player may already take from is not gated
     * anyway, and a click outside the window carries a raw slot of {@code -999}, which is not in
     * the top inventory and never reaches this classification.
     *
     * <h2>Where the fall-through is not conservative: bundles</h2>
     *
     * That default is not a safe answer for every action, and the gap is named here rather than
     * left to be discovered. {@link InventoryAction#PICKUP_FROM_BUNDLE} falls through to
     * {@link ClickType#RIGHT} and becomes {@code DIRECT}, so {@link ItemGateRules#withdrawn} names
     * the clicked slot — which holds the <em>bundle</em>, not the stack being pulled out of it. A
     * bundle is in no tier, so a player right-clicking a bundle left in a chest takes its gated
     * contents onto their cursor untested.
     *
     * <p>That is not closed here because it is not this fold's to close. Bundle contents are
     * {@code item-progression.gate-nested-bundles}, which #15 owns and which nothing reads yet;
     * closing it needs a {@link ItemGateRules.Subject} that resolves to the extracted stack rather
     * than to the clicked slot. {@code PICKUP_ALL_INTO_BUNDLE}, {@code PICKUP_SOME_INTO_BUNDLE},
     * {@code PLACE_ALL_INTO_BUNDLE}, {@code PLACE_SOME_INTO_BUNDLE} and {@code PLACE_FROM_BUNDLE}
     * all land somewhere harmless, but by accident rather than by decision, so they should be
     * revisited in the same pass. {@code GestureFoldTest} pins the current answers so that pass
     * finds failing expectations rather than silence.
     */
    public static ItemGateRules.Gesture of(InventoryAction action, ClickType click) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(click, "click");

        switch (action) {
            case PLACE_ALL, PLACE_SOME, PLACE_ONE -> {
                return ItemGateRules.Gesture.DEPOSIT;
            }
            case NOTHING -> {
                return ItemGateRules.Gesture.INERT;
            }
            default -> {
                // Not a deposit; the click type decides.
            }
        }
        return switch (click) {
            case NUMBER_KEY, SWAP_OFFHAND -> ItemGateRules.Gesture.HOTBAR_SWAP;
            case DOUBLE_CLICK -> ItemGateRules.Gesture.COLLECT_TO_CURSOR;
            case DROP, CONTROL_DROP -> ItemGateRules.Gesture.DROP;
            case SHIFT_LEFT, SHIFT_RIGHT -> ItemGateRules.Gesture.QUICK_MOVE;
            case WINDOW_BORDER_LEFT, WINDOW_BORDER_RIGHT, UNKNOWN -> ItemGateRules.Gesture.INERT;
            default -> ItemGateRules.Gesture.DIRECT;
        };
    }
}
