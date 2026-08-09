package com.openmason.engine.voxel.sbo;

import com.openmason.engine.voxel.sbo.sboRenderer.SBOFaceConventions;

/**
 * Computes flat normals for SBO mesh data and classifies every triangle
 * against the block cell it lives in.
 *
 * <p>SBO meshes parsed from GMR have no normals (GMR computes them
 * procedurally in the shader via dFdx/dFdy). The MMS vertex format
 * requires per-vertex normals, so we compute flat normals from the
 * triangle geometry.
 *
 * <p>Since flat shading requires each triangle to have its own normal,
 * shared vertices are de-indexed (duplicated) so each triangle gets
 * 3 unique vertices with the same face normal.
 *
 * <h3>Orientation is derived from geometry, never from winding</h3>
 * SBO authoring history left several shipped assets with mixed triangle
 * winding (a cube whose back faces are wound the same way as its front), so
 * the cross-product direction is not trustworthy. Instead, for every
 * axis-aligned triangle:
 * <ul>
 *   <li><b>Flush</b> with a cell boundary plane ({@code |coord| == 0.5}) — the
 *       outward direction is the boundary's own sign. Covers every cube.</li>
 *   <li><b>Interior</b> (a stair tread, a riser) — the solid side is found by
 *       an axis-parallel <em>crossing-parity</em> probe from the triangle's
 *       centroid: an odd number of the model's own perpendicular triangles
 *       above the plane means the material lies that way, so the surface
 *       faces the other way.</li>
 * </ul>
 * Only non-axis-aligned geometry (a flower's diagonal cross planes) falls back
 * to the winding normal, flipped away from the block centre.
 *
 * <p>The classification is reused by {@link SBOMeshProcessor} to bucket
 * triangles per face, so face culling and per-face textures follow the shape
 * the artist actually modelled rather than the authored GMR face id — which
 * only spans 0..5 and therefore cannot describe a model with more than six
 * faces.
 */
public final class SBONormalComputer {

    /** Half extent of a block cell in model space. */
    private static final float CELL_HALF = 0.5f;
    /** Coordinate tolerance for "these vertices share a plane". */
    private static final float PLANE_EPSILON = 1e-4f;
    /**
     * Above this triangle count the O(n²) parity probe is skipped and interior
     * triangles fall back to their winding normal. No shipped block model comes
     * close; the cap only bounds a pathological asset's load time.
     */
    private static final int PARITY_TRIANGLE_LIMIT = 4096;
    /** Barycentric weights of the parity probe point — see {@link #crossingsAbove}. */
    private static final float PROBE_A = 0.5f;
    private static final float PROBE_B = 0.3f;
    private static final float PROBE_C = 0.2f;

    private SBONormalComputer() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Result of normal computation: de-indexed mesh with flat normals and a
     * per-triangle face classification.
     *
     * @param vertices       positions (x,y,z interleaved), 3 unique vertices per triangle
     * @param normals        flat normals (nx,ny,nz interleaved), same count as vertices
     * @param texCoords      UVs (u,v interleaved), same vertex count as positions
     * @param indices        sequential indices (0,1,2, 3,4,5, ...) since vertices are de-indexed
     * @param triangleFaces  MMS face id (0..5) each triangle points along
     * @param triangleFlush  true when the triangle lies on that face's cell boundary plane,
     *                       i.e. a neighbouring block can hide it
     */
    public record ProcessedMesh(float[] vertices, float[] normals, float[] texCoords, int[] indices,
                                int[] triangleFaces, boolean[] triangleFlush) {
        public int vertexCount() {
            return vertices.length / 3;
        }

        public int triangleCount() {
            return indices.length / 3;
        }
    }

