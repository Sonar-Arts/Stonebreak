package com.openmason.main.systems.rendering.model.gmr.topology;

import org.joml.Vector3f;

/**
 * Per-edge dihedral angles with lazy dirty recomputation.
 *
 * <p>Depends on {@link FaceGeometryCache} (ensures face normals are clean
 * before computing angles) and triggers {@link EdgeClassifier#reclassify}
 * after recomputation so edge classifications stay in sync.
 *
 * @see MeshTopologyBuilder
 */
public final class DihedralAngleCache {

    private final MeshEdge[] edges;
    private final float[] dihedralAngles;
    private final boolean[] edgeDirty;
    private final FaceGeometryCache faceGeometryCache;
    private final EdgeClassifier edgeClassifier;

    /**
     * Package-private constructor used by {@link MeshTopologyBuilder}.
     *
     * @param edges             Edge array (shared reference)
     * @param dihedralAngles    Pre-computed dihedral angles (owned by this cache)
     * @param faceGeometryCache Face geometry cache for ensuring normals are clean
     * @param edgeClassifier    Edge classifier to trigger reclassification after recompute
     */
    DihedralAngleCache(MeshEdge[] edges, float[] dihedralAngles,
                       FaceGeometryCache faceGeometryCache, EdgeClassifier edgeClassifier) {
        this.edges = edges;
        this.dihedralAngles = dihedralAngles;
        this.edgeDirty = new boolean[edges.length];
        this.faceGeometryCache = faceGeometryCache;
        this.edgeClassifier = edgeClassifier;
    }

    /**
     * Get the dihedral angle at an edge in radians.
     * Lazily recomputes when dirtied by a vertex move.
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return Angle in radians (0..pi), or {@code Float.NaN} if undefined or out of range
     */
    public float getDihedralAngle(int edgeId) {
        if (edgeId < 0 || edgeId >= edges.length) {
            return Float.NaN;
        }
        ensureClean(edgeId);
        return dihedralAngles[edgeId];
    }

    /**
     * Mark an edge as needing dihedral angle recomputation.
     * Package-private — called by {@link MeshTopology#onVertexPositionChanged}.
     */
    void markEdgeDirty(int edgeId) {
        if (edgeId >= 0 && edgeId < edgeDirty.length) {
            edgeDirty[edgeId] = true;
        }
    }

    /**
     * Ensure the dihedral angle for an edge is up-to-date.
     * Ensures adjacent face normals are clean first, recomputes the angle,
     * then triggers edge reclassification.
     * Package-private — called by {@link MeshTopology} before edge classification queries.
     */
    void ensureClean(int edgeId) {
        if (edgeId < 0 || edgeId >= edgeDirty.length || !edgeDirty[edgeId]) {
            return;
        }
        MeshEdge edge = edges[edgeId];
        for (int faceId : edge.adjacentFaceIds()) {
            faceGeometryCache.ensureFaceClean(faceId);
        }
        dihedralAngles[edgeId] = computeDihedralAngleForEdge(edge);
        edgeClassifier.reclassify(edgeId, dihedralAngles[edgeId]);
        edgeDirty[edgeId] = false;
    }

    /**
     * Direct reference to the dihedral angles array.
     * Package-private — used by {@link MeshTopology#setAutoSharpThreshold}
     * for bulk reclassification via {@link EdgeClassifier#setThreshold}.
     */
    float[] angles() {
        return dihedralAngles;
    }

    private float computeDihedralAngleForEdge(MeshEdge edge) {
        int[] adjFaces = edge.adjacentFaceIds();
        if (adjFaces.length != 2) {
            return Float.NaN;
        }
        Vector3f[] normals = faceGeometryCache.normals();
        int fA = adjFaces[0];
        int fB = adjFaces[1];
        if (fA < 0 || fA >= normals.length || fB < 0 || fB >= normals.length
                || normals[fA] == null || normals[fB] == null) {
            return Float.NaN;
        }
        return MeshGeometry.computeDihedralAngle(normals[fA], normals[fB]);
    }
}
