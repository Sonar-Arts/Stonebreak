package com.openmason.engine.rendering.model.gmr.editable;

import com.openmason.engine.rendering.model.gmr.GMRConstants;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Triangulates a polygon loop, correctly handling concave polygons and loops
 * with collinear vertices (e.g. {@code [A, midpoint, B, ...]} after edge
 * subdivision). Replaces the fan triangulation the legacy processors used,
 * which produced overlapping triangles for concave faces.
 *
 * <p>Algorithm: Newell normal → project loop to its best-fit plane → convex
 * fast path (fan) → ear clipping (O(n²), fine at model-editing face sizes).
 * Always emits exactly {@code loop.length - 2} triangles; on degenerate input
 * it falls back to a fan with a warning rather than failing.
 */
public final class PolygonTriangulator {

    private static final Logger logger = LoggerFactory.getLogger(PolygonTriangulator.class);

    /** Relative area tolerance for convexity / point-in-triangle tests. */
    private static final float RELATIVE_AREA_EPSILON = 1e-7f;

    private PolygonTriangulator() {
        // Static utility — no instantiation
    }

    /**
     * Triangulate the polygon formed by {@code loop} (ordered boundary positions).
     *
     * @param loop Polygon boundary positions in winding order (≥ 3)
     * @return Loop-local index triples, {@code 3 * (loop.length - 2)} entries,
     *         wound consistently with the input loop
     */
    public static int[] triangulate(Vector3f[] loop) {
        int n = loop.length;
        if (n < 3) {
            throw new IllegalArgumentException("Polygon needs >= 3 vertices: " + n);
        }
        if (n == 3) {
            return new int[]{0, 1, 2};
        }

        Vector3f normal = newellNormal(loop);
        if (normal.lengthSquared() < GMRConstants.DEGENERATE_NORMAL_EPSILON_SQ) {
            logger.warn("Degenerate polygon normal ({} vertices) — falling back to fan triangulation", n);
            return fan(0, n);
        }

        // Project onto the best-fit plane. (tangent, bitangent, normal) is a
        // right-handed frame, so a loop that is CCW around the normal has
        // positive signed area in 2D.
        Vector3f unitNormal = new Vector3f(normal).normalize();
        Vector3f tangent = pickTangent(unitNormal);
        Vector3f bitangent = new Vector3f(unitNormal).cross(tangent);

        float[] xs = new float[n];
        float[] ys = new float[n];
        float maxExtent = 0.0f;
        for (int i = 0; i < n; i++) {
            xs[i] = loop[i].dot(tangent);
            ys[i] = loop[i].dot(bitangent);
            maxExtent = Math.max(maxExtent, Math.max(Math.abs(xs[i]), Math.abs(ys[i])));
        }
        // Cross products scale with area, so the tolerance scales with extent².
        float areaEps = RELATIVE_AREA_EPSILON * Math.max(maxExtent * maxExtent, 1e-12f);

        float orient = Math.signum(signedArea(xs, ys));
        if (orient == 0.0f) {
            logger.warn("Zero-area polygon ({} vertices) — falling back to fan triangulation", n);
            return fan(0, n);
        }

        if (isConvex(xs, ys, orient, areaEps)) {
            return fan(0, n);
        }

        return earClip(xs, ys, orient, areaEps);
    }

    /**
     * Newell normal of a polygon loop — robust for non-planar and collinear
     * inputs, unlike a single cross product of the first three vertices.
     * Points out of a counter-clockwise loop. Not normalized.
     */
    public static Vector3f newellNormal(Vector3f[] loop) {
        float nx = 0.0f, ny = 0.0f, nz = 0.0f;
        int n = loop.length;
        for (int i = 0; i < n; i++) {
            Vector3f c = loop[i];
            Vector3f x = loop[(i + 1) % n];
            nx += (c.y - x.y) * (c.z + x.z);
            ny += (c.z - x.z) * (c.x + x.x);
            nz += (c.x - x.x) * (c.y + x.y);
        }
        return new Vector3f(nx, ny, nz);
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private static Vector3f pickTangent(Vector3f unitNormal) {
        // Reference not parallel to the normal (same convention as the UV frame).
        Vector3f reference = (Math.abs(unitNormal.y) > 0.9f)
            ? new Vector3f(0.0f, 0.0f, -1.0f)
            : new Vector3f(0.0f, 1.0f, 0.0f);
        return reference.cross(unitNormal).normalize();
    }

    private static float signedArea(float[] xs, float[] ys) {
        float sum = 0.0f;
        int n = xs.length;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += xs[i] * ys[j] - xs[j] * ys[i];
        }
        return sum * 0.5f;
    }

