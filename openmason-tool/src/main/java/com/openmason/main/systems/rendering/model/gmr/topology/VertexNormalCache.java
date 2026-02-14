package com.openmason.main.systems.rendering.model.gmr.topology;

import org.joml.Vector3f;

import java.util.List;

/**
 * Per-vertex smooth normals with lazy dirty recomputation.
 *
 * <p>Computes area-weighted average of adjacent face normals.
 * Depends on {@link FaceGeometryCache} for clean face normals and areas.
 *
 * @see MeshTopologyBuilder
 */
public final class VertexNormalCache {

    private final Vector3f[] vertexNormals;
    private final boolean[] vertexNormalDirty;
    private final FaceGeometryCache faceGeometryCache;
    private final List<List<Integer>> vertexToFaces;

    /**
     * Package-private constructor used by {@link MeshTopologyBuilder}.
     *
     * @param vertexNormals    Pre-computed vertex normals (owned by this cache)
     * @param faceGeometryCache Face geometry cache for ensuring normals/areas are clean
     * @param vertexToFaces    Vertex-to-face adjacency (shared reference)
     */
    VertexNormalCache(Vector3f[] vertexNormals, FaceGeometryCache faceGeometryCache,
                      List<List<Integer>> vertexToFaces) {
        this.vertexNormals = vertexNormals;
        this.vertexNormalDirty = new boolean[vertexNormals.length];
        this.faceGeometryCache = faceGeometryCache;
        this.vertexToFaces = vertexToFaces;
    }

    /**
     * Get the smooth (area-weighted) normal for a unique vertex.
     * Lazily recomputes if dirty.
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return The vertex normal (unit vector), or null if out of range
     */
    public Vector3f getVertexNormal(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexNormals.length) {
            return null;
        }
        ensureClean(uniqueVertexIdx);
        return vertexNormals[uniqueVertexIdx];
    }

    /**
     * Mark a vertex normal as needing recomputation.
     * Package-private — called by {@link MeshTopology#onVertexPositionChanged}.
     */
    void markDirty(int uniqueVertexIdx) {
        if (uniqueVertexIdx >= 0 && uniqueVertexIdx < vertexNormalDirty.length) {
            vertexNormalDirty[uniqueVertexIdx] = true;
        }
    }

    private void ensureClean(int uniqueVertexIdx) {
        if (!vertexNormalDirty[uniqueVertexIdx]) {
            return;
        }
        List<Integer> adjacentFaceIds = vertexToFaces.get(uniqueVertexIdx);
        for (int faceId : adjacentFaceIds) {
            faceGeometryCache.ensureFaceClean(faceId);
        }
        vertexNormals[uniqueVertexIdx] = MeshGeometry.computeVertexNormal(
                adjacentFaceIds, faceGeometryCache.normals(), faceGeometryCache.areas());
        vertexNormalDirty[uniqueVertexIdx] = false;
    }
}
