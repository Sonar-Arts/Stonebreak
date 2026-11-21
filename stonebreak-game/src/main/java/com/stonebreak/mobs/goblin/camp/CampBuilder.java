package com.stonebreak.mobs.goblin.camp;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.palisade.PalisadeState;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What a camp builds as it grows, one piece at a time: a bedroll for every goblin once it is
 * {@value #BEDROLL_POPULATION} strong, and spiked walls to keep enemies out that grow with it — a
 * wall across the mouth at {@value #WALL_POPULATION}, a meat rack by the fire at
 * {@value #RACK_POPULATION}, a second wall further out at {@value #SECOND_ROW_POPULATION}, and a
 * fenced yard outside the mouth at {@value #YARD_POPULATION}. Every wall leans its points out, away
 * from the camp, and leaves a one-block gap on the camp's walk out to the sky, which nothing is
 * ever built across.
 *
 * <p>A piece that cannot go anywhere is skipped, never waited on: a cave too cramped for every
 * bedroll still gets its walls.
 *
 * <p>Pure planning over {@link CampTerrain}: {@link #survey} says which block goes where next and
 * which of the camp's pieces already stand; the registry places them.
 */
public final class CampBuilder {

    /** Goblins before a camp lays out bedrolls. */
    public static final int BEDROLL_POPULATION = 4;
    /** Goblins before a camp walls its mouth. */
    public static final int WALL_POPULATION = 6;
    /** Goblins before a camp puts up its meat rack. */
    public static final int RACK_POPULATION = 8;
    /** Goblins before a camp adds a second wall in front of the first. */
    public static final int SECOND_ROW_POPULATION = 9;
    /** Goblins before a camp fences a yard outside its mouth. */
    public static final int YARD_POPULATION = 12;
    /** How far from the fire the camp lays bedrolls and its rack. */
    static final int RADIUS = 4;
    /** Stakes either side of a wall's gap, at most: a nine-wide wall. */
    static final int WALL_REACH = 4;
    /** How far out from the mouth the second wall stands (one clear row between, so the two stay apart). */
    static final int SECOND_ROW_OUT = 2;
    /** How far out from the mouth the yard's front fence stands. */
    static final int YARD_DEPTH = 4;
    /** How far a stake may sit above or below its neighbour as a wall follows the ground. */
    private static final int[] STAKE_HEIGHTS = {0, 1, -1, 2, -2, 3};

    /** A block to place, with the state to write on it ({@code null}: none). */
    public record Placement(Vector3i cell, BlockType block, String state) {
        Placement(Vector3i cell, BlockType block) {
            this(cell, block, null);
        }
    }

    /**
     * What the camp has and wants: {@code next} — the piece to build now, or {@code null} when it
     * has all it needs for its size — and {@code standing}, the camp's pieces already in the world
     * (bedrolls and rack round the fire, stakes where its walls go).
     */
    public record Survey(Placement next, List<Vector3i> standing) {
    }

    private CampBuilder() {
    }

    /** The next piece the camp at {@code fire} should build, or {@code null}. See {@link #survey}. */
    public static Placement next(CampTerrain terrain, CampWalker walker, BlockPos fire, int population,
                                 Predicate<Vector3i> reserved) {
        return survey(terrain, walker, fire, population, reserved).next();
    }

    /**
     * Surveys the camp at {@code fire} for its {@code population}.
     *
     * @param reserved cells that must stay clear (the chest, the fire)
     */
    public static Survey survey(CampTerrain terrain, CampWalker walker, BlockPos fire, int population,
                                Predicate<Vector3i> reserved) {
        Vector3i hearth = new Vector3i(fire.x(), fire.y(), fire.z());
        List<Vector3i> standing = new ArrayList<>();
        collect(terrain, hearth, BlockType.BEDROLL, standing);
        collect(terrain, hearth, BlockType.MEAT_RACK, standing);
        if (population < BEDROLL_POPULATION) {
            return new Survey(null, standing);
        }
        List<Vector3i> walkway = CampSiteFinder.pathToSky(walker, terrain, standingBeside(terrain, hearth));
        Set<Vector3i> keepClear = new HashSet<>();
        Set<Long> openColumns = new HashSet<>();
        if (walkway != null) {
            for (Vector3i c : walkway) {
                keepClear.add(c);
                keepClear.add(new Vector3i(c.x, c.y + 1, c.z));
                openColumns.add(column(c.x, c.z));
            }
        }
        Placement next = null;
        if (count(terrain, hearth, BlockType.BEDROLL) < population) {
            next = floorSpot(terrain, hearth, 2, keepClear, reserved, BlockType.BEDROLL);
        }
        Walls walls = walkway != null && walkway.size() >= 2
                ? new Walls(terrain, walkway, openColumns, keepClear, reserved, standing) : null;
        if (walls != null && population >= WALL_POPULATION) {
            next = first(next, walls.row(0));
        }
        if (population >= RACK_POPULATION && count(terrain, hearth, BlockType.MEAT_RACK) == 0) {
            next = first(next, floorSpot(terrain, hearth, 1, keepClear, reserved, BlockType.MEAT_RACK));
        }
        if (walls != null && population >= SECOND_ROW_POPULATION) {
            next = first(next, walls.row(SECOND_ROW_OUT));
        }
        if (walls != null && population >= YARD_POPULATION) {
            next = first(next, walls.yard());
        }
        return new Survey(next, standing);
    }

    private static Placement first(Placement already, Placement candidate) {
        return already != null ? already : candidate;
    }

    /**
     * The camp's spiked walls, planned off its walk out: {@code out} is the walk's first cell under
     * open sky, {@code (dx, dz)} the way out along the axis it leaves by, and {@code (px, pz)}
     * across it. Each stake found standing is added to {@code standing}; each row hands back the
     * first stake it still lacks.
     */
    private static final class Walls {
        private final CampTerrain terrain;
        private final Set<Long> openColumns;
        private final Set<Vector3i> keepClear;
        private final Predicate<Vector3i> reserved;
        private final List<Vector3i> standing;
        private final Vector3i out;
        private final int dx;
        private final int dz;
        private final int px;
        private final int pz;

        Walls(CampTerrain terrain, List<Vector3i> walkway, Set<Long> walkColumns, Set<Vector3i> keepClear,
              Predicate<Vector3i> reserved, List<Vector3i> standing) {
            this.terrain = terrain;
            this.keepClear = keepClear;
            this.reserved = reserved;
            this.standing = standing;
            this.out = walkway.get(walkway.size() - 1);
            int[] axis = outwardAxis(walkway);
            this.dx = axis[0];
            this.dz = axis[1];
            this.px = -dz;
            this.pz = dx;
            // The gap: the walk itself, and straight on out through every wall it will ever have.
            this.openColumns = new HashSet<>(walkColumns);
            for (int k = 0; k <= YARD_DEPTH; k++) {
                openColumns.add(column(out.x + dx * k, out.z + dz * k));
            }
        }

        /**
         * A wall across the way out, {@code k} blocks beyond the mouth: stakes either side of the
         * gap, out to {@link #WALL_REACH}, each side stopping where it meets rock — wall to wall
         * across a cave mouth.
         */
        Placement row(int k) {
            int gapX = out.x + dx * k;
            int gapZ = out.z + dz * k;
            int gapY = groundNear(gapX, out.y, gapZ);
            String lean = PalisadeState.leaningToward(dx, dz).toStateString();
            Placement next = null;
            for (int side : new int[] {1, -1}) {
                int refY = gapY;
                for (int i = 1; i <= WALL_REACH; i++) {
                    Stake s = stake(gapX + px * side * i, refY, gapZ + pz * side * i, lean);
                    if (s == null) {
                        break; // the rock wall, a drop or water: the row ends here
                    }
                    refY = s.cell.y;
                    next = first(next, s.placement);
                }
            }
            return next;
        }

        /**
         * A yard outside the mouth: a fence down each side, leaning out sideways, and one across
         * the front at {@link #YARD_DEPTH} with the gap in it.
         */
        Placement yard() {
            Placement next = null;
            for (int side : new int[] {1, -1}) {
                String lean = PalisadeState.leaningToward(px * side, pz * side).toStateString();
                int refY = out.y;
                for (int k = 1; k < YARD_DEPTH; k++) {
                    Stake s = stake(out.x + dx * k + px * side * WALL_REACH, refY,
                            out.z + dz * k + pz * side * WALL_REACH, lean);
                    if (s != null) {
                        refY = s.cell.y;
                        next = first(next, s.placement);
                    }
                }
            }
            return first(next, row(YARD_DEPTH));
        }

        /** Where a column's ground is near {@code refY}: the standable height, or {@code refY} itself. */
        private int groundNear(int x, int refY, int z) {
            for (int dy : STAKE_HEIGHTS) {
                if (terrain.standable(x, refY + dy, z)) {
                    return refY + dy;
                }
            }
            return refY;
        }

        /**
         * The stake for column {@code (x, z)} near {@code refY}: one already standing there (no
         * placement — never a second stacked on top), or the ground to put one on, or
         * {@code null} when the column takes none.
         */
        private Stake stake(int x, int refY, int z, String lean) {
            if (openColumns.contains(column(x, z))) {
                return null;
            }
            for (int dy : STAKE_HEIGHTS) {
                Vector3i c = new Vector3i(x, refY + dy, z);
                if (terrain.block(c.x, c.y, c.z) == BlockType.PALISADE) {
                    standing.add(c);
                    return new Stake(c, null);
                }
            }
            for (int dy : STAKE_HEIGHTS) {
                Vector3i c = new Vector3i(x, refY + dy, z);
                if (empty(terrain, c) && terrain.standable(c.x, c.y, c.z) && !keepClear.contains(c) && !reserved.test(c)) {
                    return new Stake(c, new Placement(c, BlockType.PALISADE, lean));
                }
            }
            return null;
        }
    }

    private record Stake(Vector3i cell, Placement placement) {
    }

    /** Where to stand by the fire to start the walk out: the fire's own cell is not walkable. */
    static Vector3i standingBeside(CampTerrain terrain, Vector3i hearth) {
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int dy = 0; dy >= -1; dy--) {
                if (terrain.standable(hearth.x + d[0], hearth.y + dy, hearth.z + d[1])) {
                    return new Vector3i(hearth.x + d[0], hearth.y + dy, hearth.z + d[1]);
                }
            }
        }
        return hearth;
    }

    /**
     * The way out of the camp along one axis: the walk's last step, or — when that step is a
     * diagonal — the axis it moves furthest along.
     */
    static int[] outwardAxis(List<Vector3i> walkway) {
        Vector3i out = walkway.get(walkway.size() - 1);
        Vector3i in = walkway.get(walkway.size() - 2);
        int ddx = out.x - in.x;
        int ddz = out.z - in.z;
        return Math.abs(ddx) >= Math.abs(ddz) ? new int[] {Integer.signum(ddx), 0} : new int[] {0, Integer.signum(ddz)};
    }

    /** The lean that points a spiked wall out along the walk's last step, away from the camp. */
    static PalisadeState outward(List<Vector3i> walkway) {
        int[] axis = outwardAxis(walkway);
        return PalisadeState.leaningToward(axis[0], axis[1]);
    }

    /**
     * The nearest floor cell round the fire, at least {@code minDistance} from it: under the roof
     * if there is room there, out in the open beside the camp if not, or {@code null}.
     */
    private static Placement floorSpot(CampTerrain terrain, Vector3i hearth, int minDistance,
                                       Set<Vector3i> keepClear, Predicate<Vector3i> reserved, BlockType block) {
        for (boolean roofedOnly : new boolean[] {true, false}) {
            for (int r = minDistance; r <= RADIUS; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        for (int dy = 0; dy >= -1; dy--) {
                            Vector3i c = new Vector3i(hearth.x + dx, hearth.y + dy, hearth.z + dz);
                            if (empty(terrain, c) && terrain.standable(c.x, c.y, c.z)
                                    && (!roofedOnly || terrain.roofed(c.x, c.y, c.z))
                                    && !keepClear.contains(c) && !reserved.test(c)) {
                                return new Placement(c, block);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Plain air: nothing built there yet (a bedroll is open, but taken). */
    private static boolean empty(CampTerrain terrain, Vector3i c) {
        return terrain.block(c.x, c.y, c.z) == BlockType.AIR;
    }

    private static long column(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** How many {@code block}s stand round the fire. */
    static int count(CampTerrain terrain, Vector3i hearth, BlockType block) {
        List<Vector3i> found = new ArrayList<>();
        collect(terrain, hearth, block, found);
        return found.size();
    }

    /** Every {@code block} standing round the fire, into {@code out}. */
    private static void collect(CampTerrain terrain, Vector3i hearth, BlockType block, List<Vector3i> out) {
        for (int dx = -RADIUS - 1; dx <= RADIUS + 1; dx++) {
            for (int dz = -RADIUS - 1; dz <= RADIUS + 1; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    if (terrain.block(hearth.x + dx, hearth.y + dy, hearth.z + dz) == block) {
                        out.add(new Vector3i(hearth.x + dx, hearth.y + dy, hearth.z + dz));
                    }
                }
            }
        }
    }
}
