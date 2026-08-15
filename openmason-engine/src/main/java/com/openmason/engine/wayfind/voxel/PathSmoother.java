package com.openmason.engine.wayfind.voxel;

/**
 * Trims a raw node chain down to the corners that matter.
 *
 * <p>A grid search emits one node per cell, so a straight 20-block walk arrives as 20 waypoints. A
 * follower steering toward each in turn wastes work and, worse, re-aims on every one — collapsing
 * the runs leaves it aiming at the far end of a straight stretch, which is both cheaper and
 * visually smoother.
 *
 * <p>Only exactly-collinear runs are collapsed. String-pulling a path through open space (testing
 * whether a diagonal shortcut is walkable and dropping the corner if so) would shorten routes
 * further, but it needs walkability sampling along the line and can cut corners the agent's
 * collision box does not actually clear — deliberately left out until something asks for it. The
 * eight-way domain already avoids the staircase zig-zag a four-way one would produce, which is
 * where most of the benefit was.
 */
public final class PathSmoother {

    private PathSmoother() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the chain with interior nodes removed wherever the direction of travel is unchanged.
     * The first and last nodes always survive. Inputs shorter than three nodes are returned as-is.
     */
    public static long[] collapseCollinear(long[] nodes) {
        if (nodes.length < 3) {
            return nodes;
        }

        long[] kept = new long[nodes.length];
        int count = 0;
        kept[count++] = nodes[0];

        int previousDx = NavNodes.x(nodes[1]) - NavNodes.x(nodes[0]);
        int previousDy = NavNodes.y(nodes[1]) - NavNodes.y(nodes[0]);
        int previousDz = NavNodes.z(nodes[1]) - NavNodes.z(nodes[0]);

        for (int i = 1; i < nodes.length - 1; i++) {
            int dx = NavNodes.x(nodes[i + 1]) - NavNodes.x(nodes[i]);
            int dy = NavNodes.y(nodes[i + 1]) - NavNodes.y(nodes[i]);
            int dz = NavNodes.z(nodes[i + 1]) - NavNodes.z(nodes[i]);

            if (dx != previousDx || dy != previousDy || dz != previousDz) {
                kept[count++] = nodes[i];
                previousDx = dx;
                previousDy = dy;
                previousDz = dz;
            }
        }
        kept[count++] = nodes[nodes.length - 1];

        if (count == nodes.length) {
            return nodes;
        }
        long[] trimmed = new long[count];
        System.arraycopy(kept, 0, trimmed, 0, count);
        return trimmed;
    }
}
