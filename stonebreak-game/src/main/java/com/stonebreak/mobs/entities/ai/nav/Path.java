package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.wayfind.voxel.GroundNavDomain;
import com.openmason.engine.wayfind.voxel.NavNodes;
import com.openmason.engine.wayfind.voxel.PathSmoother;
import org.joml.Vector3f;

/**
 * A finished route: world-space waypoints a mob walks through, in order.
 *
 * <p>Immutable, which is what makes the asynchronous handoff safe — a worker thread builds one and
 * publishes it through a single volatile write, and the tick thread reads it without a lock. The
 * position along the path is <em>not</em> part of it; that belongs to the {@link PathAgent} doing
 * the walking, so the same path could in principle be followed by two mobs.
 *
 * <p>Waypoints sit at the horizontal centre of their cell, at the exact height the mob's feet will
 * rest — which is what makes a stair tread or a snow layer a real waypoint rather than an
 * approximate one.
 */
public final class Path {

    /** No route: either nothing was found, or the search failed. */
    public static final Path EMPTY = new Path(new float[0], false);

    private final float[] points; // x, y, z per waypoint
    private final boolean complete;

    private Path(float[] points, boolean complete) {
        this.points = points;
        this.complete = complete;
    }

    /**
     * Converts a search's node chain into world-space waypoints, collapsing straight runs.
     *
     * <p>Must be called while {@code domain} is still valid — it is what knows the exact surface
     * height of each cell.
     *
     * @param complete whether the chain actually reaches the goal, as opposed to being the best
     *                 effort toward an unreachable one
     */
    public static Path of(long[] nodes, GroundNavDomain domain, boolean complete) {
        if (nodes.length == 0) {
            return EMPTY;
        }
        long[] corners = PathSmoother.collapseCollinear(nodes);
        float[] points = new float[corners.length * 3];
        for (int i = 0; i < corners.length; i++) {
            long node = corners[i];
            float surface = domain.surfaceOf(node);
            points[i * 3] = NavNodes.x(node) + 0.5f;
            // A cell can stop being standable between the search and this call (a block broken on
            // the tick thread); the cell floor is the honest fallback, and the follower's own
            // ground handling absorbs the difference.
            points[i * 3 + 1] = Float.isNaN(surface) ? NavNodes.y(node) : surface;
            points[i * 3 + 2] = NavNodes.z(node) + 0.5f;
        }
        return new Path(points, complete);
    }

    /**
     * Converts an air search's chain of coarse cells into world-space waypoints at their centres.
     *
     * <p>No collinear collapse here: {@code AirNavDomain.stringPull} has already reduced the chain
     * to the corners a flyer actually turns at, and it does a better job than a direction test
     * because it knows which shortcuts are clear.
     *
     * @param complete whether the chain reaches the goal, as opposed to being the best effort
     *                 toward one that could not be reached
     */
    public static Path ofCells(long[] nodes, int cellSize, boolean complete) {
        if (nodes.length == 0) {
            return EMPTY;
        }
        float half = cellSize * 0.5f;
        float[] points = new float[nodes.length * 3];
        for (int i = 0; i < nodes.length; i++) {
            long node = nodes[i];
            points[i * 3] = NavNodes.x(node) * cellSize + half;
            points[i * 3 + 1] = NavNodes.y(node) * cellSize + half;
            points[i * 3 + 2] = NavNodes.z(node) * cellSize + half;
        }
        return new Path(points, complete);
    }

    public int size() {
        return points.length / 3;
    }

    public boolean isEmpty() {
        return points.length == 0;
    }

    /** Whether this route reaches the goal, rather than getting as close as it could. */
    public boolean isComplete() {
        return complete;
    }

    public float x(int index) {
        return points[index * 3];
    }

    public float y(int index) {
        return points[index * 3 + 1];
    }

    public float z(int index) {
        return points[index * 3 + 2];
    }

    /** Writes waypoint {@code index} into {@code out} and returns it. */
    public Vector3f waypoint(int index, Vector3f out) {
        return out.set(x(index), y(index), z(index));
    }

    /** Writes the final waypoint into {@code out}; unchanged when the path is empty. */
    public Vector3f end(Vector3f out) {
        return isEmpty() ? out : waypoint(size() - 1, out);
    }
}
