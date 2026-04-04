package com.openmason.main.systems.rendering.model.gmr.topology;

/**
 * Winding-aware directed edge traversal within faces.
 *
 * <p>For each face, {@link MeshFace#edgeIds()} stores edges in winding order
 * where {@code edgeIds[i]} connects {@code vertexIndices[i]} to
 * {@code vertexIndices[(i+1) % length]}. This service exposes that ordering
 * as a clean traversal API without requiring callers to index into raw arrays.
 *
 * <p>Constructed by {@link MeshTopology} and composed as a sub-service.
 * All methods are O(n) in face vertex count (typically 3–6).
 */
public final class FaceEdgeTraversal {

    private final MeshFace[] faces;

    /**
     * Package-private constructor used by MeshTopology.
     *
     * @param faces Face array (shared reference, not copied)
     */
    FaceEdgeTraversal(MeshFace[] faces) {
        this.faces = faces;
    }

    // =========================================================================
    // EDGE TRAVERSAL
    // =========================================================================

    /**
     * Get the next edge in the face's winding order after the given edge.
     *
     * <p>For a quad with edges [e0, e1, e2, e3]:
     * {@code getNextEdgeInFace(faceId, e1)} returns {@code e2},
     * and {@code getNextEdgeInFace(faceId, e3)} wraps to {@code e0}.
     *
     * @param faceId Face identifier
     * @param edgeId Edge identifier (must belong to this face)
     * @return The next edge ID, or -1 if faceId or edgeId is invalid
     */
    public int getNextEdgeInFace(int faceId, int edgeId) {
        MeshFace face = getFaceOrNull(faceId);
        if (face == null) return -1;
        int idx = findEdgeIndex(face, edgeId);
        if (idx < 0) return -1;
        int[] edges = face.edgeIds();
        return edges[(idx + 1) % edges.length];
    }

    /**
     * Get the previous edge in the face's winding order before the given edge.
     *
     * <p>For a quad with edges [e0, e1, e2, e3]:
     * {@code getPrevEdgeInFace(faceId, e1)} returns {@code e0},
     * and {@code getPrevEdgeInFace(faceId, e0)} wraps to {@code e3}.
     *
     * @param faceId Face identifier
     * @param edgeId Edge identifier (must belong to this face)
     * @return The previous edge ID, or -1 if faceId or edgeId is invalid
     */
    public int getPrevEdgeInFace(int faceId, int edgeId) {
        MeshFace face = getFaceOrNull(faceId);
        if (face == null) return -1;
        int idx = findEdgeIndex(face, edgeId);
        if (idx < 0) return -1;
        int[] edges = face.edgeIds();
        return edges[(idx - 1 + edges.length) % edges.length];
    }

    // =========================================================================
    // DIRECTED VERTEX QUERIES
    // =========================================================================

    /**
     * Get the directed (from, to) vertex pair for an edge within a face's winding.
     *
     * <p>{@link MeshEdge} stores vertices in canonical order (vertexA &le; vertexB),
     * but within a specific face the edge may flow in either direction. This method
     * returns the vertices in the face's actual winding order.
     *
     * @param faceId Face identifier
     * @param edgeId Edge identifier (must belong to this face)
     * @return Two-element array [fromVertex, toVertex] in winding order,
     *         or null if faceId or edgeId is invalid
     */
    public int[] getDirectedVertices(int faceId, int edgeId) {
        MeshFace face = getFaceOrNull(faceId);
        if (face == null) return null;
        int idx = findEdgeIndex(face, edgeId);
        if (idx < 0) return null;
        int[] verts = face.vertexIndices();
        return new int[]{ verts[idx], verts[(idx + 1) % verts.length] };
    }

    // =========================================================================
    // INTERNAL
    // =========================================================================

    private MeshFace getFaceOrNull(int faceId) {
        if (faceId < 0 || faceId >= faces.length) return null;
        return faces[faceId];
    }

    private static int findEdgeIndex(MeshFace face, int edgeId) {
        int[] edges = face.edgeIds();
        if (edges == null) return -1;
        for (int i = 0; i < edges.length; i++) {
            if (edges[i] == edgeId) return i;
        }
        return -1;
    }
}
