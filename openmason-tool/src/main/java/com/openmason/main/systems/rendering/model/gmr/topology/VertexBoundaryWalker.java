package com.openmason.main.systems.rendering.model.gmr.topology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Boundary edge chain walker: traces the mesh boundary from a given vertex.
 *
 * <p>Given a boundary vertex (one connected to at least one open edge with
 * {@code adjacentFaceCount == 1}), walks along the boundary edge chain and
 * returns an ordered list of unique vertex IDs along the mesh outline.
 *
 * <p>Useful for selecting mesh outlines, open edge loops, and hole detection.
 *
 * <p>Constructed by {@link MeshTopology} and composed as a sub-service.
 * All methods are pure read-only queries — no mutable state.
 */
public final class VertexBoundaryWalker {

    private final MeshEdge[] edges;
    private final List<List<Integer>> vertexToEdges;

    /**
     * Package-private constructor used by MeshTopology.
     *
     * @param edges         Edge array (shared reference)
     * @param vertexToEdges Per-vertex edge ID lists (shared reference)
     */
    VertexBoundaryWalker(MeshEdge[] edges, List<List<Integer>> vertexToEdges) {
        this.edges = edges;
        this.vertexToEdges = vertexToEdges;
    }

    /**
     * Trace the boundary edge chain starting from a boundary vertex.
     *
     * <p>Walks along open edges ({@code adjacentFaceCount == 1}) collecting each
     * vertex in traversal order. If the boundary forms a closed loop, the walk
     * stops when it returns to the start vertex. If the boundary is open-ended,
     * the walk stops when no further boundary edge can be found.
     *
     * <p>Returns an empty list if:
     * <ul>
     *   <li>The vertex index is out of range</li>
     *   <li>The vertex is not a boundary vertex (no connected open edges)</li>
     * </ul>
     *
     * @param uniqueVertexIdx Unique vertex index to start from
     * @return Unmodifiable ordered list of unique vertex IDs along the boundary chain,
     *         including the start vertex as the first element
     */
    public List<Integer> traceBoundaryFromVertex(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexToEdges.size()) {
            return Collections.emptyList();
        }

        int startBoundaryEdge = findBoundaryEdge(uniqueVertexIdx);
        if (startBoundaryEdge == -1) {
            return Collections.emptyList();
        }

        List<Integer> chain = new ArrayList<>();
        chain.add(uniqueVertexIdx);

        int currentVertex = uniqueVertexIdx;
        int currentEdgeId = startBoundaryEdge;

        while (true) {
            int nextVertex = otherEndpoint(edges[currentEdgeId], currentVertex);

            if (nextVertex == uniqueVertexIdx) {
                break; // Closed loop — returned to start
            }

            chain.add(nextVertex);

            int nextEdgeId = findNextBoundaryEdge(nextVertex, currentEdgeId);
            if (nextEdgeId == -1) {
                break; // Open chain — no further boundary edge
            }

            currentVertex = nextVertex;
            currentEdgeId = nextEdgeId;
        }

        return Collections.unmodifiableList(chain);
    }

    // =========================================================================
    // INTERNAL
    // =========================================================================

    /**
     * Find any boundary edge (adjacentFaceCount == 1) connected to the given vertex.
     *
     * @return Edge ID of a boundary edge, or -1 if none found
     */
    private int findBoundaryEdge(int uniqueVertexIdx) {
        List<Integer> edgeIds = vertexToEdges.get(uniqueVertexIdx);
        for (int edgeId : edgeIds) {
            if (edges[edgeId].adjacentFaceCount() == 1) {
                return edgeId;
            }
        }
        return -1;
    }

    /**
     * Find the next boundary edge at a vertex, excluding the edge we arrived on.
     *
     * @param vertexIdx      Current vertex
     * @param excludeEdgeId  Edge we just traversed (must not revisit)
     * @return Edge ID of the next boundary edge, or -1 if none found
     */
    private int findNextBoundaryEdge(int vertexIdx, int excludeEdgeId) {
        List<Integer> edgeIds = vertexToEdges.get(vertexIdx);
        for (int edgeId : edgeIds) {
            if (edgeId != excludeEdgeId && edges[edgeId].adjacentFaceCount() == 1) {
                return edgeId;
            }
        }
        return -1;
    }

    /**
     * Get the vertex at the other end of an edge from the given vertex.
     */
    private static int otherEndpoint(MeshEdge edge, int vertex) {
        return edge.vertexA() == vertex ? edge.vertexB() : edge.vertexA();
    }
}
