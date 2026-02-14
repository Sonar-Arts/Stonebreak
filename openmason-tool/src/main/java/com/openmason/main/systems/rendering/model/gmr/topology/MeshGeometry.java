package com.openmason.main.systems.rendering.model.gmr.topology;

import org.joml.Vector3f;

import java.util.List;

/**
 * Stateless geometry computations for mesh topology elements.
 *
 * <p>Pure functions with no side effects — safe to call from any thread.
 * Used by {@link MeshTopology} for cached geometry and by
 * {@link MeshTopologyBuilder} for initial computation.
 */
public final class MeshGeometry {

    private MeshGeometry() {
        // Utility class
    }

    /**
     * Compute a face normal using Newell's method.
     * Works for triangles, quads, and arbitrary planar polygons.
     *
     * @param uniqueVertexIndices Ordered unique vertex indices of the face
     * @param uniqueToMesh       Mapping from unique vertex index to mesh indices
     * @param vertices           Vertex positions (x,y,z interleaved, indexed by mesh index)
     * @return Normalized face normal, or zero vector for degenerate faces
     */
    public static Vector3f computeNormal(int[] uniqueVertexIndices, int[][] uniqueToMesh, float[] vertices) {
        float normalX = 0, normalY = 0, normalZ = 0;
        int count = uniqueVertexIndices.length;

        for (int i = 0; i < count; i++) {
            int currMesh = uniqueToMesh[uniqueVertexIndices[i]][0];
            int nextMesh = uniqueToMesh[uniqueVertexIndices[(i + 1) % count]][0];

            float cx = vertices[currMesh * 3],     cy = vertices[currMesh * 3 + 1],     cz = vertices[currMesh * 3 + 2];
            float nx = vertices[nextMesh * 3],     ny = vertices[nextMesh * 3 + 1],     nz = vertices[nextMesh * 3 + 2];

            normalX += (cy - ny) * (cz + nz);
            normalY += (cz - nz) * (cx + nx);
            normalZ += (cx - nx) * (cy + ny);
        }

        Vector3f result = new Vector3f(normalX, normalY, normalZ);
        float len = result.length();
        if (len > 1e-8f) {
            result.div(len);
        }
        return result;
    }

    /**
     * Compute a face centroid (average of vertex positions).
     *
     * @param uniqueVertexIndices Ordered unique vertex indices of the face
     * @param uniqueToMesh       Mapping from unique vertex index to mesh indices
     * @param vertices           Vertex positions (x,y,z interleaved, indexed by mesh index)
     * @return Face centroid
     */
    public static Vector3f computeCentroid(int[] uniqueVertexIndices, int[][] uniqueToMesh, float[] vertices) {
        float cx = 0, cy = 0, cz = 0;
        int count = uniqueVertexIndices.length;

        for (int i = 0; i < count; i++) {
            int meshIdx = uniqueToMesh[uniqueVertexIndices[i]][0];
            cx += vertices[meshIdx * 3];
            cy += vertices[meshIdx * 3 + 1];
            cz += vertices[meshIdx * 3 + 2];
        }

        return new Vector3f(cx / count, cy / count, cz / count);
    }

    /**
     * Compute a face area using the magnitude of Newell's cross product sum.
     * Works for triangles, quads, and arbitrary planar polygons.
     *
     * @param uniqueVertexIndices Ordered unique vertex indices of the face
     * @param uniqueToMesh       Mapping from unique vertex index to mesh indices
     * @param vertices           Vertex positions (x,y,z interleaved, indexed by mesh index)
     * @return Face area (half the magnitude of the Newell normal), or 0 for degenerate faces
     */
    public static float computeArea(int[] uniqueVertexIndices, int[][] uniqueToMesh, float[] vertices) {
        float normalX = 0, normalY = 0, normalZ = 0;
        int count = uniqueVertexIndices.length;

        for (int i = 0; i < count; i++) {
            int currMesh = uniqueToMesh[uniqueVertexIndices[i]][0];
            int nextMesh = uniqueToMesh[uniqueVertexIndices[(i + 1) % count]][0];

            float cx = vertices[currMesh * 3],     cy = vertices[currMesh * 3 + 1],     cz = vertices[currMesh * 3 + 2];
            float nx = vertices[nextMesh * 3],     ny = vertices[nextMesh * 3 + 1],     nz = vertices[nextMesh * 3 + 2];

            normalX += (cy - ny) * (cz + nz);
            normalY += (cz - nz) * (cx + nx);
            normalZ += (cx - nx) * (cy + ny);
        }

        return 0.5f * (float) Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
    }

