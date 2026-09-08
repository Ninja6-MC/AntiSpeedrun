package com.ninja6.antispeedrun.listeners;

import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * §4 of the provenance record: the one retained provenance rule, and the only one there will be.
 *
 * <p>A player may always re-collect an item entity carrying their own UUID, whatever tier it
 * belongs to. It exists for the cases where somebody legitimately holds above-tier items —
 * an administrative grant, gear predating installation, gear held while a bypass permission was
 * active that has since been revoked — because without it a tier check on death does not gate
 * those items, it confiscates them.
 *
 * <h2>Why this is safe, and why the fragility is the feature</h2>
 *
 * Audit finding R-10 flagged the short life of entity PDC as a defect. Here it is the entire
 * safety argument. The stamp:
 *
 * <ul>
 *   <li>cannot be transferred, because pickup destroys the entity that carries it;</li>
 *   <li>cannot be stockpiled, because despawn destroys it after about five minutes;</li>
 *   <li>cannot be diluted into a stack, because merging destroys it;</li>
 *   <li>privileges exactly one player, for exactly as long as the entity lives.</li>
 * </ul>
 *
 * <p><strong>No {@code ItemStack} is ever stamped.</strong> That is the invariant the whole model
 * rests on: with no per-item entitlement written into NBT, there is nothing to forge, transfer or
 * inherit, and laundering is structurally impossible rather than merely guarded against.
 *
 * <h2>The key</h2>
 *
 * The record calls this key {@code n6_asr_dropper}. Bukkit namespaces every key under the plugin
 * already, so the literal name here is {@code drop-owner} and the key on disk is
 * {@code antispeedrun:drop-owner} — the same convention {@code BypassStore} uses for
 * {@code bypass-expires-at}, rather than a second, hand-rolled prefix inside the namespace that
 * exists to provide one.
 *
 * <p>The value is a {@code LONG_ARRAY} of the UUID's two halves, which is R-10's amendment:
 * 16 bytes against the 36 a {@code STRING} would cost, on an entity that may exist in the
 * thousands on a busy server.
 */
public final class DropRecall {

    /**
     * How far from a recorded death an item may spawn and still be attributed to it.
     *
     * <p>Vanilla scatters death drops within about half a block of the corpse. Four is generous
     * against that, and still far short of the distance another player would have to be standing to
     * have their own drop mistakenly claimed.
     */
    static final double DEATH_RADIUS_BLOCKS = 4.0D;

    /**
     * How long a death stays eligible to claim newly spawned items.
     *
     * <p>The server spawns death drops in the same tick as {@code PlayerDeathEvent}, so this is
     * already two orders of magnitude longer than it needs to be; it is a second rather than a tick
     * so that a server stalling mid-death does not silently drop the stamping.
     */
    static final long DEATH_WINDOW_MILLIS = 1_000L;

    private final NamespacedKey dropOwner;

    /**
     * Deaths still inside their window, oldest first.
     *
     * <p>Concurrent because on Folia a death and an item spawn are ordinary region-thread events
     * and two players in different regions can die in the same tick. It stays small by
     * construction — entries live for {@link #DEATH_WINDOW_MILLIS} — and is pruned on every read
     * and every write, so no scheduled sweep is needed and nothing accumulates if the server is
     * idle.
     */
    private final Queue<DeathMark> deaths = new ConcurrentLinkedQueue<>();

    public DropRecall(Plugin plugin) {
        this.dropOwner = new NamespacedKey(Objects.requireNonNull(plugin, "plugin"), "drop-owner");
    }

    /** Where and when a player died, and who they were. */
    private record DeathMark(UUID player, UUID world, double x, double y, double z, long at) {
    }

    // -------------------------------------------------------------------------------------------
    // Stamping and reading
    // -------------------------------------------------------------------------------------------

    /** Records {@code owner} as the player who parted with this item entity. */
    public void stamp(Item item, UUID owner) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(owner, "owner");
        item.getPersistentDataContainer().set(dropOwner, PersistentDataType.LONG_ARRAY,
                new long[] {owner.getMostSignificantBits(), owner.getLeastSignificantBits()});
    }

    /**
     * Whether {@code player} is the player who dropped or died with this item.
     *
     * <p>An in-memory read on the entity the calling region already owns, so it is safe inline on
     * the pickup path — and it is only reached once the material is known gated and the player
     * known ineligible, which is the rare branch rather than the hot one.
     */
    public boolean belongsTo(Item item, UUID player) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(player, "player");
        PersistentDataContainer container = item.getPersistentDataContainer();
        long[] stored = container.get(dropOwner, PersistentDataType.LONG_ARRAY);
        if (stored == null || stored.length != 2) {
            return false;
        }
        return stored[0] == player.getMostSignificantBits()
                && stored[1] == player.getLeastSignificantBits();
    }

    // -------------------------------------------------------------------------------------------
    // Deaths, which have no entity to stamp yet
    // -------------------------------------------------------------------------------------------

    /**
     * Remembers a death so that the items about to spawn from it can be attributed.
     *
     * <p>{@code PlayerDeathEvent#getDrops()} hands over {@code ItemStack}s, and the {@code Item}
     * entities carrying them do not exist until the server spawns them a moment later. Stamping the
     * stacks would be the easy answer and is the one thing this model forbids, so the death is
     * recorded here and {@link #claim} matches the entities to it as they appear.
     *
     * @param at the death location; a location with no world is ignored, since nothing can then be
     *           matched against it
     */
    public void recordDeath(UUID player, Location at, long now) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(at, "at");
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        prune(now);
        deaths.add(new DeathMark(player, world.getUID(), at.getX(), at.getY(), at.getZ(), now));
    }

    /**
     * Whether any death is currently inside its window.
     *
     * <p>Exists so {@link ItemProgressionListener#onItemSpawn} can answer the common case without
     * calling {@code Entity#getLocation()}, which allocates. That handler runs for every item
     * entity created anywhere on the server — mob farms and block breaks included — while this is
     * false except in the second after somebody dies, so the allocation would be pure waste on the
     * overwhelming majority of calls.
     */
    public boolean hasPendingDeaths() {
        return !deaths.isEmpty();
    }

    /**
     * The player whose death produced an item spawning here, if any.
     *
     * <p>Matching is {@link ItemGateRules#withinDeathWindow}, which is where the bounds and the
     * failure mode this leaves are argued.
     */
    public Optional<UUID> claim(Location at, long now) {
        Objects.requireNonNull(at, "at");
        World world = at.getWorld();
        if (world == null || deaths.isEmpty()) {
            return Optional.empty();
        }
        UUID worldId = world.getUID();
        Iterator<DeathMark> marks = deaths.iterator();
        while (marks.hasNext()) {
            DeathMark mark = marks.next();
            if (now - mark.at() > DEATH_WINDOW_MILLIS) {
                marks.remove();
                continue;
            }
            if (!worldId.equals(mark.world())) {
                continue;
            }
            if (ItemGateRules.withinDeathWindow(at.getX() - mark.x(), at.getY() - mark.y(),
                    at.getZ() - mark.z(), now - mark.at(),
                    DEATH_RADIUS_BLOCKS, DEATH_WINDOW_MILLIS)) {
                return Optional.of(mark.player());
            }
        }
        return Optional.empty();
    }

    /** Drops marks whose window has closed. Called on every record and every claim. */
    private void prune(long now) {
        deaths.removeIf(mark -> now - mark.at() > DEATH_WINDOW_MILLIS);
    }
}
