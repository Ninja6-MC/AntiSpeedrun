package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecondaryDragonTest {

    @Test
    void onlyHoverNeedsAnActivePhase() {
        assertTrue(SecondaryDragonRules.needsFightingPhase("HOVER"));
        assertFalse(SecondaryDragonRules.needsFightingPhase("CIRCLING"));
        assertFalse(SecondaryDragonRules.needsFightingPhase("DYING"));
    }

    @Test
    void barsFollowVanillasIslandRangeAndHealth() {
        assertTrue(SecondaryDragonRules.seesBossBars(true, false, 0, 128, 192));
        assertFalse(SecondaryDragonRules.seesBossBars(true, false, 0, 128, 193));
        assertFalse(SecondaryDragonRules.seesBossBars(false, false, 0, 128, 0));
        assertFalse(SecondaryDragonRules.seesBossBars(true, true, 0, 128, 0));
        assertEquals(0.5F, SecondaryDragonRules.progress(100, 200));
        assertEquals(1.0F, SecondaryDragonRules.progress(300, 200));
        assertEquals(0.0F, SecondaryDragonRules.progress(0, 200));
    }

    @Test
    void deathAndWorldUnloadRemoveBarsFromEveryViewer() {
        UUID world = UUID.randomUUID();
        UUID dragon = UUID.randomUUID();
        SecondaryBarBoard board = new SecondaryBarBoard();
        List<String> events = new ArrayList<>();
        ViewerBars<String> viewer = new ViewerBars<>(new ViewerBars.Display<>() {
            @Override public String create(float progress) { return "bar"; }
            @Override public void show(String bar) { events.add("show"); }
            @Override public void fill(String bar, float progress) { events.add("fill:" + progress); }
            @Override public void hide(String bar) { events.add("hide"); }
        });

        board.publish(world, dragon, 1.0F);
        viewer.sync(board.snapshot(world));
        board.publish(world, dragon, 0.5F);
        viewer.sync(board.snapshot(world));
        board.withdraw(world, dragon);
        viewer.sync(board.snapshot(world));
        assertEquals(List.of("show", "fill:0.5", "hide"), events);
        assertEquals(0, viewer.size());

        board.publish(world, dragon, 1.0F);
        viewer.sync(board.snapshot(world));
        board.forgetWorld(world);
        viewer.sync(board.snapshot(world));
        assertEquals(List.of("show", "fill:0.5", "hide", "show", "hide"), events);
        assertEquals(Map.of(), board.snapshot(world));
    }
}