    /**
     * Compute flat normals, de-index the mesh and classify each triangle.
     *
     * @param srcVertices  original positions (x,y,z interleaved)
     * @param srcTexCoords original UVs (u,v interleaved)
     * @param srcIndices   original triangle indices
     * @return processed mesh with de-indexed vertices, flat normals and face buckets
     */
    public static ProcessedMesh compute(float[] srcVertices, float[] srcTexCoords, int[] srcIndices) {
        int triangleCount = srcIndices.length / 3;
        int newVertexCount = triangleCount * 3;

        float[] vertices = new float[newVertexCount * 3];
        float[] normals = new float[newVertexCount * 3];
        float[] texCoords = new float[newVertexCount * 2];
        int[] indices = new int[newVertexCount];
        int[] triangleFaces = new int[triangleCount];
        boolean[] triangleFlush = new boolean[triangleCount];

        // De-index first: the parity probe needs random access to every
        // triangle's corners in one flat array.
        for (int tri = 0; tri < triangleCount; tri++) {
            int i0 = srcIndices[tri * 3];
            int i1 = srcIndices[tri * 3 + 1];
            int i2 = srcIndices[tri * 3 + 2];
            int base = tri * 3;
            int vOff = base * 3;
            int tOff = base * 2;

            vertices[vOff]     = srcVertices[i0 * 3];
            vertices[vOff + 1] = srcVertices[i0 * 3 + 1];
            vertices[vOff + 2] = srcVertices[i0 * 3 + 2];
            vertices[vOff + 3] = srcVertices[i1 * 3];
            vertices[vOff + 4] = srcVertices[i1 * 3 + 1];
            vertices[vOff + 5] = srcVertices[i1 * 3 + 2];
            vertices[vOff + 6] = srcVertices[i2 * 3];
            vertices[vOff + 7] = srcVertices[i2 * 3 + 1];
            vertices[vOff + 8] = srcVertices[i2 * 3 + 2];

            texCoords[tOff]     = srcTexCoords[i0 * 2];
            texCoords[tOff + 1] = srcTexCoords[i0 * 2 + 1];
            texCoords[tOff + 2] = srcTexCoords[i1 * 2];
            texCoords[tOff + 3] = srcTexCoords[i1 * 2 + 1];
            texCoords[tOff + 4] = srcTexCoords[i2 * 2];
            texCoords[tOff + 5] = srcTexCoords[i2 * 2 + 1];

            indices[base]     = base;
            indices[base + 1] = base + 1;
            indices[base + 2] = base + 2;
        }

        // Plane each triangle lies on: axis (-1 = not axis-aligned) + coordinate.
        int[] triAxis = new int[triangleCount];
        float[] triCoord = new float[triangleCount];
        for (int tri = 0; tri < triangleCount; tri++) {
            triAxis[tri] = planarAxis(vertices, tri);
            triCoord[tri] = triAxis[tri] >= 0 ? vertices[tri * 9 + triAxis[tri]] : 0f;
        }
        boolean parityAvailable = triangleCount <= PARITY_TRIANGLE_LIMIT;

        for (int tri = 0; tri < triangleCount; tri++) {
            int axis = triAxis[tri];
            float nx;
            float ny;
            float nz;

            if (axis >= 0) {
                float coord = triCoord[tri];
                boolean flush = Math.abs(Math.abs(coord) - CELL_HALF) <= PLANE_EPSILON;
                boolean positive;
                if (flush) {
                    positive = coord > 0f;
                } else if (parityAvailable) {
                    // Odd crossing count above the plane ⇒ solid above ⇒ the
                    // surface looks down the axis.
                    positive = (crossingsAbove(vertices, triAxis, triCoord, tri, axis, coord) & 1) == 0;
                } else {
                    positive = windingComponent(vertices, tri, axis) >= 0f;
                }
                nx = axis == 0 ? (positive ? 1f : -1f) : 0f;
                ny = axis == 1 ? (positive ? 1f : -1f) : 0f;
                nz = axis == 2 ? (positive ? 1f : -1f) : 0f;
                triangleFaces[tri] = SBOFaceConventions.mmsFaceForAxis(axis, positive);
                triangleFlush[tri] = flush;
            } else {
                // Not axis-aligned (cross-plane flowers): keep the legacy rule —
                // winding normal, flipped if it points back at the block centre.
                float[] n = windingNormal(vertices, tri);
                float cx = (vertices[tri * 9] + vertices[tri * 9 + 3] + vertices[tri * 9 + 6]) * (1f / 3f);
                float cy = (vertices[tri * 9 + 1] + vertices[tri * 9 + 4] + vertices[tri * 9 + 7]) * (1f / 3f);
                float cz = (vertices[tri * 9 + 2] + vertices[tri * 9 + 5] + vertices[tri * 9 + 8]) * (1f / 3f);
                if (n[0] * cx + n[1] * cy + n[2] * cz < 0f) {
                    n[0] = -n[0];
                    n[1] = -n[1];
                    n[2] = -n[2];
                }
                nx = n[0];
                ny = n[1];
                nz = n[2];
                int dominant = dominantAxis(nx, ny, nz);
                triangleFaces[tri] = SBOFaceConventions.mmsFaceForAxis(dominant,
                        component(nx, ny, nz, dominant) >= 0f);
                triangleFlush[tri] = false;
            }

            int vOff = tri * 9;
            normals[vOff]     = nx; normals[vOff + 1] = ny; normals[vOff + 2] = nz;
            normals[vOff + 3] = nx; normals[vOff + 4] = ny; normals[vOff + 5] = nz;
            normals[vOff + 6] = nx; normals[vOff + 7] = ny; normals[vOff + 8] = nz;
        }

        return new ProcessedMesh(vertices, normals, texCoords, indices, triangleFaces, triangleFlush);
    }

