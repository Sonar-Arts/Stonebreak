package com.openmason.engine.wayfind.voxel;

import com.openmason.engine.util.LongIntHashMap;
import com.openmason.engine.wayfind.SearchDomain;

/**
 * Movement rules for an agent that flies: the {@link SearchDomain} a bird is routed through.
 *
 * <p>Where {@link GroundNavDomain} collapses a tall column to the one or two cells that can be
 * stood in, flight is a genuine 3D problem — there is no surface to hang nodes off, and a route
 * over a ridge and a route around it are both legitimate answers. The search is kept affordable by
 * coarsening space instead: a node is a cube of {@link AirNavProfile#cellSize} blocks, so a
 * hundred-block corridor is a couple of dozen nodes rather than a hundred, and the 26 directions of
 * a 3D grid cost about what eight cost a walker.
 *
 * <p><b>A cell is flyable only when every block in it is empty.</b> That single rule is also the
 * clearance model — see {@link AirNavProfile} — and it is why this domain needs no body dimensions.
 * Water counts as blocking: a flight corridor is air, and water is a surface a bird lands on rather
 * than a medium it routes through.
 *
 * <p>Diagonals additionally require the axis-aligned cells they pass between to be flyable, so a
 * route never squeezes through the edge or corner where two solids meet.
 *
 * <p>Unlike the ground domain, unloaded terrain is passable at a premium when the profile allows it.
 * A walker refusing unknown ground is refusing to fall off a cliff it cannot see; a flyer at cruise
 * altitude is over open sky nine times in ten, and refusing would wall a migrating flock in at the
 * boundary of the loaded world — the one place it is always heading.
 *
 * <p>One instance serves one search: it memoises cell classification, which is what keeps the block
 * probes to one per cell no matter how many of its 26 neighbours ask about it. Construct it per
 * query.
 */
public final class AirNavDomain implements SearchDomain {

    /** Returned by {@link #snapToFlyable} when no flyable cell exists in range. */
    public static final long NO_NODE = Long.MIN_VALUE;

    /** How finely {@link #stringPull} samples a shortcut, in cells. */
    private static final float LINE_SAMPLE_STEP = 0.25f;

    // Cell classification, memoised per search.
    private static final int UNCLASSIFIED = 0;
    private static final int FLYABLE = 1;
    private static final int UNKNOWN = 2;
    private static final int BLOCKED = 3;

    private static final int DIRECTIONS = 26;
    private static final int[] DX = new int[DIRECTIONS];
    private static final int[] DY = new int[DIRECTIONS];
    private static final int[] DZ = new int[DIRECTIONS];
    private static final float[] STEP_LENGTH = new float[DIRECTIONS];

