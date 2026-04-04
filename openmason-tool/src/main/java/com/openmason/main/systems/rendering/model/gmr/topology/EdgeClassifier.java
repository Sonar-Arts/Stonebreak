package com.openmason.main.systems.rendering.model.gmr.topology;

import org.joml.Vector3f;

/**
 * Manages per-edge classification metadata as a parallel array alongside
 * {@link MeshEdge}[].
 *
 * <p>Owns the {@link EdgeClassification}[] array, the auto-sharp threshold,
 * and all classification query/mutation logic. Constructed by
 * {@link MeshTopologyBuilder} and composed into {@link MeshTopology}.
 *
 * <p>Auto-sharp rules:
 * <ul>
 *   <li>{@link EdgeKind#OPEN} and {@link EdgeKind#NON_MANIFOLD} edges are always sharp</li>
 *   <li>{@link EdgeKind#MANIFOLD} edges are sharp when their dihedral angle
 *       exceeds the configurable threshold</li>
 * </ul>
 */
public final class EdgeClassifier {

    /** Default auto-sharp threshold: 30 degrees in radians. */
    public static final float DEFAULT_THRESHOLD = (float) Math.toRadians(30.0);

    private final MeshEdge[] edges;
    private final EdgeClassification[] classifications;
    private float thresholdRadians;

    /**
     * Package-private constructor used by MeshTopology.
     *
     * @param edges              Edge array (shared reference, not copied)
     * @param thresholdRadians   Initial auto-sharp threshold in radians
     * @param faceNormals        Precomputed face normals for dihedral angle computation
     */
    EdgeClassifier(MeshEdge[] edges, float thresholdRadians, Vector3f[] faceNormals) {
        this.edges = edges;
        this.thresholdRadians = Math.clamp(thresholdRadians, 0.0f, (float) Math.PI);
        this.classifications = new EdgeClassification[edges.length];

        for (int i = 0; i < edges.length; i++) {
            float angle = computeDihedralAngle(edges[i], faceNormals);
            classifications[i] = classify(edges[i], angle);
        }
    }

    // =========================================================================
    // QUERIES
    // =========================================================================

    /**
     * Get the classification for an edge.
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return The classification, or null if out of range
     */
    public EdgeClassification get(int edgeId) {
        if (edgeId < 0 || edgeId >= classifications.length) {
            return null;
        }
        return classifications[edgeId];
    }

    /**
     * Get the topological kind of an edge.
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return The edge kind, or null if out of range
     */
    public EdgeKind getKind(int edgeId) {
        EdgeClassification c = get(edgeId);
        return c != null ? c.kind() : null;
    }

    /**
     * Check if an edge is sharp (produces a hard normal break).
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return true if sharp, false if smooth or out of range
     */
    public boolean isSharp(int edgeId) {
        EdgeClassification c = get(edgeId);
        return c != null && c.sharp();
    }

    /**
     * Check if an edge is a UV seam.
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return true if seam, false otherwise or out of range
     */
    public boolean isSeam(int edgeId) {
        EdgeClassification c = get(edgeId);
        return c != null && c.seam();
    }

    /**
     * Get the crease weight for an edge.
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return Crease weight in [0, 1], or {@code Float.NaN} if out of range
     */
    public float getCreaseWeight(int edgeId) {
        EdgeClassification c = get(edgeId);
        return c != null ? c.creaseWeight() : Float.NaN;
    }

    /**
     * Check if an edge is open (boundary — single adjacent face).
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return true if the edge is open
     */
    public boolean isOpen(int edgeId) {
        return getKind(edgeId) == EdgeKind.OPEN;
    }

    /**
     * Check if an edge is manifold (exactly two adjacent faces).
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return true if the edge is manifold
     */
    public boolean isManifold(int edgeId) {
        return getKind(edgeId) == EdgeKind.MANIFOLD;
    }

    /**
     * Check if an edge is non-manifold (three or more adjacent faces).
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return true if the edge is non-manifold
     */
    public boolean isNonManifold(int edgeId) {
        return getKind(edgeId) == EdgeKind.NON_MANIFOLD;
    }

    // =========================================================================
    // MUTATIONS
    // =========================================================================

    /**
     * Manually override the sharp flag for an edge.
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @param sharp  New sharp value
     */
    public void setSharp(int edgeId, boolean sharp) {
        if (edgeId >= 0 && edgeId < classifications.length) {
            classifications[edgeId] = classifications[edgeId].withSharp(sharp);
        }
    }

    /**
     * Manually override the seam flag for an edge.
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @param seam   New seam value
     */
    public void setSeam(int edgeId, boolean seam) {
        if (edgeId >= 0 && edgeId < classifications.length) {
            classifications[edgeId] = classifications[edgeId].withSeam(seam);
        }
    }

