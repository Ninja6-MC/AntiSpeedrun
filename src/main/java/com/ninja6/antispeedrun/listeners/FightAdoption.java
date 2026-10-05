package com.ninja6.antispeedrun.listeners;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps vanilla's dragon fight from ending on a secondary's death (#165).
 *
 * <h2>What vanilla does</h2>
 *
 * The End's dragon fight remembers its dragon by UUID. When that dragon has not ticked for 1,200
 * ticks, which is what an unloaded primary looks like, the fight takes the first Ender Dragon it can
 * find in the End as its dragon instead. During a multi-dragon fight that is a tagged secondary, and
 * a secondary dies normally, so its death would run the victory sequence (exit portal, egg, gateway)
 * while the primary and any other secondaries are still alive. Observed on Folia 26.2 build 7: the
 * fight's boss bar switched to a damaged secondary's health about 1,300 ticks after the primary was
 * moved into an unloaded chunk, and that secondary's death opened the exit portal and placed the egg.
 *
 * <h2>What the plugin does about it</h2>
 *
 * There is no API to point the fight back at the primary, so the plugin accepts the fight's choice
 * and moves the labels instead. Once a second, on the battle's region, {@link #observe} is shown the
 * dragon the fight tracks. While that is the untagged primary it is remembered. When it turns out to
 * be a tagged secondary, the fight has adopted it. The adopted dragon leaves the roster, and the
 * listener removes its tag, so it is held like a primary until every secondary has died. The primary
 * it replaced joins the roster at once, while it is still unloaded: it is alive, saved in its chunk,
 * and until it dies the adopted dragon must not, or its death would end the fight with a dragon
 * still out there. When that chunk loads again {@link #takeDemoted} tells the listener to tag it as a
 * secondary; tracking it a second time then changes nothing. If it is removed before that, untagged,
 * {@link #release} takes it off the roster. Every rule that keeps the victory sequence for the last
 * dragon then holds again, for the dragon vanilla actually tracks.
 *
 * <p>The demotion list and the roster are memory only. After a restart the old primary is not
 * counted until its chunk loads, as for any secondary (see {@link SecondaryRoster}), and it loads
 * untagged and untracked: vanilla ignores its death, and the plugin holds it like any other untagged
 * dragon, so it can neither end the fight nor be ended early.
 */
public final class FightAdoption {

    /** What one look at the fight's dragon found. */
    public record Outcome(boolean adopted, UUID demoted) {

        static final Outcome NONE = new Outcome(false, null);
    }

    /** The untagged dragon the fight last tracked. Read and written on the battle's region only. */
    private volatile UUID primary;

    /** Former primaries to tag when their chunk loads. Written on the battle's region, read on any. */
    private final Set<UUID> demoted = ConcurrentHashMap.newKeySet();

    /**
     * On the battle's region: the dragon the fight tracks right now. On an adoption the roster loses
     * the adopted dragon and gains the primary it replaced, loaded or not.
     *
     * @param tracked   that dragon's UUID, or {@code null} when it is not loaded where it can be read
     * @param secondary whether it carries the secondary tag
     * @param roster    the world's living secondaries
     * @return whether the fight has adopted a secondary, and which primary, if any, it replaced
     */
    public Outcome observe(UUID tracked, boolean secondary, SecondaryRoster roster) {
        Objects.requireNonNull(roster, "roster");
        if (tracked == null) {
            return Outcome.NONE;
        }
        if (!secondary) {
            primary = tracked;
            return Outcome.NONE;
        }
        UUID replaced = primary != null && !primary.equals(tracked) ? primary : null;
        roster.forget(tracked);
        if (replaced != null) {
            demoted.add(replaced);
            roster.track(replaced);
        }
        primary = tracked;
        return new Outcome(true, replaced);
    }

    /**
     * On the region removing {@code dragon}, untagged, for good: if it was a replaced primary still
     * waiting for its chunk, it leaves the roster.
     *
     * @return {@code true} if it was one
     */
    public boolean release(UUID dragon, SecondaryRoster roster) {
        Objects.requireNonNull(roster, "roster");
        if (!takeDemoted(dragon)) {
            return false;
        }
        roster.forget(dragon);
        return true;
    }

    /**
     * On the region loading {@code dragon}: whether it is a primary the fight replaced, which is
     * then no longer pending.
     */
    public boolean takeDemoted(UUID dragon) {
        return demoted.remove(Objects.requireNonNull(dragon, "dragon"));
    }
}
