package com.openmason.engine.voxel.sbo;

import com.openmason.engine.format.mesh.ParsedMeshData;

import java.util.ArrayList;
import java.util.List;

/**
 * Classifies SBO models built from upright flat planes — a single pane, or an
 * X of two crossing panes (saplings, flowers). The SBO format carries no shape
 * field, so the shape is read off the geometry: every triangle lies on one of
 * at most {@value #MAX_PLANES} distinct vertical planes. A cube has six planes,
 * stairs more; horizontal plates are not panes and keep their authored height.
 */
public final class SBOPlanarGeometry {

    /** One pane, or the two panes of an X. */
    private static final int MAX_PLANES = 2;
    /** Tolerance on plane normals and offsets. */
    private static final float EPSILON = 1e-3f;
    /** Bottom of the block cell in the stamp's centred model space. */
    private static final float CELL_FLOOR_Y = -0.5f;

    private SBOPlanarGeometry() {
    }

    /** True when every triangle of the mesh lies on one or two vertical planes. */
    public static boolean isPlanar(ParsedMeshData mesh) {
        if (mesh == null || !mesh.hasGeometry() || mesh.indices() == null
                || mesh.getTriangleCount() == 0) {
            return false;
        }
        float[] v = mesh.vertices();
        int[] idx = mesh.indices();
        List<float[]> planes = new ArrayList<>(MAX_PLANES + 1);
        for (int tri = 0; tri < mesh.getTriangleCount(); tri++) {
            float[] plane = planeOf(v, idx[tri * 3], idx[tri * 3 + 1], idx[tri * 3 + 2]);
            if (plane == null) continue; // degenerate sliver
            if (Math.abs(plane[1]) > EPSILON) return false; // not upright
            if (!containsPlane(planes, plane)) {
                planes.add(plane);
                if (planes.size() > MAX_PLANES) return false;
            }
        }
        return !planes.isEmpty();
    }

    /**
     * The Y shift that puts the mesh's lowest vertex on the cell floor, so a
     * pane or X exported with a baked-in part offset still stands on the
     * ground instead of floating.
     */
    public static float groundOffsetY(ParsedMeshData mesh) {
        float[] v = mesh.vertices();
        float minY = Float.POSITIVE_INFINITY;
        for (int i = 1; i < v.length; i += 3) {
            minY = Math.min(minY, v[i]);
        }
        return CELL_FLOOR_Y - minY;
    }

    /** Unit normal with a canonical sign (so front and back faces match) plus offset. */
    private static float[] planeOf(float[] v, int a, int b, int c) {
        float ax = v[a * 3], ay = v[a * 3 + 1], az = v[a * 3 + 2];
        float e1x = v[b * 3] - ax, e1y = v[b * 3 + 1] - ay, e1z = v[b * 3 + 2] - az;
        float e2x = v[c * 3] - ax, e2y = v[c * 3 + 1] - ay, e2z = v[c * 3 + 2] - az;
        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return null;
        nx /= len;
        ny /= len;
        nz /= len;
        // First non-zero component positive.
        boolean flip = Math.abs(nx) > EPSILON ? nx < 0
                : Math.abs(ny) > EPSILON ? ny < 0
                : nz < 0;
        if (flip) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        return new float[]{nx, ny, nz, nx * ax + ny * ay + nz * az};
    }

    private static boolean containsPlane(List<float[]> planes, float[] p) {
        for (float[] q : planes) {
            if (Math.abs(q[0] - p[0]) < EPSILON && Math.abs(q[1] - p[1]) < EPSILON
                    && Math.abs(q[2] - p[2]) < EPSILON && Math.abs(q[3] - p[3]) < EPSILON) {
                return true;
            }
        }
        return false;
    }
}
