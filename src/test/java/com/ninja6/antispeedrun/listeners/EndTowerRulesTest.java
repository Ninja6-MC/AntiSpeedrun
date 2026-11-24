package com.ninja6.antispeedrun.listeners;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndTowerRulesTest {

    /** A pillar whose blocks are a set of filled positions, relative to the centre. */
    private static final class FakePillar implements EndTowerRules.Pillar {

        final int height;
        final Set<List<Integer>> filled = new HashSet<>();
        boolean player;
        boolean crystal;

        FakePillar(int height) {
            this.height = height;
        }

        /** A whole vanilla pillar top: bedrock, crystal and, if caged, every bar. */
        static FakePillar whole(int height) {
            FakePillar pillar = new FakePillar(height);
            pillar.crystal = true;
            pillar.fill(0, height, 0);
            pillar.fill(0, height + 1, 0); // the crystal's fire
            if (EndTowerRules.caged(height)) {
                for (EndTowerRules.Bar bar : EndTowerRules.cageBars()) {
                    pillar.fill(bar.dx(), height + bar.dy(), bar.dz());
                }
            }
            return pillar;
        }

        void fill(int dx, int y, int dz) {
            filled.add(List.of(dx, y, dz));
        }

        @Override
        public boolean bedrockAt(int y) {
            return height >= 0 && y == height;
        }

        @Override
        public boolean playerInside(int height) {
            return player;
        }

        @Override
        public boolean crystalPresent(int height) {
            return crystal;
        }

        @Override
        public boolean crystalSpaceClear(int height) {
            // Fire counts as clear; the fake marks fire as filled only at height + 1.
            return !filled.contains(List.of(0, height + 2, 0)) && !playerBlockAtBase;
        }

        boolean playerBlockAtBase;

        @Override
        public boolean empty(int dx, int y, int dz) {
            return !filled.contains(List.of(dx, y, dz));
        }
    }

    @Test
    @DisplayName("pillar centres match vanilla's ring of ten")
    void centres() {
        List<EndTowerRules.Centre> expected = List.of(
                new EndTowerRules.Centre(42, 0), new EndTowerRules.Centre(33, 24),
                new EndTowerRules.Centre(12, 39), new EndTowerRules.Centre(-13, 39),
                new EndTowerRules.Centre(-34, 24), new EndTowerRules.Centre(-42, -1),
                new EndTowerRules.Centre(-34, -25), new EndTowerRules.Centre(-13, -40),
                new EndTowerRules.Centre(12, -40), new EndTowerRules.Centre(33, -25));
        assertEquals(expected, EndTowerRules.pillarCentres());
        assertEquals(2, new EndTowerRules.Centre(42, 0).chunkX());
        assertEquals(-1, new EndTowerRules.Centre(-13, -1).chunkZ());
    }

    @Test
    @DisplayName("only the pillars 79 and 82 blocks tall are caged")
    void cagedHeights() {
        int[] heights = EndTowerRules.pillarHeights();
        assertEquals(76, heights[0]);
        assertEquals(103, heights[9]);
        int caged = 0;
        for (int height : heights) {
            if (EndTowerRules.caged(height)) {
                caged++;
                assertTrue(height == 79 || height == 82, String.valueOf(height));
            }
        }
        assertEquals(2, caged);
        assertFalse(EndTowerRules.caged(80));
        assertFalse(EndTowerRules.caged(73));
    }

    @Test
    @DisplayName("the cage is vanilla's shell: four walls and a roof, with vanilla's connections")
    void cageShape() {
        List<EndTowerRules.Bar> bars = EndTowerRules.cageBars();
        // 16 wall columns three high, then a full 5 by 5 roof.
        assertEquals(16 * 3 + 25, bars.size());
        Set<List<Integer>> positions = new HashSet<>();
        for (EndTowerRules.Bar bar : bars) {
            assertTrue(positions.add(List.of(bar.dx(), bar.dy(), bar.dz())), "duplicate " + bar);
            boolean wall = Math.abs(bar.dx()) == 2 || Math.abs(bar.dz()) == 2;
            assertTrue(wall || bar.dy() == 3, "interior bar " + bar);
            assertTrue(bar.dy() >= 0 && bar.dy() <= 3);
        }
        assertFalse(positions.contains(List.of(0, 0, 0)), "no bar replaces the bedrock");
        assertFalse(positions.contains(List.of(0, 1, 0)), "no bar where the crystal stands");

        assertEquals(new EndTowerRules.Bar(-2, 0, -2, false, true, true, false), bar(bars, -2, 0, -2));
        assertEquals(new EndTowerRules.Bar(0, 0, -2, false, false, true, true), bar(bars, 0, 0, -2));
        assertEquals(new EndTowerRules.Bar(2, 1, 0, true, true, false, false), bar(bars, 2, 1, 0));
        assertEquals(new EndTowerRules.Bar(0, 3, 0, true, true, true, true), bar(bars, 0, 3, 0));
    }

    private static EndTowerRules.Bar bar(List<EndTowerRules.Bar> bars, int dx, int dy, int dz) {
        return bars.stream().filter(b -> b.dx() == dx && b.dy() == dy && b.dz() == dz).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the occupied space is the cage's box, so a player on the roof is outside it")
    void occupiedSpace() {
        EndTowerRules.Bounds space = EndTowerRules.occupiedSpace(new EndTowerRules.Centre(12, -40), 82);
        assertEquals(new EndTowerRules.Bounds(10, 82, -42, 15, 86, -37), space);
    }

    @Test
    @DisplayName("a party that destroyed every crystal gets each one back, and caged pillars keep their cages")
    void everyCrystalDestroyed() {
        for (int height : EndTowerRules.pillarHeights()) {
            FakePillar pillar = FakePillar.whole(height);
            pillar.crystal = false;
            EndTowerRules.Plan plan = EndTowerRules.plan(pillar);
            assertEquals(EndTowerRules.Outcome.RESTORE, plan.outcome());
            assertEquals(height, plan.height());
            assertTrue(plan.crystal());
            assertTrue(plan.bars().isEmpty(), "a whole cage gets no bar");
            assertTrue(plan.settled());
        }
    }

    @Test
    @DisplayName("a broken cage is rebuilt into air only, and an uncaged pillar never gets bars")
    void brokenCage() {
        FakePillar caged = new FakePillar(79);
        caged.fill(0, 79, 0);
        caged.fill(-2, 80, 0); // a player block where a bar stood
        EndTowerRules.Plan plan = EndTowerRules.plan(caged);
        assertEquals(EndTowerRules.Outcome.RESTORE, plan.outcome());
        assertTrue(plan.crystal());
        assertEquals(EndTowerRules.cageBars().size() - 1, plan.bars().size());
        assertTrue(plan.bars().stream().noneMatch(b -> b.dx() == -2 && b.dy() == 1 && b.dz() == 0));

        FakePillar open = new FakePillar(100);
        EndTowerRules.Plan openPlan = EndTowerRules.plan(open);
        assertTrue(openPlan.crystal());
        assertTrue(openPlan.bars().isEmpty());
    }

    @Test
    @DisplayName("a player inside the cage space defers the whole pillar, with nothing placed")
    void occupied() {
        for (int height : new int[] {79, 100}) {
            FakePillar pillar = new FakePillar(height);
            pillar.player = true;
            EndTowerRules.Plan plan = EndTowerRules.plan(pillar);
            assertEquals(EndTowerRules.Outcome.OCCUPIED, plan.outcome());
            assertFalse(plan.crystal());
            assertTrue(plan.bars().isEmpty());
            assertFalse(plan.settled(), "asked again later");

            pillar.player = false;
            assertEquals(EndTowerRules.Outcome.RESTORE, EndTowerRules.plan(pillar).outcome());
        }
    }

    @Test
    @DisplayName("a player block in the crystal's space keeps the block and skips the crystal")
    void playerBlockOnTop() {
        FakePillar pillar = FakePillar.whole(91);
        pillar.crystal = false;
        pillar.playerBlockAtBase = true;
        EndTowerRules.Plan plan = EndTowerRules.plan(pillar);
        assertEquals(EndTowerRules.Outcome.INTACT, plan.outcome());
        assertFalse(plan.crystal());
    }

    @Test
    @DisplayName("a retried restore after a complete one places nothing, so no crystal is doubled")
    void retryIsIdempotent() {
        FakePillar pillar = FakePillar.whole(82);
        EndTowerRules.Plan plan = EndTowerRules.plan(pillar);
        assertEquals(EndTowerRules.Outcome.INTACT, plan.outcome());
        assertFalse(plan.crystal());
        assertTrue(plan.bars().isEmpty());
    }

    @Test
    @DisplayName("no bedrock at any pillar height means no pillar and nothing placed")
    void noPillar() {
        EndTowerRules.Plan plan = EndTowerRules.plan(new FakePillar(-1));
        assertEquals(EndTowerRules.Outcome.NO_PILLAR, plan.outcome());
        assertEquals(-1, plan.height());
        assertTrue(plan.settled());
        assertEquals(-1, EndTowerRules.pillarHeight(y -> y == 80));
        assertEquals(103, EndTowerRules.pillarHeight(y -> y == 103));
    }
}
