package com.openmason.main.systems.rendering.model.gmr.topology;

import java.util.List;
import java.util.Map;

/**
 * Vertex-to-vertex adjacency queries over mesh topology data.
 *
 * <p>Two vertices are adjacent if they share an edge. This service
 * provides O(1) connectivity checks via the canonical edge key map
 * and a neighbor count alias for clarity alongside valence.
 *
 * <p>Constructed by {@link MeshTopology} and composed as a sub-service.
 * All methods are pure read-only queries — no mutable state.
 */
public final class VertexAdjacencyQuery {

    private final Map<Long, Integer> edgeKeyToId;
    private final MeshEdge[] edges;
    private final List<List<Integer>> vertexToEdges;

    /**
     * Package-private constructor used by MeshTopology.
     *
     * @param edgeKeyToId   Canonical vertex-pair key → edge ID map (shared reference)
     * @param edges         Edge array (shared reference)
     * @param vertexToEdges Per-vertex edge ID lists (shared reference)
     */
    VertexAdjacencyQuery(Map<Long, Integer> edgeKeyToId,
                         MeshEdge[] edges,
                         List<List<Integer>> vertexToEdges) {
        this.edgeKeyToId = edgeKeyToId;
        this.edges = edges;
        this.vertexToEdges = vertexToEdges;
    }

    /**
     * Check whether two vertices are directly connected by an edge.
     *
     * <p>O(1) via canonical edge key hash map lookup.
     *
     * @param v0 First unique vertex index
     * @param v1 Second unique vertex index
     * @return true if an edge connects v0 and v1
     */
    public boolean areVerticesConnected(int v0, int v1) {
        if (v0 == v1) {
            return false;
        }
        long key = MeshEdge.canonicalKey(v0, v1);
        return edgeKeyToId.containsKey(key);
    }

    /**
     * Get the edge connecting two vertices, if one exists.
     *
     * <p>O(1) via canonical edge key hash map lookup.
     *
     * @param v0 First unique vertex index
     * @param v1 Second unique vertex index
     * @return The connecting edge, or null if the vertices are not adjacent
     */
    public MeshEdge getConnectingEdge(int v0, int v1) {
        if (v0 == v1) {
            return null;
        }
        long key = MeshEdge.canonicalKey(v0, v1);
        Integer id = edgeKeyToId.get(key);
        return id != null ? edges[id] : null;
    }

    /**
     * Get the number of vertices directly connected to a vertex by edges.
     *
     * <p>Equivalent to valence (edge count) — provided as a semantic alias
     * for readability when the intent is "how many neighbors does this vertex have?"
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return Neighbor count, or 0 if out of range
     */
    public int getNeighborCount(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexToEdges.size()) {
            return 0;
        }
        return vertexToEdges.get(uniqueVertexIdx).size();
    }
}