    static {
        int index = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    DX[index] = dx;
                    DY[index] = dy;
                    DZ[index] = dz;
                    STEP_LENGTH[index] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    index++;
                }
            }
        }
    }

    private final NavVolume volume;
    private final AirNavProfile profile;
    private final long goalCell;
    private final float goalRadiusCells;
    /** Cruise altitude expressed in cells, so the altitude term is a plain cell-space difference. */
    private final float cruiseCellY;

    private final LongIntHashMap classified = new LongIntHashMap(2048);

    /**
     * @param goalCell     the cell to reach; build it with {@link #cellOf(AirNavProfile, int, int, int)}
     * @param goalRadius   how close a cell centre must get to the goal cell's centre to count as
     *                     arrival, in blocks. Zero demands the exact cell
     */
    public AirNavDomain(NavVolume volume, AirNavProfile profile, long goalCell, float goalRadius) {
        this.volume = volume;
        this.profile = profile;
        this.goalCell = goalCell;
        this.goalRadiusCells = Math.max(0.0f, goalRadius) / profile.cellSize();
        this.cruiseCellY = profile.cruiseY() / profile.cellSize();
    }

    /** The cell containing a world block position, as a node key. */
    public static long cellOf(AirNavProfile profile, int blockX, int blockY, int blockZ) {
        int size = profile.cellSize();
        return NavNodes.pack(Math.floorDiv(blockX, size),
                Math.floorDiv(blockY, size),
                Math.floorDiv(blockZ, size));
    }

    /** Whether a world block position can be expressed as a cell of this profile at all. */
    public static boolean cellInRange(AirNavProfile profile, int blockX, int blockY, int blockZ) {
        int size = profile.cellSize();
        return NavNodes.inRange(Math.floorDiv(blockX, size),
                Math.floorDiv(blockY, size),
                Math.floorDiv(blockZ, size));
    }

    // ── SearchDomain ─────────────────────────────────────────────────────────

    @Override
    public int successors(long node, long[] outNodes, float[] outCosts) {
        int x = NavNodes.x(node);
        int y = NavNodes.y(node);
        int z = NavNodes.z(node);

        int count = 0;
        for (int dir = 0; dir < DIRECTIONS; dir++) {
            int dx = DX[dir];
            int dy = DY[dir];
            int dz = DZ[dir];
            int nx = x + dx;
            int ny = y + dy;
            int nz = z + dz;
            if (!NavNodes.inRange(nx, ny, nz)) {
                continue;
            }

            int neighbour = classify(nx, ny, nz);
            if (!passable(neighbour)) {
                continue;
            }
            if (!cornerClear(x, y, z, dx, dy, dz)) {
                continue;
            }

            float cost = STEP_LENGTH[dir];
            if (dy > 0) {
                cost += profile.climbCost();
            } else if (dy < 0) {
                cost += profile.descendCost();
            }
            cost += profile.altitudeCost() * Math.abs(ny + 0.5f - cruiseCellY);
            if (neighbour == UNKNOWN) {
                cost += profile.unknownCost();
            }

            outNodes[count] = NavNodes.pack(nx, ny, nz);
            outCosts[count] = cost;
            count++;
        }
        return count;
    }

    @Override
    public float heuristic(long node) {
        // Straight-line distance in cells. Every move costs at least its own geometric length and
        // every surcharge is non-negative, so this never over-estimates.
        float dx = NavNodes.x(node) - NavNodes.x(goalCell);
        float dy = NavNodes.y(node) - NavNodes.y(goalCell);
        float dz = NavNodes.z(node) - NavNodes.z(goalCell);
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public boolean isGoal(long node) {
        if (node == goalCell) {
            return true;
        }
        float dx = NavNodes.x(node) - NavNodes.x(goalCell);
        float dy = NavNodes.y(node) - NavNodes.y(goalCell);
        float dz = NavNodes.z(node) - NavNodes.z(goalCell);
        return dx * dx + dy * dy + dz * dz <= goalRadiusCells * goalRadiusCells;
    }

    @Override
    public int maxSuccessors() {
        return DIRECTIONS;
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    /** Whether a cell is clear enough to fly through, unknown air included when allowed. */
    public boolean isFlyable(long node) {
        return passable(classify(NavNodes.x(node), NavNodes.y(node), NavNodes.z(node)));
    }

    /** World-space centre of a cell along X. */
    public float centreX(long node) {
        return centre(NavNodes.x(node));
    }

    /** World-space centre of a cell along Y. */
    public float centreY(long node) {
        return centre(NavNodes.y(node));
    }

    /** World-space centre of a cell along Z. */
    public float centreZ(long node) {
        return centre(NavNodes.z(node));
    }

    /**
     * The flyable cell nearest to a world block position, searched outward by Chebyshev ring.
     *
     * <p>A destination is wherever a behaviour pointed, which for a bird is regularly the inside of
     * a hillside or a cell clipping a treetop. Nudging the goal onto real airspace first is what
     * lets a search finish {@code FOUND} rather than grinding out a partial every single time.
     *
     * @return the cell node, or {@link #NO_NODE} when nothing within {@code radiusCells} is flyable
     */
    public long snapToFlyable(int blockX, int blockY, int blockZ, int radiusCells) {
        if (!cellInRange(profile, blockX, blockY, blockZ)) {
            return NO_NODE;
        }
        long origin = cellOf(profile, blockX, blockY, blockZ);
        if (isFlyable(origin)) {
            return origin;
        }

        int ox = NavNodes.x(origin);
        int oy = NavNodes.y(origin);
        int oz = NavNodes.z(origin);
        for (int ring = 1; ring <= radiusCells; ring++) {
            long best = NO_NODE;
            float bestDistance = Float.MAX_VALUE;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dy = -ring; dy <= ring; dy++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        // Only the shell of this ring is new; the interior was covered already.
                        if (Math.abs(dx) != ring && Math.abs(dy) != ring && Math.abs(dz) != ring) {
                            continue;
                        }
                        int cx = ox + dx;
                        int cy = oy + dy;
                        int cz = oz + dz;
                        if (!NavNodes.inRange(cx, cy, cz) || !passable(classify(cx, cy, cz))) {
                            continue;
                        }
                        // Prefer the closest, and among equals the one nearest cruise altitude —
                        // a bird nudged off its goal should be nudged sideways, not downward.
                        float distance = dx * dx + dz * dz + 4.0f * dy * dy;
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = NavNodes.pack(cx, cy, cz);
                        }
                    }
                }
            }
            if (best != NO_NODE) {
                return best;
            }
        }
        return NO_NODE;
    }

    /**
     * Drops the interior nodes of a chain wherever the straight line between two survivors stays in
     * flyable air — the coarse-grid staircase becomes the long straight legs a bird actually flies.
     *
     * <p>Worth doing here and not in {@link PathSmoother} because it needs the domain's own
     * passability: a shortcut is only safe if every cell the line crosses is clear, which is the
     * question this class already answers and memoises. Shortcuts are refused through
     * <em>unknown</em> cells even when the search was allowed to route through them — straightening
     * a route should never trade air someone has looked at for air nobody has.
     */
    public long[] stringPull(long[] nodes) {
        if (nodes.length < 3) {
            return nodes;
        }

        long[] kept = new long[nodes.length];
        int count = 0;
        kept[count++] = nodes[0];

        int anchor = 0;
        while (anchor < nodes.length - 1) {
            int furthest = anchor + 1;
            for (int candidate = nodes.length - 1; candidate > anchor + 1; candidate--) {
                if (lineFlyable(nodes[anchor], nodes[candidate])) {
                    furthest = candidate;
                    break;
                }
            }
            kept[count++] = nodes[furthest];
            anchor = furthest;
        }

        if (count == nodes.length) {
            return nodes;
        }
        long[] trimmed = new long[count];
        System.arraycopy(kept, 0, trimmed, 0, count);
        return trimmed;
    }

    /** Whether the straight cell-space segment between two cells stays in known-clear air. */
    public boolean lineFlyable(long from, long to) {
        float x0 = NavNodes.x(from);
        float y0 = NavNodes.y(from);
        float z0 = NavNodes.z(from);
        float dx = NavNodes.x(to) - x0;
        float dy = NavNodes.y(to) - y0;
        float dz = NavNodes.z(to) - z0;

        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(length / LINE_SAMPLE_STEP));
        for (int step = 0; step <= steps; step++) {
            float t = (float) step / steps;
            int cx = Math.round(x0 + dx * t);
            int cy = Math.round(y0 + dy * t);
            int cz = Math.round(z0 + dz * t);
            if (!NavNodes.inRange(cx, cy, cz) || classify(cx, cy, cz) != FLYABLE) {
                return false;
            }
        }
        return true;
    }

    /** Cells whose contents have been probed so far — a direct read of one search's world reads. */
    public int classifiedCells() {
        return classified.size();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private float centre(int cellCoordinate) {
        return cellCoordinate * profile.cellSize() + profile.cellSize() * 0.5f;
    }

    private boolean passable(int classification) {
        return classification == FLYABLE || (classification == UNKNOWN && profile.allowUnknown());
    }

    /**
     * Refuses a diagonal that would pass through the seam where solids meet. Dropping one component
     * at a time gives the face-neighbours the line actually brushes; requiring those keeps a route
     * out of the gap between two blocks that touch only along an edge or a corner.
     */
    private boolean cornerClear(int x, int y, int z, int dx, int dy, int dz) {
        int components = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
        if (components < 2) {
            return true;
        }
        if (dx != 0 && !passable(classify(x + dx, y, z))) {
            return false;
        }
        if (dy != 0 && !passable(classify(x, y + dy, z))) {
            return false;
        }
        return dz == 0 || passable(classify(x, y, z + dz));
    }

    private int classify(int cx, int cy, int cz) {
        long key = NavNodes.pack(cx, cy, cz);
        int cached = classified.get(key, UNCLASSIFIED);
        if (cached != UNCLASSIFIED) {
            return cached;
        }
        int classification = probe(cx, cy, cz);
        classified.put(key, classification);
        return classification;
    }

    private int probe(int cx, int cy, int cz) {
        int size = profile.cellSize();
        int baseY = cy * size;
        if (baseY < profile.minY() || baseY + size - 1 > profile.maxY()) {
            return BLOCKED; // below the floor or above the ceiling this flyer will use
        }

        int baseX = cx * size;
        int baseZ = cz * size;
        boolean sawUnknown = false;
        for (int y = baseY; y < baseY + size; y++) {
            for (int x = baseX; x < baseX + size; x++) {
                for (int z = baseZ; z < baseZ + size; z++) {
                    int flags = volume.flags(x, y, z);
                    if (NavCell.isSolid(flags) || NavCell.isHazard(flags) || NavCell.isLiquid(flags)) {
                        return BLOCKED;
                    }
                    if (NavCell.isUnknown(flags)) {
                        sawUnknown = true;
                    }
                }
            }
        }
        return sawUnknown ? UNKNOWN : FLYABLE;
    }
}
