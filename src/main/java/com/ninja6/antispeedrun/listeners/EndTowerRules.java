package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Restoring the End's pillar crystals and cages when a reinforcement window spawns extra dragons
 * (#273, Task 12.3): where vanilla's pillars stand, what each one holds, and what a restore may
 * place.
 *
 * <h2>Vanilla's pillars</h2>
 *
 * {@code EndSpikeFeature} places ten pillars. Pillar {@code i} is centred on
 * {@code floor(42 * cos(2 * (-pi + pi / 10 * i)))}, {@code floor(42 * sin(...))}, whatever the
 * seed. The seed only shuffles which of ten sizes each centre gets: size {@code j} is
 * {@code 76 + 3j} blocks tall, and sizes 1 and 2 (heights 79 and 82) are caged. The top of a
 * pillar of height {@code h} is bedrock at {@code y = h}, with the crystal standing on it at
 * {@code h + 1}. The cage is the shell of a 5 by 4 by 5 box of iron bars from {@code y = h} to
 * {@code h + 3}: four walls and a roof, around the bedrock.
 *
 * <p>The seed is not needed: the bedrock block, which survival players cannot break, is looked for
 * at each centre and each of the ten heights, and the height found says whether the pillar is
 * caged.
 *
 * <h2>What a restore places</h2>
 *
 * Only what vanilla's pillars contain, and only into empty space: a crystal where none stands, on
 * the bedrock, and the cage's bars into air. Obsidian is never rebuilt and a block a player placed is
 * never replaced. A pillar with a player inside its cage space is left until the player moves; see
 * {@link #plan}.
 */
public final class EndTowerRules {

    /** How many pillars vanilla places. */
    public static final int PILLAR_COUNT = 10;

    /** The distance of each pillar's centre from {@code (0, 0)}, before flooring. */
    public static final double PILLAR_RING_RADIUS = 42.0D;

    /** The shortest pillar's height; each of the ten sizes is three blocks taller than the last. */
    public static final int LOWEST_PILLAR_HEIGHT = 76;

    /** The height step between pillar sizes. */
    public static final int PILLAR_HEIGHT_STEP = 3;

    /** The cage's half-width: bars stand two blocks either side of the centre. */
    public static final int CAGE_HALF_WIDTH = 2;

    /** The cage's height in blocks, roof included, from the bedrock's level up. */
    public static final int CAGE_HEIGHT = 4;

    private static final List<Centre> CENTRES = centres();
    private static final List<Bar> CAGE = cage();

    private EndTowerRules() {
    }

    /** The ten pillar centres, in vanilla's order. */
    public static List<Centre> pillarCentres() {
        return CENTRES;
    }

    /** The ten heights a pillar can have, lowest first. */
    public static int[] pillarHeights() {
        int[] heights = new int[PILLAR_COUNT];
        for (int size = 0; size < PILLAR_COUNT; size++) {
            heights[size] = LOWEST_PILLAR_HEIGHT + PILLAR_HEIGHT_STEP * size;
        }
        return heights;
    }

    /** Whether a pillar of {@code height} is caged: sizes 1 and 2, so heights 79 and 82. */
    public static boolean caged(int height) {
        int step = height - LOWEST_PILLAR_HEIGHT;
        if (step < 0 || step % PILLAR_HEIGHT_STEP != 0) {
            return false;
        }
        int size = step / PILLAR_HEIGHT_STEP;
        return size == 1 || size == 2;
    }

    /**
     * Every bar of a cage, as offsets from the pillar's centre and the bedrock's level, with the
     * sides each one connects to as vanilla sets them.
     */
    public static List<Bar> cageBars() {
        return CAGE;
    }

    /**
     * The pillar's height: the level of the bedrock at its centre.
     *
     * @param bedrockAt whether the centre column holds bedrock at a given y
     * @return the height, or {@code -1} if there is no pillar top at any of the ten heights
     */
    public static int pillarHeight(java.util.function.IntPredicate bedrockAt) {
        Objects.requireNonNull(bedrockAt, "bedrockAt");
        int[] heights = pillarHeights();
        for (int i = heights.length - 1; i >= 0; i--) {
            if (bedrockAt.test(heights[i])) {
                return heights[i];
            }
        }
        return -1;
    }

    /**
     * The space a restore could close a player into, as block bounds: the cage's 5 by 4 by 5 box,
     * which also holds the crystal and the fire it lights. A player standing on the roof is outside
     * it. Minimum corner inclusive, maximum corner exclusive.
     */
    public static Bounds occupiedSpace(Centre centre, int height) {
        Objects.requireNonNull(centre, "centre");
        return new Bounds(centre.x() - CAGE_HALF_WIDTH, height, centre.z() - CAGE_HALF_WIDTH,
                centre.x() + CAGE_HALF_WIDTH + 1, height + CAGE_HEIGHT, centre.z() + CAGE_HALF_WIDTH + 1);
    }

    /**
     * What to do at one pillar, decided on the region that owns it.
     *
     * <ol>
     *   <li>No bedrock at any pillar height: {@link Outcome#NO_PILLAR}, nothing is placed.</li>
     *   <li>A player in the {@link #occupiedSpace}: {@link Outcome#OCCUPIED}. Nothing is placed and
     *       the restore is asked again later, so bars never close around a player and the
     *       crystal's fire never lights under one.</li>
     *   <li>Otherwise a crystal if none stands there and its two blocks are clear, and a bar in
     *       every empty cage position of a caged pillar. {@link Outcome#INTACT} when that is
     *       nothing.</li>
     * </ol>
     *
     * A block that is not empty stays: the crystal is skipped if a player block fills its space, and
     * a bar is skipped wherever a block already stands, including a bar left from before.
     */
    public static Plan plan(Pillar pillar) {
        Objects.requireNonNull(pillar, "pillar");
        int height = pillarHeight(pillar::bedrockAt);
        if (height < 0) {
            return Plan.of(Outcome.NO_PILLAR, height);
        }
        if (pillar.playerInside(height)) {
            return Plan.of(Outcome.OCCUPIED, height);
        }
        boolean crystal = !pillar.crystalPresent(height) && pillar.crystalSpaceClear(height);
        List<Bar> bars = new ArrayList<>();
        if (caged(height)) {
            for (Bar bar : CAGE) {
                if (pillar.empty(bar.dx(), height + bar.dy(), bar.dz())) {
                    bars.add(bar);
                }
            }
        }
        if (!crystal && bars.isEmpty()) {
            return Plan.of(Outcome.INTACT, height);
        }
        return new Plan(Outcome.RESTORE, height, crystal, List.copyOf(bars));
    }

    private static List<Centre> centres() {
        List<Centre> centres = new ArrayList<>(PILLAR_COUNT);
        for (int i = 0; i < PILLAR_COUNT; i++) {
            double angle = 2.0D * (-Math.PI + 0.3141592653589793D * i);
            centres.add(new Centre((int) Math.floor(PILLAR_RING_RADIUS * Math.cos(angle)),
                    (int) Math.floor(PILLAR_RING_RADIUS * Math.sin(angle))));
        }
        return List.copyOf(centres);
    }

    /** Vanilla's {@code EndSpikeFeature} cage, bar for bar. */
    private static List<Bar> cage() {
        List<Bar> bars = new ArrayList<>();
        for (int dx = -CAGE_HALF_WIDTH; dx <= CAGE_HALF_WIDTH; dx++) {
            for (int dz = -CAGE_HALF_WIDTH; dz <= CAGE_HALF_WIDTH; dz++) {
                for (int dy = 0; dy < CAGE_HEIGHT; dy++) {
                    boolean xWall = Math.abs(dx) == CAGE_HALF_WIDTH;
                    boolean zWall = Math.abs(dz) == CAGE_HALF_WIDTH;
                    boolean roof = dy == CAGE_HEIGHT - 1;
                    if (!xWall && !zWall && !roof) {
                        continue;
                    }
                    boolean northSouth = xWall || roof;
                    boolean eastWest = zWall || roof;
                    bars.add(new Bar(dx, dy, dz,
                            northSouth && dz != -CAGE_HALF_WIDTH,
                            northSouth && dz != CAGE_HALF_WIDTH,
                            eastWest && dx != CAGE_HALF_WIDTH,
                            eastWest && dx != -CAGE_HALF_WIDTH));
                }
            }
        }
        return List.copyOf(bars);
    }

    /** A pillar's centre column. */
    public record Centre(int x, int z) {

        /** The chunk x of the centre, which names the region the pillar's restore runs on. */
        public int chunkX() {
            return x >> 4;
        }

        /** The chunk z of the centre. */
        public int chunkZ() {
            return z >> 4;
        }
    }

    /**
     * One iron bar, offset from the pillar's centre and the bedrock's level.
     *
     * @param north whether it connects north (towards negative z)
     * @param south whether it connects south
     * @param east  whether it connects east (towards positive x)
     * @param west  whether it connects west
     */
    public record Bar(int dx, int dy, int dz, boolean north, boolean south, boolean east, boolean west) {
    }

    /** A box of blocks: minimum corner inclusive, maximum exclusive. */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    /** What one pillar's restore found. */
    public enum Outcome {

        /** No pillar top at this centre. Nothing is placed and it is not asked again. */
        NO_PILLAR,

        /** A player is in the cage space. Nothing is placed; asked again later. */
        OCCUPIED,

        /** The crystal stands and the cage is whole. Nothing to place. */
        INTACT,

        /** Something is missing and will be placed. */
        RESTORE
    }

    /**
     * One pillar's restore.
     *
     * @param height  the pillar's height, or {@code -1} for {@link Outcome#NO_PILLAR}
     * @param crystal whether to spawn the crystal
     * @param bars    the bars to place
     */
    public record Plan(Outcome outcome, int height, boolean crystal, List<Bar> bars) {

        public Plan {
            Objects.requireNonNull(outcome, "outcome");
            bars = List.copyOf(bars);
        }

        static Plan of(Outcome outcome, int height) {
            return new Plan(outcome, height, false, List.of());
        }

        /** Whether the restore is finished for this window: everything except {@link Outcome#OCCUPIED}. */
        public boolean settled() {
            return outcome != Outcome.OCCUPIED;
        }
    }

    /** What the restore reads at one pillar, on the region that owns it. */
    public interface Pillar {

        /** Whether the centre column holds bedrock at {@code y}. */
        boolean bedrockAt(int y);

        /** Whether any player who is not a spectator overlaps the {@link #occupiedSpace}. */
        boolean playerInside(int height);

        /** Whether an End crystal already stands on the pillar top. */
        boolean crystalPresent(int height);

        /** Whether the crystal's two blocks above the bedrock hold only air or fire. */
        boolean crystalSpaceClear(int height);

        /** Whether the block at the centre plus {@code (dx, dz)}, at {@code y}, is air. */
        boolean empty(int dx, int y, int dz);
    }
}
