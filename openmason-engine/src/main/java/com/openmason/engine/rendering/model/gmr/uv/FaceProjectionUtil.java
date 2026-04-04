package com.openmason.engine.rendering.model.gmr.uv;

import org.joml.Vector3f;

/**
 * Stateless utility for tangent-space face projection.
 *
 * <p>Computes a tangent frame (tangent + bitangent) for any face normal,
 * enabling UV projection that works correctly for both axis-aligned and
 * arbitrarily oriented faces. Replaces the divergent axis-selection
 * helpers that were duplicated across UV processing classes.
 *
 * <p><b>Algorithm:</b>
 * <ol>
 *   <li>Choose a reference vector not parallel to the normal</li>
 *   <li>Tangent = normalize(reference x normal) — viewer's "right" direction</li>
 *   <li>Bitangent = normalize(normal x tangent) — viewer's "up" direction</li>
 * </ol>
 *
 * <p>Verified correct for all 6 cardinal orientations:
 * <ul>
 *   <li>+Z: T=(1,0,0), B=(0,1,0)</li>
 *   <li>-Z: T=(-1,0,0), B=(0,1,0)</li>
 *   <li>+X: T=(0,0,-1), B=(0,1,0)</li>
 *   <li>-X: T=(0,0,1), B=(0,1,0)</li>
 *   <li>+Y: T=(1,0,0), B=(0,0,-1)</li>
 *   <li>-Y: T=(-1,0,0), B=(0,0,1)</li>
 * </ul>
 */
public final class FaceProjectionUtil {

    /** Position-matching tolerance for degenerate detection. */
    public static final float EPSILON = 0.0001f;

    private FaceProjectionUtil() {
        // Stateless utility — no instantiation
    }

    /**
     * Compute an orthonormal tangent frame for the given face normal.
     *
     * <p>The tangent represents the viewer's "right" direction and the
     * bitangent represents the viewer's "up" direction when looking at
     * the face from outside the mesh.
     *
     * @param normal Face normal (need not be normalized; will be normalized internally)
     * @return {@code [tangent, bitangent]}, or {@code null} if the normal is degenerate
     */
    public static Vector3f[] computeTangentFrame(Vector3f normal) {
        if (normal.lengthSquared() < EPSILON * EPSILON) {
            return null;
        }

        Vector3f n = new Vector3f(normal).normalize();

        // Choose reference vector that is not parallel to the normal.
        // For top/bottom faces (Y-dominant), use (0,0,-1) to avoid gimbal lock.
        Vector3f reference = (Math.abs(n.y) > 0.9f)
            ? new Vector3f(0.0f, 0.0f, -1.0f)
            : new Vector3f(0.0f, 1.0f, 0.0f);

        // Tangent = normalize(reference x N) — viewer's "right"
        Vector3f tangent = new Vector3f(reference).cross(n).normalize();

        // Bitangent = normalize(N x T) — viewer's "up"
        Vector3f bitangent = new Vector3f(n).cross(tangent).normalize();

        return new Vector3f[]{tangent, bitangent};
    }

    /**
     * Project face vertex positions into 2D local space normalized to [0, 1].
     *
     * <p>Projects all vertices onto the face's tangent frame, then normalizes
     * the resulting 2D coordinates so that the bounding box maps to the unit
     * square. This gives the polygon shape in the same coordinate space that
     * PolygonShapeMask.fromUVRegion (in openmason-tool texture editor)
     * expects.
     *
     * @param positions   Vertex positions (x,y,z interleaved) for this face only
     * @param vertexCount Number of vertices in the face
     * @return {@code float[2][]} where [0] is X coords and [1] is Y coords (both 0–1),
     *         or {@code null} if the face is degenerate
     */
    public static float[][] projectFaceToLocalSpace(float[] positions, int vertexCount) {
        if (positions == null || vertexCount < 3 || positions.length < vertexCount * 3) {
            return null;
        }

        Vector3f normal = computeRobustFaceNormal(positions, vertexCount);
        Vector3f[] frame = computeTangentFrame(normal);
        if (frame == null) {
            return null;
        }

        Vector3f tangent = frame[0];
        Vector3f bitangent = frame[1];

        float refX = positions[0];
        float refY = positions[1];
        float refZ = positions[2];

        float[] sCoords = new float[vertexCount];
        float[] tCoords = new float[vertexCount];
        float minS = Float.MAX_VALUE, maxS = -Float.MAX_VALUE;
        float minT = Float.MAX_VALUE, maxT = -Float.MAX_VALUE;

        for (int i = 0; i < vertexCount; i++) {
            float dx = positions[i * 3]     - refX;
            float dy = positions[i * 3 + 1] - refY;
            float dz = positions[i * 3 + 2] - refZ;

            sCoords[i] = dx * tangent.x + dy * tangent.y + dz * tangent.z;
            tCoords[i] = dx * bitangent.x + dy * bitangent.y + dz * bitangent.z;

            minS = Math.min(minS, sCoords[i]);
            maxS = Math.max(maxS, sCoords[i]);
            minT = Math.min(minT, tCoords[i]);
            maxT = Math.max(maxT, tCoords[i]);
        }

        float rangeS = maxS - minS;
        float rangeT = maxT - minT;

        if (rangeS < EPSILON || rangeT < EPSILON) {
            return null; // Degenerate (line or point)
        }

        float[] localX = new float[vertexCount];
        float[] localY = new float[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            localX[i] = (sCoords[i] - minS) / rangeS;
            localY[i] = 1.0f - ((tCoords[i] - minT) / rangeT);
        }

        return new float[][]{localX, localY};
    }

