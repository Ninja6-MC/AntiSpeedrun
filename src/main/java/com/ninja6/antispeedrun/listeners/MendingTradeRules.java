package com.ninja6.antispeedrun.listeners;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.MilestoneRequirement;

/**
 * Section 8's one gate: a villager trade whose result carries Mending (#8, Task 3.2.2).
 *
 * <p>The same Bukkit-free split {@link ItemGateRules} and {@link DimensionGateRules} make, for the
 * same reason — {@link ItemProgressionListener} reads the event, calls in here, and applies the
 * answer. There is more to state here than the size of the class suggests, because the gate this
 * implements is the only one in the plugin that is <em>not</em> a function of a {@code Material}.
 *
 * <h2>Why the item gate could not already do this</h2>
 *
 * {@link ItemProgressionListener#onTradeSelect} has gated merchant offers since #54, but it asks
 * {@link com.ninja6.antispeedrun.gating.ItemGateTable} about the result's {@code Material} and
 * nothing else. A librarian's Mending book and their Bane of Arthropods book are both
 * {@code ENCHANTED_BOOK}, so a material-keyed table can only gate every enchanted book or none of
 * them. Neither is what #8 asks for. This class is keyed on the enchantment instead.
 *
 * <h2>Enchantment keys, not {@code Enchantment} constants</h2>
 *
 * The caller resolves each enchantment on the result stack to its namespaced key and hands the
 * strings in. That keeps the decision testable without a server — {@code Enchantment} is a
 * registry-backed Bukkit interface and cannot be constructed in a unit test — and it costs nothing,
 * because the caller is already holding the stack.
 *
 * <p>Two sources have to be read, and reading only the obvious one is the mistake this class exists
 * to make hard to repeat: an {@code ENCHANTED_BOOK} carries its enchantments as
 * <em>stored</em> enchantments in {@code EnchantmentStorageMeta} and reports
 * {@code ItemStack#getEnchantments()} as empty. But the book is not the only route — a librarian
 * sells books, and a datapack or a plugin-supplied merchant can offer an already-enchanted tool
 * whose Mending is an ordinary enchantment. {@link #carriesMending} is therefore asked about the
 * union of both, and takes no view on which material it came from.
 */
public final class MendingTradeRules {

    /**
     * The enchantment this gate is about, as {@code Enchantment#getKey()} renders it.
     *
     * <p>Namespaced and lower case, so a caller that hands in {@code NamespacedKey#toString} output
     * matches without normalising. Unlike the advancement key beside it in section 8, this one is
     * deliberately <em>not</em> configurable: #8 gates Mending, and an operator who wants a
     * different enchantment gated is asking for a feature that does not exist yet rather than for a
     * different value in this constant.
     */
    public static final String MENDING_KEY = "minecraft:mending";

    /**
     * The raw slot a {@code MERCHANT} view puts its result in.
     *
     * <p>Slots 0 and 1 are the two ingredient inputs, which hold the player's <em>own</em> items and
     * must stay retrievable; 2 is the output. Fixed by the vanilla merchant screen rather than by
     * anything configurable, and used by {@link ItemProgressionListener} to scope the withdrawal
     * check to the one slot where a gated result can leave the merchant.
     */
    public static final int MERCHANT_RESULT_SLOT = 2;

    /**
     * The key {@link ItemProgressionListener} throttles this gate's feedback under.
     *
     * <p>The item gate's cooldown map is keyed per {@code ItemTier} id, and this gate shares it
     * rather than growing a second map: being refused a Mending book and then, moments later, a
     * diamond chestplate is two different pieces of news, which is precisely the distinction that
     * map already makes. The colon keeps it out of the tier namespace — a tier id is a YAML mapping
     * key an operator writes, and none of the shipped ones contains one.
     */
    public static final String FEEDBACK_KEY = "villager:mending";

    private MendingTradeRules() {
    }

    /**
     * Whether the gate is switched on at all.
     *
     * <p>Asked first by every caller, and the reason the trade path costs nothing on a default
     * server: {@code gate-mending-trade} defaults to {@code false}, and while it is false no stack
     * is inspected, no {@code ItemMeta} is copied and no progression is evaluated.
     */
    public static boolean armed(PluginConfig config) {
        Objects.requireNonNull(config, "config");
        return config.villagerProgression().gateMendingTrade();
    }

    /**
     * Whether a trade result carrying these enchantments is the one this gate is about.
     *
     * @param enchantmentKeys every enchantment on the stack, normal and stored alike, as namespaced
     *                        keys. {@code null} entries are tolerated and ignored rather than
     *                        throwing, because the collection is assembled from a registry lookup
     *                        the caller does not control
     */
    public static boolean carriesMending(Collection<String> enchantmentKeys) {
        Objects.requireNonNull(enchantmentKeys, "enchantmentKeys");
        for (String key : enchantmentKeys) {
            if (MENDING_KEY.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the gate demands, in the shape {@code ProgressionManager} evaluates.
     *
     * <p>Built from {@link Milestone#villagerTradeAdvancement}, which is the single place the two
     * configuration facts — is the gate on, and is the key blank — are turned into an answer. Going
     * through it rather than reading {@code required-advancement} directly is what keeps this gate
     * and the snapshot capture that pre-resolves advancement keys from disagreeing about which key
     * matters: a key this method required but that method never captured would force a fresh
     * snapshot on every trade click.
     *
     * <p>Section 8 configures no playtime or tenure requirement, so those are zero. An empty
     * requirement is returned when no advancement is required, and
     * {@code ProgressionManager#evaluate} short-circuits it to a pass without touching the cache.
     */
    public static MilestoneRequirement requirement(PluginConfig config) {
        Objects.requireNonNull(config, "config");
        Optional<String> advancement = Milestone.villagerTradeAdvancement(config);
        return advancement
                .map(key -> new MilestoneRequirement(List.of(key), 0.0D, 0))
                .orElseGet(MilestoneRequirement::none);
    }
}
