package com.ninja6.antispeedrun.listeners;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.ninja6.antispeedrun.listeners.ItemGateRules.Gesture;
import static com.ninja6.antispeedrun.listeners.ItemGateRules.Subject;
import static com.ninja6.antispeedrun.listeners.InventoryGestures.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link InventoryGestures#of(InventoryAction, ClickType)} — the fold, and the one decision in
 * the click path that a test can reach without a server.
 *
 * <p>{@link ItemProgressionListener} itself is deliberately untested, as
 * {@code ProgressionGateListener} is: it reads events and applies answers. It is also literally
 * untestable — it holds an {@code EnumSet<InventoryType>}, and {@code InventoryType} resolves
 * {@code MenuType} through Paper's registry in its static initialiser, so the class cannot even be
 * loaded off-server. That is why this fold lives in its own file: see {@link InventoryGestures}.
 *
 * <p>{@code ItemGateRulesTest.depositOntoAnOccupiedSlot} asserts that a {@code DEPOSIT} gesture
 * takes nothing. Whether an ordinary click on an occupied container slot ever <em>becomes</em> a
 * {@code DEPOSIT} is decided here, and nowhere else — which is the layer the original test missed.
 *
 * <p>Neither {@link InventoryAction} nor {@link ClickType} needs a running server to name, so this
 * costs nothing.
 */
class GestureFoldTest {

    @Nested
    @DisplayName("Deposits, which the action decides and the click type cannot")
    class Deposits {

        /**
         * The defect this method was extracted for. A left-click on a container slot is
         * {@code PICKUP_ALL} when the cursor is empty and {@code PLACE_ALL} when it is full — same
         * button, same slot, opposite meaning — so the click type alone cannot tell a player
         * filling a chest from one emptying it.
         */
        @Test
        @DisplayName("the three PLACE actions are deposits whatever the click type")
        void placeActionsAreDeposits() {
            assertEquals(Gesture.DEPOSIT, of(InventoryAction.PLACE_ALL, ClickType.LEFT));
            assertEquals(Gesture.DEPOSIT, of(InventoryAction.PLACE_SOME, ClickType.LEFT));
            assertEquals(Gesture.DEPOSIT, of(InventoryAction.PLACE_ONE, ClickType.RIGHT));
        }

        /** A deposit takes nothing, wherever it lands. The two halves of the fix, joined up. */
        @Test
        @DisplayName("and a deposit gesture withdraws nothing from either half")
        void depositsWithdrawNothing() {
            assertEquals(Subject.NONE,
                    ItemGateRules.withdrawn(of(InventoryAction.PLACE_SOME, ClickType.LEFT), true));
            assertEquals(Subject.NONE,
                    ItemGateRules.withdrawn(of(InventoryAction.PLACE_ALL, ClickType.LEFT), false));
        }

        @Test
        @DisplayName("a click the server resolved to no movement is inert")
        void nothingIsInert() {
            assertEquals(Gesture.INERT, of(InventoryAction.NOTHING, ClickType.LEFT));
        }
    }

    @Nested
    @DisplayName("The withdrawals that must never be mistaken for deposits")
    class NotDeposits {

        /**
         * The negative case, and the one that matters most. These two are what would turn the
         * deposit fix into a leak: both are ordinary left-clicks on a container slot, and both take
         * the slot's stack. If a future edit widened the action arm to cover them, every test in
         * {@code ItemGateRulesTest} would still pass and the gate would be open.
         */
        @Test
        @DisplayName("taking a stack, and swapping one for what is held, are withdrawals")
        void pickupAndSwapAreNotDeposits() {
            assertEquals(Gesture.DIRECT, of(InventoryAction.PICKUP_ALL, ClickType.LEFT));
            assertEquals(Gesture.DIRECT, of(InventoryAction.PICKUP_HALF, ClickType.RIGHT));
            assertEquals(Gesture.DIRECT, of(InventoryAction.SWAP_WITH_CURSOR, ClickType.LEFT));

            assertEquals(Subject.CLICKED_SLOT,
                    ItemGateRules.withdrawn(of(InventoryAction.PICKUP_ALL, ClickType.LEFT), true));
            assertEquals(Subject.CLICKED_SLOT,
                    ItemGateRules.withdrawn(of(InventoryAction.SWAP_WITH_CURSOR, ClickType.LEFT), true));
        }