    private static boolean isConvex(float[] xs, float[] ys, float orient, float areaEps) {
        int n = xs.length;
        for (int i = 0; i < n; i++) {
            int p = (i + n - 1) % n;
            int x = (i + 1) % n;
            if (cross(xs, ys, p, i, x) * orient < -areaEps) {
                return false;
            }
        }
        return true;
    }

    /** 2D cross product of (a→b) × (b→c). */
    private static float cross(float[] xs, float[] ys, int a, int b, int c) {
        float abx = xs[b] - xs[a];
        float aby = ys[b] - ys[a];
        float bcx = xs[c] - xs[b];
        float bcy = ys[c] - ys[b];
        return abx * bcy - aby * bcx;
    }

    private static int[] fan(int apex, int n) {
        int[] triangles = new int[(n - 2) * 3];
        int t = 0;
        for (int i = 1; i < n - 1; i++) {
            triangles[t++] = apex;
            triangles[t++] = (apex + i) % n;
            triangles[t++] = (apex + i + 1) % n;
        }
        return triangles;
    }

    private static int[] earClip(float[] xs, float[] ys, float orient, float areaEps) {
        int n = xs.length;
        List<Integer> active = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            active.add(i);
        }

        int[] triangles = new int[(n - 2) * 3];
        int t = 0;

        while (active.size() > 3) {
            int earPos = findEar(active, xs, ys, orient, areaEps);
            if (earPos < 0) {
                // Numerically stuck (e.g. fully collinear remnant): finish with
                // a fan over the remaining vertices so the triangle count stays
                // exactly n - 2, and warn.
                logger.warn("Ear clipping found no ear with {} vertices remaining — finishing with fan",
                    active.size());
                for (int i = 1; i < active.size() - 1; i++) {
                    triangles[t++] = active.get(0);
                    triangles[t++] = active.get(i);
                    triangles[t++] = active.get(i + 1);
                }
                return triangles;
            }

            int m = active.size();
            triangles[t++] = active.get((earPos + m - 1) % m);
            triangles[t++] = active.get(earPos);
            triangles[t++] = active.get((earPos + 1) % m);
            active.remove(earPos);
        }

        triangles[t++] = active.get(0);
        triangles[t++] = active.get(1);
        triangles[t] = active.get(2);
        return triangles;
    }

    private static int findEar(List<Integer> active, float[] xs, float[] ys,
                               float orient, float areaEps) {
        int m = active.size();
        for (int k = 0; k < m; k++) {
            int a = active.get((k + m - 1) % m);
            int b = active.get(k);
            int c = active.get((k + 1) % m);

            // Ear tip must be strictly convex.
            if (cross(xs, ys, a, b, c) * orient <= areaEps) {
                continue;
            }

            // No other active vertex may lie inside (or on the boundary of)
            // the candidate ear — on-boundary counts as inside so vertices
            // sitting exactly on an ear edge are never stranded.
            boolean blocked = false;
            for (int other : active) {
                if (other == a || other == b || other == c) {
                    continue;
                }
                if (inTriangle(xs, ys, a, b, c, other, orient, areaEps)) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                return k;
            }
        }
        return -1;
    }

    private static boolean inTriangle(float[] xs, float[] ys, int a, int b, int c,
                                      int p, float orient, float areaEps) {
        return crossToPoint(xs, ys, a, b, p) * orient >= -areaEps
            && crossToPoint(xs, ys, b, c, p) * orient >= -areaEps
            && crossToPoint(xs, ys, c, a, p) * orient >= -areaEps;
    }

    /** 2D cross product of (a→b) × (a→p). */
    private static float crossToPoint(float[] xs, float[] ys, int a, int b, int p) {
        float abx = xs[b] - xs[a];
        float aby = ys[b] - ys[a];
        float apx = xs[p] - xs[a];
        float apy = ys[p] - ys[a];
        return abx * apy - aby * apx;
    }
}
