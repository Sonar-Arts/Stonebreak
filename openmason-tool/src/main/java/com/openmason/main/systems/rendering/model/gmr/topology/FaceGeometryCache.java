package com.openmason.main.systems.rendering.model.gmr.topology;

import org.joml.Vector3f;

/**
 * Per-face normals, centroids, and areas with lazy dirty recomputation.
 *
 * <p>Root of the dirty-tracking dependency chain. When a vertex moves,
 * affected faces are marked dirty here. Sibling caches ({@link VertexNormalCache},
 * {@link DihedralAngleCache}) call {@link #ensureFaceClean(int)} before reading
 * face geometry data.
 *
 * @see MeshTopologyBuilder
 */
public final class FaceGeometryCache {

    private final MeshFace[] faces;
    private final int[][] uniqueToMeshIndices;
    private final Vector3f[] faceNormals;
    private final Vector3f[] faceCentroids;
    private final float[] faceAreas;
    private final boolean[] faceDirty;
    private float[] verticesRef;

    /**
     * Package-private constructor used by {@link MeshTopologyBuilder}.
     */
    FaceGeometryCache(MeshFace[] faces, int[][] uniqueToMeshIndices,
                      Vector3f[] faceNormals, Vector3f[] faceCentroids,
                      float[] faceAreas, float[] vertices) {
        this.faces = faces;
        this.uniqueToMeshIndices = uniqueToMeshIndices;
        this.faceNormals = faceNormals;
        this.faceCentroids = faceCentroids;
        this.faceAreas = faceAreas;
        this.faceDirty = new boolean[faces.length];
        this.verticesRef = vertices;
    }

    /**
     * Get the cached normal for a face. Lazily recomputes if dirty.
     *
     * @param faceId Face identifier
     * @return The face normal (unit vector), or null if out of range
     */
    public Vector3f getFaceNormal(int faceId) {
        if (faceId < 0 || faceId >= faceNormals.length) {
            return null;
        }
        ensureFaceClean(faceId);
        return faceNormals[faceId];
    }

    /**
     * Get the cached centroid for a face. Lazily recomputes if dirty.
     *
     * @param faceId Face identifier
     * @return The face centroid, or null if out of range
     */
    public Vector3f getFaceCentroid(int faceId) {
        if (faceId < 0 || faceId >= faceCentroids.length) {
            return null;
        }
        ensureFaceClean(faceId);
        return faceCentroids[faceId];
    }

    /**
     * Get the cached area for a face. Lazily recomputes if dirty.
     *
     * @param faceId Face identifier
     * @return The face area, or {@code Float.NaN} if out of range
     */
    public float getFaceArea(int faceId) {
        if (faceId < 0 || faceId >= faceAreas.length) {
            return Float.NaN;
        }
        ensureFaceClean(faceId);
        return faceAreas[faceId];
    }

    /**
     * Check whether a face is planar within a given tolerance.
     *
     * @param faceId    Face identifier
     * @param tolerance Maximum allowed distance from the face plane (must be &ge; 0)
     * @return true if all vertices lie within tolerance of the face plane
     */
    public boolean isFacePlanar(int faceId, float tolerance) {
        if (faceId < 0 || faceId >= faces.length || verticesRef == null) {
            return false;
        }
        MeshFace face = faces[faceId];
        if (face.vertexCount() <= 3) {
            return true;
        }
        ensureFaceClean(faceId);
        float maxDist = MeshGeometry.computeMaxDistanceToPlane(
                face.vertexIndices(), uniqueToMeshIndices, verticesRef,
                faceNormals[faceId], faceCentroids[faceId]);
        return maxDist <= tolerance;
    }

    /**
     * Mark a face as needing geometry recomputation.
     * Package-private — called by {@link MeshTopology#onVertexPositionChanged}.
     */
    void markFaceDirty(int faceId) {
        if (faceId >= 0 && faceId < faceDirty.length) {
            faceDirty[faceId] = true;
        }
    }

    /**
     * Ensure face geometry is up-to-date, recomputing if dirty.
     * Package-private — called by sibling caches before reading face data.
     */
    void ensureFaceClean(int faceId) {
        if (faceId >= 0 && faceId < faceDirty.length && faceDirty[faceId] && verticesRef != null) {
            int[] verts = faces[faceId].vertexIndices();
            faceNormals[faceId] = MeshGeometry.computeNormal(verts, uniqueToMeshIndices, verticesRef);
            faceCentroids[faceId] = MeshGeometry.computeCentroid(verts, uniqueToMeshIndices, verticesRef);
            faceAreas[faceId] = MeshGeometry.computeArea(verts, uniqueToMeshIndices, verticesRef);
            faceDirty[faceId] = false;
        }
    }

    /**
     * Update the vertex positions reference after a vertex move.
     * Package-private — called by {@link MeshTopology#onVertexPositionChanged}.
     */
    void updateVerticesRef(float[] vertices) {
        this.verticesRef = vertices;
    }

    /**
     * Direct reference to the face normals array.
     * Package-private — used by {@link DihedralAngleCache} and {@link VertexNormalCache}.
     */
    Vector3f[] normals() {
        return faceNormals;
    }

    /**
     * Direct reference to the face areas array.
     * Package-private — used by {@link VertexNormalCache}.
     */
    float[] areas() {
        return faceAreas;
    }
}
