package com.ninja6.antispeedrun.listeners;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig.ItemTier;
import com.ninja6.antispeedrun.progression.EligibilityResult;

import static com.ninja6.antispeedrun.listeners.ItemGateRules.Gesture;
import static com.ninja6.antispeedrun.listeners.ItemGateRules.Subject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item gate's decisions, none of which need a server.
 *
 * <p>{@link ItemProgressionListener} is deliberately untested, the same way
 * {@code ProgressionGateListener} is: everything it does is read an event, call in here and apply
 * the answer. What is worth testing is in this file.
 */
class ItemGateRulesTest {

    private static final boolean TOP = true;
    private static final boolean BOTTOM = false;

    private static ItemTier tier(String id, String hint) {
        return new ItemTier(id, List.of(), List.of(), List.of(), List.of(), 0.0D, 0, hint);
    }

    private static EligibilityResult blocked(List<String> missingAdvancements,
                                             double missingHours, int missingDays) {
        return new EligibilityResult(false, missingAdvancements, List.of(),
                missingHours, missingDays, false);
    }

    @Nested
    @DisplayName("#9 - which click takes what")
    class Withdrawal {

        /**
         * The four gestures that pull a stack out of a container slot. Each is one of #9's
         * acceptance criteria: shift-click, hotbar swap 1-9, and the ordinary click, plus the drop
         * that #9 does not name but that would otherwise be the way around all three.
         */
        @Test
        @DisplayName("taking from the container names the clicked slot")
        void extractionFromTop() {
            assertEquals(Subject.CLICKED_SLOT, ItemGateRules.withdrawn(Gesture.DIRECT, TOP));
            assertEquals(Subject.CLICKED_SLOT, ItemGateRules.withdrawn(Gesture.QUICK_MOVE, TOP));
            assertEquals(Subject.CLICKED_SLOT, ItemGateRules.withdrawn(Gesture.HOTBAR_SWAP, TOP));
            assertEquals(Subject.CLICKED_SLOT, ItemGateRules.withdrawn(Gesture.DROP, TOP));
        }

        /**
         * The criterion this exists to protect: a legitimate player must be able to put gated items
         * <em>into</em> a chest. Every one of these is the same gesture as above, aimed the other
         * way.
         */
        @Test
        @DisplayName("depositing into the container is never a withdrawal")
        void depositIsLeftAlone() {
            assertEquals(Subject.NONE, ItemGateRules.withdrawn(Gesture.DIRECT, BOTTOM));
            assertEquals(Subject.NONE, ItemGateRules.withdrawn(Gesture.QUICK_MOVE, BOTTOM));
            assertEquals(Subject.NONE, ItemGateRules.withdrawn(Gesture.HOTBAR_SWAP, BOTTOM));
            assertEquals(Subject.NONE, ItemGateRules.withdrawn(Gesture.DROP, BOTTOM));
        }

        /**
         * The gesture the test above could not reach, and the gap that let a real defect through:
         * every case there starts in the player's own half, so none of them is the ordinary way a
         * player fills a chest — cursor loaded, click a slot in the top half.
         *
         * <p>That click lands on the container, so a rule keyed on the clicked half alone answered
         * {@code CLICKED_SLOT} and the caller tested whatever the slot held. Onto an empty slot that
         * was harmless; onto a matching stack it refused the deposit on the strength of the stack
         * already in the chest, so putting ten diamonds onto thirty was blocked while putting them
         * into the next slot along was not.
         */
        @Test
        @DisplayName("a deposit onto an occupied container slot is still a deposit")
        void depositOntoAnOccupiedSlot() {
            assertEquals(Subject.NONE, ItemGateRules.withdrawn(Gesture.DEPOSIT, TOP));
            assertEquals(Subject.NONE, ItemGateRules.withdrawn(Gesture.DEPOSIT, BOTTOM));
        }

        /**
         * The one gesture that ignores where the click landed. A double-click gathers matching
         * stacks from the whole view, so keying it on the clicked slot would leave the simplest
         * siphon in the game open: double-click a stack in your own inventory, and the chest empties
         * onto your cursor.
         */
        @Test
        @DisplayName("double-click gathers from the whole view, wherever it was clicked")
        void collectToCursorIgnoresWhereItWasClicked() {
            assertEquals(Subject.CURSOR, ItemGateRules.withdrawn(Gesture.COLLECT_TO_CURSOR, TOP));
            assertEquals(Subject.CURSOR, ItemGateRules.withdrawn(Gesture.COLLECT_TO_CURSOR, BOTTOM));
        }

        @Test
        @DisplayName("an inert gesture takes nothing from either half")
        void inertTakesNothing() {
            assertEquals(Subject.NONE, ItemGateRules.withdrawn(Gesture.INERT, TOP));
            assertEquals(Subject.NONE, ItemGateRules.withdrawn(Gesture.INERT, BOTTOM));
        }

