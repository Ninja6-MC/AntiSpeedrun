package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.ninja6.antispeedrun.config.PluginConfig.ItemTier;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.MilestoneRequirement;

/**
 * Every rule the item gate applies, with no Bukkit type anywhere in the signature.
 *
 * <p>The same split {@link DimensionGateRules} made for #34/#35/#6, for the same reason: the
 * decision is a pure function and {@link ItemProgressionListener} is the wiring that reads the
 * event, calls in here and acts on the answer. It matters more here than it did there, because the
 * item gate's hard part is not "may this player have diamonds" — that is one table lookup and one
 * cache read — but "does <em>this particular click</em> move an item toward the player", which has
 * six answers and no server-independent way to discover them by hand.
 *
 * <h2>What is deliberately not in this class</h2>
 *
 * No material universe, no table. {@link com.ninja6.antispeedrun.gating.ItemGateTable} already
 * answers "is this gated, and at which tier" without allocating, and it is generic in the material
 * type precisely so that a test can bind it to a fixture enum. Nothing here needs to know what a
 * material is.
 */
public final class ItemGateRules {

    private ItemGateRules() {
    }

    // -------------------------------------------------------------------------------------------
    // #9 - which click takes what
    // -------------------------------------------------------------------------------------------

    /**
     * The inventory gestures that can move an item, reduced to the distinctions the gate cares
     * about.
     *
     * <p>Bukkit's {@code ClickType} and {@code InventoryAction} between them enumerate around
     * thirty combinations, most of which differ only in how much of a stack moves. The gate does
     * not care about quantity, so {@link ItemProgressionListener} folds them into these seven on
     * the way in and the rule below is stated once per distinction that actually exists.
     */
    public enum Gesture {

        /** A plain click on a slot: take the stack onto the cursor, or swap it with what is held. */
        DIRECT,

        /**
         * A click that only puts the cursor down: all of it, some of it, or one item, into the
         * clicked slot.
         *
         * <p>Separate from {@link #DIRECT} because the two are the same button on the same slot and
         * only {@code InventoryAction} tells them apart. The destination cannot: a deposit onto an
         * empty slot and a deposit onto a matching stack are the same gesture, and neither moves
         * what is already sitting there.
         */
        DEPOSIT,

        /** Shift-click. The server moves the stack to the <em>other</em> inventory of the view. */
        QUICK_MOVE,

        /** Number key 1-9 or F: the clicked slot swaps with a hotbar slot or the off hand. */
        HOTBAR_SWAP,

        /** Double-click, which gathers every matching stack in the view onto the cursor. */
        COLLECT_TO_CURSOR,

        /** Q or Ctrl-Q on a slot: the stack leaves the view for the floor. */
        DROP,

        /** Anything that moves nothing at all. */
        INERT
    }

    /**
     * Which item a gesture moves toward the player, if any.
     *
     * <p>The caller resolves this against the event: {@code CLICKED_SLOT} is
     * {@code InventoryClickEvent#getCurrentItem()} and {@code CURSOR} is {@code #getCursor()}.
     * Returning <em>which</em> item rather than a bare boolean is what keeps deposits legal — see
     * {@link #withdrawn}.
     */
    public enum Subject {

        /** Nothing is being taken. The event is left alone. */
        NONE,

        /** The stack sitting in the clicked slot is what moves. */
        CLICKED_SLOT,

        /** The stack on the cursor is what grows. */
        CURSOR
    }

