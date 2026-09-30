package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;

import org.bukkit.Material;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.TrimProgressionManager;

/**
 * The decisions behind {@link TemplateDuplicationListener}, kept free of events so they can be
 * tested without a server (#18).
 *
 * <h2>What counts as duplication</h2>
 *
 * Any crafting recipe whose result is a template {@link TrimProgressionManager#forTemplate} gates.
 * Vanilla has exactly one recipe per template, the seven-diamond copy, so matching on the result
 * rather than on the ingredient grid catches it without restating it, and also catches a datapack
 * recipe that produces the same template another way.
 */
public final class TemplateDuplicationRules {

    /** The refusal shown on the action bar. Not configurable; {@code config.yml} section 3 has no message key. */
    static final String REJECTION =
            "<red>🔒 You cannot duplicate <yellow>{ITEM}<red>! Requires: <gold>{STRUCTURE} explored";

    /** What a Crafter's craft of a gated template comes to. */
    public enum CrafterVerdict {
        /** The owner has explored the structure. */
        ALLOW,
        /** No owner is recorded on the Crafter, so nobody can vouch for it. */
        NO_OWNER,
        /** The owner was last seen without having explored the structure. */
        UNEXPLORED
    }

    private TemplateDuplicationRules() {
    }

    /** Whether the lock is on: section 3 active and {@code block-unearned-template-duplication} set. */
    public static boolean locked(PluginConfig config) {
        return TrimProgressionManager.isActive(config)
                && config.trimProgression().blockUnearnedTemplateDuplication();
    }

    /** The structure that gates crafting {@code result}, or empty when the result is not gated. */
    public static Optional<Milestone> gate(Material result) {
        return result == null || result.isAir() ? Optional.empty() : TrimProgressionManager.forTemplate(result);
    }

    /** The owner stamped on a Crafter, or empty when nothing, or something that is not a UUID, is. */
    public static Optional<UUID> owner(String stamped) {
        if (stamped == null || stamped.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(stamped));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /**
     * Whether a Crafter may craft a template gated by {@code gate}.
     *
     * @param explored the persisted record, {@code (owner, milestone id) -> explored}
     */
    public static CrafterVerdict crafter(Optional<UUID> owner, Milestone gate,
                                         BiPredicate<UUID, String> explored) {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(explored, "explored");
        if (owner.isEmpty()) {
            return CrafterVerdict.NO_OWNER;
        }
        return explored.test(owner.get(), gate.id()) ? CrafterVerdict.ALLOW : CrafterVerdict.UNEXPLORED;
    }

    /**
     * The refusal line, as MiniMessage.
     *
     * @param itemName  the template's display name, already escaped by the caller
     * @param structure the structure's display name, already escaped by the caller
     */
    public static String rejection(String itemName, String structure) {
        return REJECTION.replace("{ITEM}", Objects.requireNonNull(itemName, "itemName"))
                .replace("{STRUCTURE}", Objects.requireNonNull(structure, "structure"));
    }
}