        @Test
        @DisplayName("a null gesture is a programming error, not a silent pass")
        void nullGestureThrows() {
            assertThrows(NullPointerException.class, () -> ItemGateRules.withdrawn(null, TOP));
        }
    }

    @Nested
    @DisplayName("Waivers")
    class Waivers {

        @Test
        @DisplayName("with no exemption, the player is gated")
        void gatedByDefault() {
            assertFalse(ItemGateRules.waived(false, false));
        }

        /**
         * There is no third argument for {@code item-progression.enabled}, and its absence is the
         * point. The master switch is read once, in {@code ItemProgressionListener.gatedTier},
         * which reports every material as ungated while it is off — so a copy of it here could
         * never be the check that fired, and a dead argument in a pure rule is a rule that looks
         * tested and is not.
         */
        @Test
        @DisplayName("the permission and a bypass grant each waive independently")
        void eitherExemptionIsEnough() {
            assertTrue(ItemGateRules.waived(true, false));
            assertTrue(ItemGateRules.waived(false, true));
            assertTrue(ItemGateRules.waived(true, true));
        }
    }

    @Nested
    @DisplayName("Feedback throttling")
    class Throttle {

        /**
         * "Never told" is its own case, not an artefact of the epoch being far away. Asserted with
         * a {@code now} smaller than the cooldown precisely so that the subtraction alone would get
         * this wrong.
         */
        @Test
        @DisplayName("a player never told before is due, whatever the clock reads")
        void neverTold() {
            assertTrue(ItemGateRules.shouldNotify(1_000L, 0L, 3_000L));
            assertTrue(ItemGateRules.shouldNotify(System.currentTimeMillis(), 0L, 3_000L));
        }

        @Test
        @DisplayName("inside the cooldown, silence")
        void insideCooldown() {
            assertFalse(ItemGateRules.shouldNotify(3_999L, 1_000L, 3_000L));
        }

        /**
         * The boundary is inclusive, which is what makes a cooldown of zero mean "tell them every
         * time" rather than "tell them at most once per millisecond".
         */
        @Test
        @DisplayName("the boundary is inclusive, so a zero cooldown never swallows a message")
        void boundaryIsInclusive() {
            assertTrue(ItemGateRules.shouldNotify(4_000L, 1_000L, 3_000L));
            assertTrue(ItemGateRules.shouldNotify(50L, 50L, 0L));
        }
    }

    @Nested
    @DisplayName("What {REQUIREMENT} says")
    class RequirementText {

        /**
         * The operator's own words beat anything this class could assemble. "Mine Stone with a
         * wooden pickaxe (Stone Age)" is the shipped hint for the iron tier, and it is better player
         * facing text than {@code minecraft:story/mine_stone}.
         */
        @Test
        @DisplayName("a configured hint wins outright")
        void hintWins() {
            ItemTier iron = tier("iron-tier", "Mine Stone with a wooden pickaxe (Stone Age)");
            String text = ItemGateRules.requirementText(iron,
                    blocked(List.of("minecraft:story/mine_stone"), 0.0D, 0));
            assertEquals("Mine Stone with a wooden pickaxe (Stone Age)", text);
        }

        @Test
        @DisplayName("with no hint, the outstanding advancements are named")
        void fallsBackToAdvancements() {
            String text = ItemGateRules.requirementText(tier("diamond-tier", ""),
                    blocked(List.of("minecraft:story/smelt_iron"), 0.0D, 0));
            assertEquals("minecraft:story/smelt_iron", text);
        }

        @Test
        @DisplayName("playtime and account age join the sentence, and whole hours lose the decimal")
        void fallsBackToTime() {
            assertEquals("2h more playtime",
                    ItemGateRules.requirementText(tier("t", ""), blocked(List.of(), 2.0D, 0)));
            assertEquals("1.5h more playtime",
                    ItemGateRules.requirementText(tier("t", ""), blocked(List.of(), 1.5D, 0)));
            assertEquals("1 more day on this server",
                    ItemGateRules.requirementText(tier("t", ""), blocked(List.of(), 0.0D, 1)));
            assertEquals("3 more days on this server",
                    ItemGateRules.requirementText(tier("t", ""), blocked(List.of(), 0.0D, 3)));
        }

        @Test
        @DisplayName("several outstanding requirements read as one clause")
        void fallbackJoinsParts() {
            String text = ItemGateRules.requirementText(tier("t", ""),
                    blocked(List.of("a", "b"), 2.0D, 1));
            assertEquals("a, b and 2h more playtime and 1 more day on this server", text);
        }

