package com.stonebreak.world.leaves;

import java.util.HashSet;
import java.util.Set;

import com.openmason.engine.wayfind.SearchDomain;
import com.openmason.engine.wayfind.voxel.NavNodes;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Single-purpose {@link SearchDomain} for leaf-decay reachability: a bounded,
 * cost-limited flood over the tree network that records the foliage cells it
 * reaches and whether it touched a log.
 *
 * <p>One instance belongs to one in-flight search; it accumulates its results
 * into the state read back after {@code search} (the core only returns a path,
 * not a visited set — per-search domain state is the sanctioned way to capture
 * the reachable region, see {@code SearchDomain} javadoc).
 *
 * <p><b>Passability mirrors vanilla.</b> Only leaves and logs are traversable,
 * at cost 1 per step, and only along the six orthogonal directions. Air is a
 * wall and diagonals do not connect: a leaf that merely sits diagonal to a log,
 * with no orthogonal leaf/log chain to it, is unreachable and therefore decays —
 * exactly how Minecraft treats leaf distance. Reachability through an emptied
 * trunk shaft works because the cascade stairs through the canopy itself, and a
 * removed log's cell is still traversed as the flood's start.
 *
 * <p>With the heuristic zero, A* degenerates to uniform-cost search and
 * {@code maxCost} prunes everything beyond the reach radius — so the recorded
 * set is exactly every leaf 6-connected to the start within the radius.
 */
final class LeafReachabilityDomain implements SearchDomain {

    private final LeafWorld world;
    private final Set<Long> reachedLeaves = new HashSet<>();
    private final Set<Long> reachedLogs = new HashSet<>();

    LeafReachabilityDomain(LeafWorld world) {
        this.world = world;
    }

    /** Every leaf cell the flood reached (deduplicated), read after {@code search}. */
    Set<Long> reachedLeaves() {
        return reachedLeaves;
    }

    /** Every log cell the flood reached — the anchor set of the flooded region. */
    Set<Long> reachedLogs() {
        return reachedLogs;
    }

    /** Whether the flood entered a log cell (the start itself counts if it is a log). */
    boolean reachedLog() {
        return !reachedLogs.isEmpty();
    }

    @Override
    public int successors(long node, long[] outNodes, float[] outCosts) {
        int x = NavNodes.x(node);
        int y = NavNodes.y(node);
        int z = NavNodes.z(node);

        BlockType current = world.getBlock(x, y, z);
        if (isLeaves(current)) {
            reachedLeaves.add(node);
        } else if (isLog(current)) {
            reachedLogs.add(node);
        }

        int count = 0;
        for (int[] dir : OFFSETS) {
            int nx = x + dir[0];
            int ny = y + dir[1];
            int nz = z + dir[2];
            // Out-of-world columns are walls, not passable openings — the world
            // itself ends at y=0, so a flood must not step below it (NavNodes
            // cannot even pack a negative y).
            if (ny < 0 || ny >= WorldConfiguration.WORLD_HEIGHT) {
                continue;
            }
            BlockType block = world.getBlock(nx, ny, nz);
            if (isLeaves(block) || isLog(block)) {
                outNodes[count] = NavNodes.pack(nx, ny, nz);
                outCosts[count] = 1.0f;
                count++;
            }
        }
        return count;
    }

    @Override
    public float heuristic(long node) {
        return 0.0f;
    }

    @Override
    public boolean isGoal(long node) {
        return false;
    }

    @Override
    public int maxSuccessors() {
        return 6;
    }

    private static boolean isLeaves(BlockType block) {
        return block != null && block.isLeaves();
    }

    private static boolean isLog(BlockType block) {
        return block != null && block.isLog();
    }

    /** Six orthogonal neighbors, listed in a deterministic order. */
    private static final int[][] OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
}
