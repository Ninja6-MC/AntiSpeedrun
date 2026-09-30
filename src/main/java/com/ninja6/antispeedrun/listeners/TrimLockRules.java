package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType.SlotType;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.TrimProgressionManager;

/**
 * The decisions behind {@link TrimSmithingListener} (#19), kept free of anything that needs a
 * running server so they can be tested directly.
 *
 * <p>Like {@link InventoryGestures}, this takes {@link SlotType}, {@link InventoryAction} and
 * {@link ClickType} but never {@code InventoryType} itself: that enum resolves {@code MenuType}
 * through Paper's registry in its static initialiser, and a class that touched it could not be
 * loaded off-server. The listener answers "is this the player's own inventory screen" and passes
 * the boolean in.
 */
public final class TrimLockRules {

    private TrimLockRules() {
    }

    /** Which lock a refusal came from; decides the wording and the feedback throttle key. */
    public enum Lock {

        /** {@code block-unearned-smithing}: a Smithing Table result. */
        SMITHING("forge this"),

        /** {@code block-wearing-unearned-trims}: an armor slot. */
        WEARING("wear this");

        private final String verb;

        Lock(String verb) {
            this.verb = verb;
        }

        /** The phrase completing "... first to". */
        public String verb() {
            return verb;
        }
    }

    /**
     * Where the stack a click would put into an armor slot comes from.
     *
     * <p>{@link #NONE} for every click that cannot equip anything, which is almost all of them.
     */
    public enum EquipSource {

        /** The stack on the cursor, placed or swapped onto an armor slot. */
        CURSOR,

        /** The clicked stack, shift-clicked from the player's own inventory into an empty armor slot. */
        CLICKED,

        /** The hotbar stack named by the number key pressed over an armor slot. */
        HOTBAR,

        /** The off-hand stack, swapped onto an armor slot with the swap-hands key. */
        OFF_HAND,

        /** The click equips nothing. */
        NONE
    }

    /** Whether {@code block-unearned-smithing} is in force: section 3 active and that lock on. */
    public static boolean smithingLocked(PluginConfig config) {
        return TrimProgressionManager.isActive(config)
                && config.trimProgression().blockUnearnedSmithing();
    }

    /** Whether {@code block-wearing-unearned-trims} is in force: section 3 active and that lock on. */
    public static boolean wearingLocked(PluginConfig config) {
        return TrimProgressionManager.isActive(config)
                && config.trimProgression().blockWearingUnearnedTrims();
    }

    /**
     * The milestone gating a Smithing Table application.
     *
     * <p>The template decides first: an armor trim template by its pattern, and the netherite
     * upgrade template by {@link TrimProgressionManager#BASTION}. The trim on the result is the
     * fallback, so a trim applied by a template this plugin does not recognise is still caught when
     * its pattern is gated.
     *
     * @param template      the template slot's material, or {@code null} when it is empty
     * @param resultPattern the trim pattern key on the result, or {@code null} when it carries none
     * @return the gating milestone, or empty when the application is free
     */
    public static Optional<Milestone> smithingGate(Material template, NamespacedKey resultPattern) {
        if (template != null) {
            Optional<Milestone> byTemplate = TrimProgressionManager.forTemplate(template);
            if (byTemplate.isPresent()) {
                return byTemplate;
            }
        }
        return resultPattern == null ? Optional.empty() : TrimProgressionManager.forPattern(resultPattern);
    }

    /**
     * Which stack, if any, a click would put into one of the player's armor slots.
     *
     * @param slotType            the clicked slot's type
     * @param action              what the server resolved the click to
     * @param click               the click type
     * @param ownInventoryView    whether the open view is the player's own inventory screen, the
     *                            only one in which a shift-click equips armor
     * @param clickedOwnInventory whether the clicked slot belongs to the player's inventory rather
     *                            than the view's top half
     * @param armorSlotEmpty      whether the armor slot the clicked stack would go to is empty;
     *                            only read for a shift-click
     */
    public static EquipSource equipSource(SlotType slotType, InventoryAction action, ClickType click,
                                          boolean ownInventoryView, boolean clickedOwnInventory,
                                          boolean armorSlotEmpty) {
        Objects.requireNonNull(slotType, "slotType");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(click, "click");
        if (slotType == SlotType.ARMOR) {
            return switch (action) {
                case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> EquipSource.CURSOR;
                case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD ->
                        click == ClickType.SWAP_OFFHAND ? EquipSource.OFF_HAND : EquipSource.HOTBAR;
                default -> EquipSource.NONE;
            };
        }
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY && ownInventoryView
                && clickedOwnInventory && armorSlotEmpty) {
            return EquipSource.CLICKED;
        }
        return EquipSource.NONE;
    }

    /**
     * What the player has to go and do, in their words rather than the advancement's.
     *
     * <p>The Ancient City is proven by Sneak 100, which no player would guess from the structure's
     * name, so its line says how.
     */
    public static String requirement(Milestone milestone) {
        Objects.requireNonNull(milestone, "milestone");
        String name = milestone.displayName();
        String line = "Explore " + article(name) + " " + name;
        if (milestone.equals(TrimProgressionManager.ANCIENT_CITY)) {
            line += " and sneak past a Sculk Sensor or Warden";
        }
        return line;
    }

    /**
     * The action-bar line for a refusal, as MiniMessage.
     *
     * @param requirement {@link #requirement}, already escaped by the caller
     */
    public static String rejection(Lock lock, String requirement) {
        Objects.requireNonNull(lock, "lock");
        Objects.requireNonNull(requirement, "requirement");
        return "<red>" + requirement + " first to " + lock.verb() + ".";
    }

    /** The feedback throttle key: one per lock and structure. */
    public static String feedbackKey(Lock lock, Milestone milestone) {
        return lock.name() + ':' + milestone.id();
    }

    private static String article(String name) {
        return !name.isEmpty() && "AEIOUaeiou".indexOf(name.charAt(0)) >= 0 ? "an" : "a";
    }
}