    /**
     * Compute a robust face normal by finding three non-collinear vertices.
     *
     * <p>After edge subdivision, the polygon boundary walk can produce vertex
     * orderings where the first three vertices are collinear (e.g.,
     * {@code [A, midpoint(A,B), B, ...]}). Using those for the cross product
     * yields a near-zero normal and a garbage tangent frame. This method
     * tries all vertex triples anchored at vertex 0, selecting the one that
     * produces the largest cross-product magnitude.
     *
     * @param positions   Vertex positions (x,y,z interleaved)
     * @param vertexCount Number of vertices in the face
     * @return The best normal found, or a zero-length vector if all triples are degenerate
     */
    public static Vector3f computeRobustFaceNormal(float[] positions, int vertexCount) {
        Vector3f bestNormal = new Vector3f();
        float bestLenSq = 0.0f;

        // Try all (0, i, j) triples to find the one with the largest cross product
        for (int i = 1; i < vertexCount - 1; i++) {
            for (int j = i + 1; j < vertexCount; j++) {
                Vector3f candidate = computeFaceNormal(positions, 0, i, j);
                float lenSq = candidate.lengthSquared();
                if (lenSq > bestLenSq) {
                    bestNormal = candidate;
                    bestLenSq = lenSq;
                }
            }
        }

        return bestNormal;
    }

    /**
     * Compute a robust face normal from an indexed vertex list.
     *
     * <p>Same algorithm as {@link #computeRobustFaceNormal(float[], int)} but
     * operates on mesh vertex indices (e.g., from {@code groupVerticesByFace})
     * rather than sequential packed positions.
     *
     * @param vertices      Full vertex position buffer (x,y,z interleaved)
     * @param vertexIndices Mesh vertex indices for this face
     * @return The best normal found, or a zero-length vector if all triples are degenerate
     */
    public static Vector3f computeRobustFaceNormal(float[] vertices, java.util.List<Integer> vertexIndices) {
        Vector3f bestNormal = new Vector3f();
        float bestLenSq = 0.0f;
        int count = vertexIndices.size();
        int anchor = vertexIndices.get(0);

        for (int i = 1; i < count - 1; i++) {
            for (int j = i + 1; j < count; j++) {
                Vector3f candidate = computeFaceNormal(vertices,
                    anchor, vertexIndices.get(i), vertexIndices.get(j));
                float lenSq = candidate.lengthSquared();
                if (lenSq > bestLenSq) {
                    bestNormal = candidate;
                    bestLenSq = lenSq;
                }
            }
        }

        return bestNormal;
    }

    /**
     * Compute the (unnormalized) face normal from three vertex positions
     * using the cross product of two edges.
     *
     * @param vertices Vertex positions (x,y,z interleaved)
     * @param i0       Index of the first vertex
     * @param i1       Index of the second vertex
     * @param i2       Index of the third vertex
     * @return The cross product normal (may be zero-length for degenerate faces)
     */
    public static Vector3f computeFaceNormal(float[] vertices, int i0, int i1, int i2) {
        float e1x = vertices[i1 * 3]     - vertices[i0 * 3];
        float e1y = vertices[i1 * 3 + 1] - vertices[i0 * 3 + 1];
        float e1z = vertices[i1 * 3 + 2] - vertices[i0 * 3 + 2];

        float e2x = vertices[i2 * 3]     - vertices[i0 * 3];
        float e2y = vertices[i2 * 3 + 1] - vertices[i0 * 3 + 1];
        float e2z = vertices[i2 * 3 + 2] - vertices[i0 * 3 + 2];

        return new Vector3f(
            e1y * e2z - e1z * e2y,
            e1z * e2x - e1x * e2z,
            e1x * e2y - e1y * e2x
        );
    }
}