    /**
     * Manually override the crease weight for an edge.
     *
     * @param edgeId       Edge identifier (0..edgeCount-1)
     * @param creaseWeight New crease weight (clamped to [0, 1])
     */
    public void setCreaseWeight(int edgeId, float creaseWeight) {
        if (edgeId >= 0 && edgeId < classifications.length) {
            classifications[edgeId] = classifications[edgeId].withCreaseWeight(creaseWeight);
        }
    }

    // =========================================================================
    // AUTO-SHARP THRESHOLD
    // =========================================================================

    /**
     * Get the current auto-sharp dihedral angle threshold in radians.
     *
     * @return Threshold in radians (0..π)
     */
    public float getThresholdRadians() {
        return thresholdRadians;
    }

    /**
     * Get the current auto-sharp dihedral angle threshold in degrees.
     *
     * @return Threshold in degrees (0..180)
     */
    public float getThresholdDegrees() {
        return (float) Math.toDegrees(thresholdRadians);
    }

    /**
     * Update the auto-sharp threshold and reclassify all manifold edges.
     * Open and non-manifold edges remain always-sharp regardless of threshold.
     *
     * @param thresholdRadians New threshold in radians (clamped to [0, π])
     * @param dihedralAngles   Current dihedral angles array (indexed by edgeId)
     */
    public void setThreshold(float thresholdRadians, float[] dihedralAngles) {
        this.thresholdRadians = Math.clamp(thresholdRadians, 0.0f, (float) Math.PI);
        for (int i = 0; i < edges.length; i++) {
            classifications[i] = classify(edges[i], dihedralAngles[i]);
        }
    }

    /**
     * Update the auto-sharp threshold (in degrees) and reclassify all manifold edges.
     *
     * @param thresholdDegrees New threshold in degrees (clamped to [0, 180])
     * @param dihedralAngles   Current dihedral angles array (indexed by edgeId)
     */
    public void setThresholdDegrees(float thresholdDegrees, float[] dihedralAngles) {
        setThreshold(
            (float) Math.toRadians(Math.clamp(thresholdDegrees, 0.0f, 180.0f)),
            dihedralAngles
        );
    }

    // =========================================================================
    // RECLASSIFICATION (called by MeshTopology on dirty edge recomputation)
    // =========================================================================

    /**
     * Reclassify a single edge after its dihedral angle changed.
     *
     * @param edgeId        Edge identifier
     * @param dihedralAngle Updated dihedral angle in radians (may be NaN)
     */
    void reclassify(int edgeId, float dihedralAngle) {
        if (edgeId >= 0 && edgeId < classifications.length) {
            classifications[edgeId] = classify(edges[edgeId], dihedralAngle);
        }
    }

    // =========================================================================
    // COUNTING
    // =========================================================================

    /**
     * Count edges by kind.
     *
     * @param kind The edge kind to count
     * @return Number of edges with the given kind
     */
    public int countByKind(EdgeKind kind) {
        int count = 0;
        for (EdgeClassification c : classifications) {
            if (c.kind() == kind) {
                count++;
            }
        }
        return count;
    }

    /**
     * Count sharp edges.
     *
     * @return Number of edges marked as sharp
     */
    public int countSharp() {
        int count = 0;
        for (EdgeClassification c : classifications) {
            if (c.sharp()) {
                count++;
            }
        }
        return count;
    }

    // =========================================================================
    // INTERNAL
    // =========================================================================

    /**
     * Classify a single edge based on its topology and dihedral angle.
     */
    private EdgeClassification classify(MeshEdge edge, float dihedralAngle) {
        EdgeKind kind = EdgeKind.fromAdjacentFaceCount(edge.adjacentFaceCount());
        return switch (kind) {
            case OPEN -> EdgeClassification.open();
            case NON_MANIFOLD -> EdgeClassification.nonManifold();
            case MANIFOLD -> {
                boolean sharp = !Float.isNaN(dihedralAngle) && dihedralAngle >= thresholdRadians;
                float crease = Float.isNaN(dihedralAngle) ? 0.0f : dihedralAngle / (float) Math.PI;
                yield EdgeClassification.manifold(sharp, crease);
            }
        };
    }

    /**
     * Compute dihedral angle for an edge from face normals.
     */
    private static float computeDihedralAngle(MeshEdge edge, Vector3f[] faceNormals) {
        int[] adjFaces = edge.adjacentFaceIds();
        if (adjFaces.length != 2) {
            return Float.NaN;
        }
        int fA = adjFaces[0];
        int fB = adjFaces[1];
        if (fA < 0 || fA >= faceNormals.length || fB < 0 || fB >= faceNormals.length
                || faceNormals[fA] == null || faceNormals[fB] == null) {
            return Float.NaN;
        }
        return MeshGeometry.computeDihedralAngle(faceNormals[fA], faceNormals[fB]);
    }
}