        /**
         * Reachable when the only outstanding requirement was an unresolvable advancement, which
         * {@code MilestoneEvaluator} waives. Telling the player to go and earn a key this server
         * does not define would be worse than saying nothing useful.
         */
        @Test
        @DisplayName("nothing actionable still produces a line rather than a blank")
        void neverBlank() {
            String text = ItemGateRules.requirementText(tier("t", ""),
                    new EligibilityResult(false, List.of(), List.of("minecraft:nope"), 0.0D, 0, false));
            assertEquals("further progression", text);
        }
    }

    @Nested
    @DisplayName("Rejection message")
    class Rejection {

        @Test
        @DisplayName("both placeholders are filled")
        void substitutesBoth() {
            String out = ItemGateRules.rejection(
                    "<red>You cannot pick up <yellow>{ITEM}<red>! Requires: <gold>{REQUIREMENT}",
                    "Diamond Sword", "Smelt Iron");
            assertEquals("<red>You cannot pick up <yellow>Diamond Sword<red>! Requires: <gold>Smelt Iron",
                    out);
        }

        /**
         * {@code String#replace} rather than {@code replaceAll}. A hint containing {@code $1} or a
         * backslash is a perfectly ordinary thing for an operator to write, and under a regex
         * replacement it would be interpreted as a group reference and either mangle the line or
         * throw on a live server.
         */
        @Test
        @DisplayName("a replacement containing regex metacharacters is inserted literally")
        void replacementIsLiteral() {
            String out = ItemGateRules.rejection("{ITEM} needs {REQUIREMENT}", "$1 \\ item", "$0");
            assertEquals("$1 \\ item needs $0", out);
        }

        @Test
        @DisplayName("a template using neither placeholder is left as it is")
        void templateWithoutPlaceholders() {
            assertEquals("<red>Nope", ItemGateRules.rejection("<red>Nope", "Diamond", "Smelt Iron"));
        }
    }

    @Nested
    @DisplayName("Material names a player recognises")
    class FriendlyName {

        @Test
        @DisplayName("enum constants become title case words")
        void titleCases() {
            assertEquals("Diamond Chestplate", ItemGateRules.friendlyName("DIAMOND_CHESTPLATE"));
            assertEquals("Diamond", ItemGateRules.friendlyName("DIAMOND"));
            assertEquals("Netherite Upgrade Smithing Template",
                    ItemGateRules.friendlyName("NETHERITE_UPGRADE_SMITHING_TEMPLATE"));
        }

        @Test
        @DisplayName("an empty name is handed back rather than blowing up")
        void emptyName() {
            assertEquals("", ItemGateRules.friendlyName(""));
        }
    }

    @Nested
    @DisplayName("§4 - matching a drop to a death")
    class DeathWindow {

        private static final double RADIUS = 4.0D;
        private static final long WINDOW = 1_000L;

        @Test
        @DisplayName("a drop on the corpse, in the same instant, matches")
        void onTheCorpse() {
            assertTrue(ItemGateRules.withinDeathWindow(0, 0, 0, 0L, RADIUS, WINDOW));
        }

        @Test
        @DisplayName("both bounds are inclusive at the edge")
        void edgesMatch() {
            assertTrue(ItemGateRules.withinDeathWindow(RADIUS, 0, 0, WINDOW, RADIUS, WINDOW));
        }

        /**
         * Distance alone is not enough: without the time bound, a stack dropped on a grave hours
         * later would be handed to whoever died there.
         */
        @Test
        @DisplayName("close enough but too late does not match")
        void tooLate() {
            assertFalse(ItemGateRules.withinDeathWindow(0, 0, 0, WINDOW + 1L, RADIUS, WINDOW));
        }

        /**
         * And time alone is not enough either, or every item spawning anywhere in the world in that
         * instant would be claimed.
         */
        @Test
        @DisplayName("in time but too far does not match")
        void tooFar() {
            assertFalse(ItemGateRules.withinDeathWindow(RADIUS + 0.01D, 0, 0, 0L, RADIUS, WINDOW));
            assertFalse(ItemGateRules.withinDeathWindow(3, 3, 3, 0L, RADIUS, WINDOW));
        }

        @Test
        @DisplayName("distance is spherical, not per-axis")
        void distanceIsSpherical() {
            // 3-4-5: exactly on the sphere at radius 5, and outside a radius of 4.
            assertTrue(ItemGateRules.withinDeathWindow(3, 4, 0, 0L, 5.0D, WINDOW));
            assertFalse(ItemGateRules.withinDeathWindow(3, 4, 0, 0L, RADIUS, WINDOW));
        }

        /**
         * A clock that appears to run backwards -- an NTP step between the death and the spawn --
         * must not be read as "zero elapsed and therefore a match".
         */
        @Test
        @DisplayName("negative elapsed time never matches")
        void negativeElapsed() {
            assertFalse(ItemGateRules.withinDeathWindow(0, 0, 0, -1L, RADIUS, WINDOW));
        }
    }
}