        @Test
        @DisplayName("shift-click, hotbar swap, off-hand swap and double-click keep their gestures")
        void theOtherExtractionsSurviveTheActionArm() {
            assertEquals(Gesture.QUICK_MOVE,
                    of(InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT));
            assertEquals(Gesture.HOTBAR_SWAP,
                    of(InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY));
            assertEquals(Gesture.HOTBAR_SWAP,
                    of(InventoryAction.HOTBAR_SWAP, ClickType.SWAP_OFFHAND));
            assertEquals(Gesture.COLLECT_TO_CURSOR,
                    of(InventoryAction.COLLECT_TO_CURSOR, ClickType.DOUBLE_CLICK));
        }

        @Test
        @DisplayName("dropping out of a container slot stays a drop")
        void dropsSurvive() {
            assertEquals(Gesture.DROP, of(InventoryAction.DROP_ONE_SLOT, ClickType.DROP));
            assertEquals(Gesture.DROP, of(InventoryAction.DROP_ALL_SLOT, ClickType.CONTROL_DROP));
        }

        /**
         * Exhaustive rather than sampled, because both arms are lists of constants and the failure
         * mode is somebody adding one to either. Only the three {@code PLACE_*} actions may produce
         * a {@code DEPOSIT}, and no click type may produce one on its own.
         *
         * <p>Both enums are swept, not just the actions. Fixing the click type would constrain the
         * action arm alone, and a {@code case MIDDLE -> DEPOSIT} slipped into the click switch would
         * pass every assertion in this file — which is the same shape of gap that let the original
         * deposit defect through.
         */
        @Test
        @DisplayName("no other action or click type anywhere in the API resolves to a deposit")
        void onlyPlaceActionsDeposit() {
            for (InventoryAction action : InventoryAction.values()) {
                boolean isPlace = action == InventoryAction.PLACE_ALL
                        || action == InventoryAction.PLACE_SOME
                        || action == InventoryAction.PLACE_ONE;
                for (ClickType click : ClickType.values()) {
                    Gesture gesture = of(action, click);
                    if (isPlace) {
                        assertEquals(Gesture.DEPOSIT, gesture,
                                action + " with " + click + " must be a deposit");
                    } else {
                        assertNotEquals(Gesture.DEPOSIT, gesture,
                                action + " with " + click + " must not be a deposit");
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("The fall-through, and where it is not conservative")
    class FallThrough {

        /**
         * An unrecognised click type on a container slot is treated as a withdrawal rather than
         * waved through. It costs nothing, because a slot the player may already take from is not
         * gated anyway.
         */
        @Test
        @DisplayName("an unrecognised click type is treated as a withdrawal")
        void unknownClickTypeIsRefused() {
            assertEquals(Gesture.DIRECT, of(InventoryAction.UNKNOWN, ClickType.CREATIVE));
            assertEquals(Gesture.DIRECT, of(InventoryAction.UNKNOWN, ClickType.MIDDLE));
        }

        @Test
        @DisplayName("clicks on the window border move nothing")
        void windowBorderIsInert() {
            assertEquals(Gesture.INERT,
                    of(InventoryAction.NOTHING, ClickType.WINDOW_BORDER_LEFT));
            assertEquals(Gesture.INERT,
                    of(InventoryAction.UNKNOWN, ClickType.WINDOW_BORDER_RIGHT));
        }

        /**
         * Pins the bundle gap rather than pretending it is closed, so that whoever implements
         * {@code gate-nested-bundles} for #15 finds a failing expectation to update instead of a
         * silent hole.
         *
         * <p>{@code PICKUP_FROM_BUNDLE} falls through to {@code DIRECT}, so
         * {@link ItemGateRules#withdrawn} names the clicked slot — the bundle itself, which is in
         * no tier — and never the gated stack being pulled out of it. Closing that needs a subject
         * resolving to the extracted stack, which is #15's work and not this class's.
         */
        @Test
        @DisplayName("pulling from a bundle names the bundle, not its contents -- #15's gap")
        void bundleExtractionNamesTheBundle() {
            assertEquals(Gesture.DIRECT,
                    of(InventoryAction.PICKUP_FROM_BUNDLE, ClickType.RIGHT));
            assertEquals(Subject.CLICKED_SLOT, ItemGateRules.withdrawn(
                    of(InventoryAction.PICKUP_FROM_BUNDLE, ClickType.RIGHT), true));
        }
    }
}