    /** The axis all three corners share a coordinate on, or -1 if there is none. */
    private static int planarAxis(float[] verts, int tri) {
        int base = tri * 9;
        for (int axis = 0; axis < 3; axis++) {
            float a = verts[base + axis];
            float b = verts[base + 3 + axis];
            float c = verts[base + 6 + axis];
            if (Math.abs(a - b) <= PLANE_EPSILON && Math.abs(a - c) <= PLANE_EPSILON) {
                return axis;
            }
        }
        return -1;
    }

    /**
     * Number of the model's own perpendicular triangles an axis-parallel ray
     * from a point inside {@code tri} crosses on the far side of its plane.
     *
     * <p>The probe point is a lopsided barycentric blend rather than the
     * centroid: two parallel faces of a box share a footprint but are
     * triangulated along opposite diagonals, and a centroid lands exactly on
     * the opposite face's diagonal — the strict containment test would then
     * miss the crossing and read the surface inside-out. Uneven weights keep
     * the point clear of the axis-aligned splits a box model produces while
     * staying strictly inside the triangle.
     */
    private static int crossingsAbove(float[] verts, int[] triAxis, float[] triCoord,
                                      int tri, int axis, float coord) {
        int base = tri * 9;
        int u = axis == 0 ? 1 : 0;
        int v = axis == 2 ? 1 : 2;
        float pu = verts[base + u] * PROBE_A + verts[base + 3 + u] * PROBE_B + verts[base + 6 + u] * PROBE_C;
        float pv = verts[base + v] * PROBE_A + verts[base + 3 + v] * PROBE_B + verts[base + 6 + v] * PROBE_C;

        int crossings = 0;
        for (int other = 0; other < triAxis.length; other++) {
            if (triAxis[other] != axis || triCoord[other] <= coord + PLANE_EPSILON) {
                continue;
            }
            int ob = other * 9;
            if (containsPoint(verts[ob + u], verts[ob + v],
                    verts[ob + 3 + u], verts[ob + 3 + v],
                    verts[ob + 6 + u], verts[ob + 6 + v], pu, pv)) {
                crossings++;
            }
        }
        return crossings;
    }

    /** Strict 2D point-in-triangle test (points on an edge are excluded). */
    private static boolean containsPoint(float ax, float ay, float bx, float by,
                                         float cx, float cy, float px, float py) {
        float denom = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
        if (Math.abs(denom) < 1e-12f) {
            return false; // degenerate triangle contains nothing
        }
        float l1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / denom;
        float l2 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / denom;
        float l3 = 1f - l1 - l2;
        return l1 > 0f && l2 > 0f && l3 > 0f;
    }

    /** Unit cross-product normal of a de-indexed triangle. */
    private static float[] windingNormal(float[] verts, int tri) {
        int base = tri * 9;
        float e1x = verts[base + 3] - verts[base];
        float e1y = verts[base + 4] - verts[base + 1];
        float e1z = verts[base + 5] - verts[base + 2];
        float e2x = verts[base + 6] - verts[base];
        float e2y = verts[base + 7] - verts[base + 1];
        float e2z = verts[base + 8] - verts[base + 2];
        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-8f) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        return new float[]{nx, ny, nz};
    }

    private static float windingComponent(float[] verts, int tri, int axis) {
        return windingNormal(verts, tri)[axis];
    }

    private static float component(float nx, float ny, float nz, int axis) {
        return axis == 0 ? nx : axis == 1 ? ny : nz;
    }

    private static int dominantAxis(float nx, float ny, float nz) {
        float ax = Math.abs(nx);
        float ay = Math.abs(ny);
        float az = Math.abs(nz);
        if (ax >= ay && ax >= az) return 0;
        return ay >= az ? 1 : 2;
    }
}