    /**
     * Whether this gesture withdraws from the container, and if so which stack to test.
     *
     * <h2>Why this is not simply "did they click the top inventory"</h2>
     *
     * Because the same button on the same slot is a withdrawal or a deposit depending on what is on
     * the cursor. Clicking a top-inventory slot with an empty cursor takes the stack; clicking it
     * with a loaded one puts items down. Which of the two it was is carried in the
     * {@link Gesture}, because it cannot be recovered afterwards from the slot: an earlier revision
     * answered {@code CLICKED_SLOT} for every direct click on the top half and left the caller to
     * test whatever was in the slot, which read correctly for a pickup and wrongly for a merge —
     * putting diamonds into a chest slot that already held diamonds was refused on the strength of
     * the diamonds already in the chest. {@link Gesture#DEPOSIT} exists to state that difference
     * once, where it can be tested.
     *
     * <p>Answering with the <em>subject</em> rather than a boolean is what lets the two cases below
     * name a stack that is not the clicked one:
     *
     * <ul>
     *   <li><strong>{@link Gesture#COLLECT_TO_CURSOR}</strong> ignores {@code clickedTopInventory}
     *       entirely. A double-click gathers matching stacks from the <em>whole view</em>, so a
     *       double-click on a stack in the player's own inventory still empties the chest's
     *       matching stacks onto the cursor. Keying this on where the click landed would leave the
     *       simplest siphon in the game open. The cost is that a player cannot consolidate their
     *       own above-tier stacks by double-clicking while any container is open — a gather that
     *       would have taken nothing out of the chest is still refused, because this rule cannot
     *       see what the view holds. Closing the container makes it work again.</li>
     *   <li><strong>{@link Gesture#DROP}</strong> from a container slot counts as a withdrawal even
     *       though the item lands on the floor rather than in the player's inventory. It is a
     *       withdrawal in one move and a pickup in the next, and while #12 would refuse that
     *       pickup, the drop-recall rule in §4 of the provenance record is exactly the kind of
     *       exemption a two-step laundering route goes looking for. A player who may not take a
     *       stack out of a chest may not fling it out either.</li>
     * </ul>
     *
     * @param gesture             the gesture, folded down by the caller
     * @param clickedTopInventory whether the clicked raw slot lies in the view's top inventory —
     *                            that is, in the container rather than in the player's own
     *                            inventory
     * @return which stack, if any, the caller must test against the gate
     */
    public static Subject withdrawn(Gesture gesture, boolean clickedTopInventory) {
        Objects.requireNonNull(gesture, "gesture");

        if (gesture == Gesture.COLLECT_TO_CURSOR) {
            return Subject.CURSOR;
        }
        if (!clickedTopInventory) {
            return Subject.NONE;
        }
        return switch (gesture) {
            case DIRECT, QUICK_MOVE, HOTBAR_SWAP, DROP -> Subject.CLICKED_SLOT;
            case COLLECT_TO_CURSOR, DEPOSIT, INERT -> Subject.NONE;
        };
    }

    // -------------------------------------------------------------------------------------------
    // Waivers and requirements
    // -------------------------------------------------------------------------------------------

    /**
     * Whether the item gate does not apply to this player at all, before any progression is looked
     * at.
     *
     * <p>{@link DimensionGateRules#waived} minus its third waiver: there is no server-wide item
     * unlock, because {@code /asr unlock} opens dimensions and nothing opens a tier.
     *
     * <p>The master switch is deliberately not a fourth argument. {@code item-progression.enabled}
     * is read in {@code ItemProgressionListener.gatedTier}, which reports every material as ungated
     * when it is off, and every caller of this method has already been past it — so threading it in
     * here as well would be a second off switch that no test could ever see fail, which is what it
     * had become.
     *
     * @param hasBypassPermission {@code player.hasPermission(antispeedrun.bypass.items)}
     * @param hasBypassGrant      {@code plugin.bypasses().hasBypass(player, now)}
     */
    public static boolean waived(boolean hasBypassPermission, boolean hasBypassGrant) {
        return hasBypassPermission || hasBypassGrant;
    }

    /** What a tier demands, in the shape {@code ProgressionManager} evaluates. */
    public static MilestoneRequirement requirement(ItemTier tier) {
        return MilestoneRequirement.of(Objects.requireNonNull(tier, "tier"));
    }

