package com.ninja6.antispeedrun.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.bukkit.ExplosionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which explosions may clear placed-block entries (#212). */
class PlacedBlockListenerTest {

    @Test
    @DisplayName("only an explosion that destroys its blocks clears their entries")
    void onlyDestroyingExplosionsClear() {
        assertTrue(PlacedBlockListener.destroysBlocks(ExplosionResult.DESTROY));
        assertTrue(PlacedBlockListener.destroysBlocks(ExplosionResult.DESTROY_WITH_DECAY));
        assertFalse(PlacedBlockListener.destroysBlocks(ExplosionResult.TRIGGER_BLOCK),
                "a wind charge leaves the placed block standing");
        assertFalse(PlacedBlockListener.destroysBlocks(ExplosionResult.KEEP),
                "mobGriefing off leaves the placed block standing");
        assertFalse(PlacedBlockListener.destroysBlocks(null));
    }

    @Test
    @DisplayName("every explosion result is classified, so a new one fails here first")
    void everyResultCovered() {
        assertEquals(EnumSet.of(ExplosionResult.KEEP, ExplosionResult.DESTROY,
                ExplosionResult.DESTROY_WITH_DECAY, ExplosionResult.TRIGGER_BLOCK),
                EnumSet.allOf(ExplosionResult.class));
    }
}
