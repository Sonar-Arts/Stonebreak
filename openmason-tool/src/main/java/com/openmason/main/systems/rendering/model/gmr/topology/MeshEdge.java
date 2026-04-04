package com.openmason.main.systems.rendering.model.gmr.topology;

/**
 * Immutable edge entity with identity and adjacency information.
 * Edges are first-class objects rather than derived artifacts from face geometry.
 *
 * <p>Vertex ordering is canonical: {@code vertexA <= vertexB} to ensure
 * consistent hashing and equality regardless of traversal direction.
 *
 * <p>Adjacency is encoded in {@code adjacentFaceIds}:
 * <ul>
 *   <li>Length 1 → open/outline edge (single face)</li>
 *   <li>Length 2 → boundary edge shared by two faces</li>
 *   <li>Length 3+ → non-manifold edge</li>
 * </ul>
 *
 * @param edgeId            Stable edge identifier (0..N-1)
 * @param vertexA           First unique vertex index (canonical: vertexA &lt;= vertexB)
 * @param vertexB           Second unique vertex index
 * @param adjacentFaceIds   Face IDs sharing this edge (length encodes topology)
 */
public record MeshEdge(
    int edgeId,
    int vertexA,
    int vertexB,
    int[] adjacentFaceIds
) {

    /**
     * Create a MeshEdge with canonical vertex ordering enforced.
     *
     * @param edgeId         Stable edge identifier
     * @param v0             First vertex index (will be reordered if needed)
     * @param v1             Second vertex index (will be reordered if needed)
     * @param adjacentFaceIds Face IDs sharing this edge
     * @return New MeshEdge with vertexA &lt;= vertexB
     */
    public static MeshEdge of(int edgeId, int v0, int v1, int[] adjacentFaceIds) {
        int a = Math.min(v0, v1);
        int b = Math.max(v0, v1);
        return new MeshEdge(edgeId, a, b, adjacentFaceIds != null ? adjacentFaceIds : new int[0]);
    }

    /**
     * Compute a canonical key for HashMap lookup.
     * Packs the two vertex indices into a single long with the smaller index in the high bits.
     *
     * @return Packed long suitable for use as a map key
     */
    public long canonicalKey() {
        return canonicalKey(vertexA, vertexB);
    }

    /**
     * Compute canonical key for any vertex pair (static utility).
     *
     * @param v0 First vertex index
     * @param v1 Second vertex index
     * @return Packed long with min in high bits, max in low bits
     */
    public static long canonicalKey(int v0, int v1) {
        int min = Math.min(v0, v1);
        int max = Math.max(v0, v1);
        return ((long) min << 32) | (max & 0xFFFFFFFFL);
    }

    /**
     * Check if this edge connects to the given vertex.
     *
     * @param vertexIndex Unique vertex index to check
     * @return true if this edge has the vertex as an endpoint
     */
    public boolean connectsVertex(int vertexIndex) {
        return vertexA == vertexIndex || vertexB == vertexIndex;
    }

    /**
     * Check if this edge is a boundary edge (shared by 2+ faces).
     * Single-face edges are open/outline edges.
     *
     * @return true if shared by at least 2 faces
     */
    public boolean isBoundary() {
        return adjacentFaceIds != null && adjacentFaceIds.length >= 2;
    }

    /**
     * Get the number of faces sharing this edge.
     *
     * @return Adjacent face count
     */
    public int adjacentFaceCount() {
        return adjacentFaceIds != null ? adjacentFaceIds.length : 0;
    }
}