    // -------------------------------------------------------------------------------------------
    // Feedback
    // -------------------------------------------------------------------------------------------

    /**
     * Whether enough time has passed to tell this player again.
     *
     * <p>{@code lastNotified} of {@code 0} means never told, and is stated as its own case rather
     * than left to the subtraction. With {@code now} coming from {@code System#currentTimeMillis}
     * the difference from zero is always enormous and the arithmetic would give the same answer —
     * but only because of where the epoch happens to be, which is not a property worth depending
     * on and not one a reader should have to reconstruct.
     *
     * <p>The comparison is {@code >=} so that a cooldown of {@code 0} — an operator who wants every
     * refusal announced — does not silently swallow messages inside the same millisecond.
     */
    public static boolean shouldNotify(long now, long lastNotified, long cooldownMillis) {
        if (lastNotified == 0L) {
            return true;
        }
        return now - lastNotified >= cooldownMillis;
    }

    /**
     * What to put in {@code {REQUIREMENT}}.
     *
     * <p>The tier's configured {@code hint} wins whenever it is set, because it is the operator
     * saying how they want the requirement described — "Mine Stone with a wooden pickaxe (Stone
     * Age)" is better player-facing text than any list of advancement keys this method could
     * assemble. The composed fallback exists for a tier whose hint was left blank, and names only
     * what the player can still go and do: {@link EligibilityResult#unresolvableAdvancements()} is
     * excluded because the evaluator has already waived those, and repeating them here would tell a
     * player to go and earn something this server does not define.
     *
     * @return never blank; falls back to a generic line when a tier has no hint and the result
     *         carries nothing actionable, which happens when the only outstanding requirement was
     *         waived
     */
    public static String requirementText(ItemTier tier, EligibilityResult result) {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(result, "result");

        String hint = tier.hint();
        if (!hint.isBlank()) {
            return hint;
        }
        return outstanding(result);
    }

    /**
     * What is still outstanding, composed from the evaluation alone.
     *
     * <p>Extracted from {@link #requirementText} so that {@link MendingTradeRules}'s gate can share
     * it. Section 8 configures no {@code hint} — it has no tier to hang one on — so the composed
     * line is all that gate ever has, and duplicating this arithmetic there would be two
     * descriptions of a refusal that can drift apart.
     *
     * <p>Names only what the player can still go and do:
     * {@link EligibilityResult#unresolvableAdvancements()} is excluded because the evaluator has
     * already waived those, and repeating them here would tell a player to go and earn something
     * this server does not define.
     *
     * @return never blank; falls back to a generic line when the result carries nothing actionable,
     *         which happens when the only outstanding requirement was waived
     */
    public static String outstanding(EligibilityResult result) {
        Objects.requireNonNull(result, "result");

        List<String> parts = new ArrayList<>(3);
        if (!result.missingAdvancements().isEmpty()) {
            parts.add(String.join(", ", result.missingAdvancements()));
        }
        if (result.missingPlaytimeHours() > 0.0D) {
            parts.add(formatHours(result.missingPlaytimeHours()) + " more playtime");
        }
        if (result.missingAccountAgeDays() > 0) {
            int days = result.missingAccountAgeDays();
            parts.add(days + (days == 1 ? " more day on this server" : " more days on this server"));
        }
        return parts.isEmpty() ? "further progression" : String.join(" and ", parts);
    }

    /**
     * Trims a fractional hour count to something a player wants to read.
     *
     * <p>{@code 2.0} becomes {@code 2h} rather than {@code 2.0h}, and anything fractional keeps one
     * decimal. {@code Locale.ROOT} because this is a number in a sentence, not a formatted quantity
     * an operator configures.
     */
    private static String formatHours(double hours) {
        if (hours == Math.rint(hours)) {
            return (long) hours + "h";
        }
        return String.format(java.util.Locale.ROOT, "%.1fh", hours);
    }

