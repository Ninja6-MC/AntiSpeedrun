package com.ninja6.antispeedrun.listeners;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import com.ninja6.antispeedrun.AntiSpeedrunPlugin;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.PlayerStateMap;
import com.ninja6.antispeedrun.storage.DimensionUnlock;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Refuses an Eye of Ender throw until the player has found a Nether Fortress (#7, Task 3.2.1).
 *
 * <p>The decisions are in {@link EyeThrowRules}; this class reads the event and applies them.
 *
 * <h2>Why the interaction, and why only the item half of it</h2>
 *
 * The throw is refused at {@link PlayerInteractEvent} by denying {@code useItemInHand}, which stops
 * the item's use from running at all. That is what satisfies both of #7's criteria at once: the
 * eye is only consumed, and the {@code EnderSignal} only spawned, inside that use. Refusing later —
 * cancelling the entity's spawn — would leave nothing that says which player threw it.
 *
 * <p>{@code useInteractedBlock} is left as it was. A player holding an eye who right-clicks a chest
 * or a door still opens it; only the throw that would have followed is withheld. And a right click
 * on a block covers the throw that follows it: the server answers the use-item packet the client
 * sends after a block click with the verdict this event already reached for that block, so there is
 * no second event for the throw to slip through.
 *
 * <p>Not {@code ignoreCancelled}. A right click in the air arrives <em>already cancelled</em>,
 * because there is no block for {@code useInteractedBlock} to allow, so that flag would skip every
 * air throw. The handler asks instead whether the item use has already been denied, which is the
 * only half of the event it cares about.
 *
 * <h2>Folia</h2>
 *
 * A single-player event, called on the region owning the player. The clicked block is within reach
 * of that player and so in the same region. Progression is read through the cache, the bypass
 * grant from the player's PDC and the End unlock from memory; nothing is scheduled and nothing
 * touches a file.
 */
public final class EyeThrowListener implements Listener {

    /**
     * The standing exemption. Section 7 lives under {@code antispeedrun.bypass.anticheese}, and this
     * rule is configured in section 7, so it is waived by that node rather than by a new one.
     */
    public static final String BYPASS_PERMISSION = "antispeedrun.bypass.anticheese";

    /**
     * How long a refused player goes without being told again. A held right click re-fires the
     * interaction every few ticks, so without this the line would repeat for as long as the button
     * is held. Three seconds, matching the dimension gate's constant; section 7 has no cooldown key.
     */
    private static final long FEEDBACK_COOLDOWN_MILLIS = 3_000L;

    private final AntiSpeedrunPlugin plugin;

    /** When each player was last told. Registered, so quit clears it (finding R-08). */
    private final PlayerStateMap<Long> lastFeedback;

    public EyeThrowListener(AntiSpeedrunPlugin plugin) {
        this.plugin = plugin;
        this.lastFeedback = plugin.playerState().register("eye-throw-feedback");
    }

    /**
     * Denies the item use when the eye would be thrown and the player has not earned it.
     *
     * <p>Ordered so the common case is cheap: every interaction in the game fires this, and all but
     * a handful are not holding an eye, so the material check comes first and allocates nothing.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.ENDER_EYE
                || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        PluginConfig config = plugin.configuration();
        if (!EyeThrowRules.armed(config)
                || !EyeThrowRules.isThrow(click(event.getAction()), emptyFrame(event.getClickedBlock()))) {
            return;
        }
        Player player = event.getPlayer();
        if (waived(player)) {
            return;
        }
        EligibilityResult result = plugin.progression().evaluate(
                player, config, EyeThrowRules.requirement(config));
        if (result.eligible()) {
            return;
        }
        // Refuse first, then explain: a message that throws costs the explanation, not the refusal.
        event.setUseItemInHand(Event.Result.DENY);
        notify(player, config);
    }

    private static EyeThrowRules.Click click(Action action) {
        return switch (action) {
            case RIGHT_CLICK_AIR -> EyeThrowRules.Click.RIGHT_AIR;
            case RIGHT_CLICK_BLOCK -> EyeThrowRules.Click.RIGHT_BLOCK;
            default -> EyeThrowRules.Click.OTHER;
        };
    }

    private static boolean emptyFrame(Block block) {
        return block != null
                && block.getBlockData() instanceof EndPortalFrame frame
                && !frame.hasEye();
    }

    private boolean waived(Player player) {
        return EyeThrowRules.waived(
                player.hasPermission(BYPASS_PERMISSION),
                plugin.bypasses().hasBypass(player, System.currentTimeMillis()),
                plugin.dimensionUnlocks().isUnlocked(DimensionUnlock.THE_END));
    }

    private void notify(Player player, PluginConfig config) {
        long now = System.currentTimeMillis();
        long last = lastFeedback.getOrDefault(player.getUniqueId(), 0L);
        if (!ItemGateRules.shouldNotify(now, last, FEEDBACK_COOLDOWN_MILLIS)) {
            return;
        }
        lastFeedback.put(player.getUniqueId(), now);
        player.sendActionBar(MiniMessage.miniMessage().deserialize(
                config.antiCheese().earlyEyeRejectionMessage()));
    }
}