    /**
     * Compute a smooth vertex normal as the area-weighted average of adjacent face normals.
     * Larger faces contribute more to the result, which is the standard weighting approach.
     *
     * @param adjacentFaceIds Face IDs adjacent to the vertex
     * @param faceNormals     Precomputed face normals array
     * @param faceAreas       Precomputed face areas array
     * @return Normalized vertex normal, or zero vector if no valid adjacent faces
     */
    public static Vector3f computeVertexNormal(List<Integer> adjacentFaceIds,
                                               Vector3f[] faceNormals, float[] faceAreas) {
        float nx = 0, ny = 0, nz = 0;

        for (int faceId : adjacentFaceIds) {
            if (faceId < 0 || faceId >= faceNormals.length) {
                continue;
            }
            Vector3f fn = faceNormals[faceId];
            float area = faceAreas[faceId];
            nx += fn.x * area;
            ny += fn.y * area;
            nz += fn.z * area;
        }

        Vector3f result = new Vector3f(nx, ny, nz);
        float len = result.length();
        if (len > 1e-8f) {
            result.div(len);
        }
        return result;
    }

    /**
     * Compute the dihedral angle between two face normals.
     *
     * @param n0 First face normal (unit vector)
     * @param n1 Second face normal (unit vector)
     * @return Angle in radians (0..π)
     */
    public static float computeDihedralAngle(Vector3f n0, Vector3f n1) {
        float dot = Math.clamp(n0.dot(n1), -1.0f, 1.0f);
        return (float) Math.acos(dot);
    }

    /**
     * Compute the maximum distance from any vertex to the best-fit plane of a face.
     * The plane is defined by the face normal and centroid.
     *
     * <p>Trivially returns 0 for triangles (3 vertices are always coplanar).
     * For quads and n-gons, measures the absolute distance from each vertex
     * to the plane, returning the maximum.
     *
     * @param uniqueVertexIndices Ordered unique vertex indices of the face
     * @param uniqueToMesh       Mapping from unique vertex index to mesh indices
     * @param vertices           Vertex positions (x,y,z interleaved, indexed by mesh index)
     * @param normal             Precomputed face normal (unit vector)
     * @param centroid           Precomputed face centroid
     * @return Maximum absolute distance from any vertex to the face plane,
     *         or 0 for triangles and degenerate faces
     */
    public static float computeMaxDistanceToPlane(int[] uniqueVertexIndices, int[][] uniqueToMesh,
                                                   float[] vertices, Vector3f normal, Vector3f centroid) {
        int count = uniqueVertexIndices.length;
        if (count <= 3) {
            return 0.0f;
        }

        float normalLen = normal.lengthSquared();
        if (normalLen < 1e-16f) {
            return 0.0f;
        }

        float maxDist = 0.0f;
        for (int i = 0; i < count; i++) {
            int meshIdx = uniqueToMesh[uniqueVertexIndices[i]][0];
            float vx = vertices[meshIdx * 3]     - centroid.x;
            float vy = vertices[meshIdx * 3 + 1] - centroid.y;
            float vz = vertices[meshIdx * 3 + 2] - centroid.z;

            float dist = Math.abs(vx * normal.x + vy * normal.y + vz * normal.z);
            if (dist > maxDist) {
                maxDist = dist;
            }
        }
        return maxDist;
    }

    /**
     * Compute a canonical key for a pair of face IDs.
     * Packs the smaller ID into the high bits, the larger into the low bits.
     *
     * @param faceA First face ID
     * @param faceB Second face ID
     * @return Packed long suitable for use as a map key
     */
    public static long canonicalFacePairKey(int faceA, int faceB) {
        int min = Math.min(faceA, faceB);
        int max = Math.max(faceA, faceB);
        return ((long) min << 32) | (max & 0xFFFFFFFFL);
    }
}
