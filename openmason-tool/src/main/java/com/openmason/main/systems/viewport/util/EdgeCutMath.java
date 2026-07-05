package com.openmason.main.systems.viewport.util;

import org.joml.Vector3f;

/**
 * Utility class for edge-cut parameter math shared by the knife tool and
 * Ctrl+Click vertex insertion (DRY).
 *
 * <p>An edge cut is described by a parametric position {@code t} along the edge
 * from vertex A ({@code t = 0}) to vertex B ({@code t = 1}).
 */
public final class EdgeCutMath {

    private EdgeCutMath() {
        throw new AssertionError("EdgeCutMath is a utility class and should not be instantiated");
    }

    /**
     * Compute the position on an edge at parameter t.
     *
     * @param a Edge start vertex position
     * @param b Edge end vertex position
     * @param t Parametric position along the edge (0 = start, 1 = end)
     * @return Interpolated position as a new vector
     */
    public static Vector3f pointOnEdge(Vector3f a, Vector3f b, float t) {
        return new Vector3f(
            a.x * (1f - t) + b.x * t,
            a.y * (1f - t) + b.y * t,
            a.z * (1f - t) + b.z * t
        );
    }

    /**
     * Recompute the parametric t value from a position projected back onto the edge.
     * Uses dot-product projection to find the closest point on the edge line.
     * Used after grid snapping so the stored parameter matches the snapped position.
     *
     * @param point Position to project (e.g. a grid-snapped cut position)
     * @param a     Edge start vertex position
     * @param b     Edge end vertex position
     * @return Clamped t in [0, 1]; 0.5 for a degenerate (zero-length) edge
     */
    public static float recomputeT(Vector3f point, Vector3f a, Vector3f b) {
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        float dz = b.z - a.z;
        float lengthSq = dx * dx + dy * dy + dz * dz;
        if (lengthSq < 1e-12f) {
            return 0.5f;
        }
        float t = ((point.x - a.x) * dx
                 + (point.y - a.y) * dy
                 + (point.z - a.z) * dz) / lengthSq;
        return Math.max(0f, Math.min(1f, t));
    }
}