    /**
     * Substitutes the two placeholders the shipped {@code rejection-message} uses.
     *
     * <p>Both replacements are literal: {@code String#replace} rather than {@code replaceAll}, so a
     * material name or a hint containing {@code $} or a regex metacharacter is inserted as itself.
     * The result is still MiniMessage and is still deserialised by the caller — neutralising tags
     * in the interpolated halves is the caller's job, because that needs the MiniMessage instance.
     *
     * @param template     the operator's {@code item-progression.rejection-message}
     * @param itemName     the display name for {@code {ITEM}}, already escaped by the caller
     * @param requirement  the text for {@code {REQUIREMENT}}, already escaped by the caller
     */
    public static String rejection(String template, String itemName, String requirement) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(itemName, "itemName");
        Objects.requireNonNull(requirement, "requirement");
        return template.replace("{ITEM}", itemName).replace("{REQUIREMENT}", requirement);
    }

    /**
     * {@code DIAMOND_CHESTPLATE} to {@code Diamond Chestplate}.
     *
     * <p>Enum constant names are what the gate has to work with — the table is keyed on them and
     * the configuration names materials that way — but they are not what a player calls the thing.
     * Bukkit does carry a translatable display name, and this deliberately does not use it: reading
     * it means holding an {@code ItemStack}, and two of the four handlers here have a material and
     * no stack to hand.
     */
    public static String friendlyName(String materialName) {
        Objects.requireNonNull(materialName, "materialName");
        if (materialName.isEmpty()) {
            return materialName;
        }
        StringBuilder out = new StringBuilder(materialName.length());
        boolean startOfWord = true;
        for (int i = 0; i < materialName.length(); i++) {
            char c = materialName.charAt(i);
            if (c == '_') {
                out.append(' ');
                startOfWord = true;
            } else if (startOfWord) {
                out.append(Character.toUpperCase(c));
                startOfWord = false;
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    // -------------------------------------------------------------------------------------------
    // §4 drop recall - matching an item entity to a death
    // -------------------------------------------------------------------------------------------

    /**
     * Whether an item that has just spawned belongs to a death recorded a moment ago.
     *
     * <p>A manual drop hands {@link ItemProgressionListener} the item entity directly, so it needs
     * none of this. A death does not: {@code PlayerDeathEvent#getDrops()} is a list of
     * <em>stacks</em>, and the entities carrying them do not exist until the server spawns them
     * immediately afterwards. Stamping the stacks instead is not an option — "no {@code ItemStack}
     * ever carries a plugin tag" is the invariant the provenance record leans on to make laundering
     * structurally impossible — so the death is remembered for a moment and the items are matched
     * to it as they appear.
     *
     * <p>Both bounds are needed and neither is sufficient. Time alone would claim every item
     * spawning anywhere in the world for that instant; distance alone would claim a stack dropped
     * on a grave hours later.
     *
     * <p>The failure mode this leaves is bounded and worth stating plainly: another player's item
     * spawning within {@code radius} of a fresh corpse inside {@code windowMillis} is stamped for
     * the dead player, who could then re-collect it above tier. It requires standing on a corpse in
     * the same second, it privileges one player over one stack for at most the item's despawn, and
     * the alternative — tagging stacks — reopens the laundering vector the whole model exists to
     * close.
     *
     * @param dx             item x minus death x
     * @param dy             item y minus death y
     * @param dz             item z minus death z
     * @param elapsedMillis  how long ago the death was recorded; negative is treated as no match
     *                       rather than as a clock that ran backwards
     * @param radius         how far from the death position a drop may appear, in blocks
     * @param windowMillis   how long after the death drops are still attributed to it
     */
    public static boolean withinDeathWindow(double dx, double dy, double dz, long elapsedMillis,
                                            double radius, long windowMillis) {
        if (elapsedMillis < 0L || elapsedMillis > windowMillis) {
            return false;
        }
        return (dx * dx) + (dy * dy) + (dz * dz) <= radius * radius;
    }
}
