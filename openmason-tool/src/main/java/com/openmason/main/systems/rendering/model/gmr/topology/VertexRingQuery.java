package com.openmason.main.systems.rendering.model.gmr.topology;

import java.util.*;

/**
 * Vertex ring queries: connected vertices and ordered vertex rings.
 *
 * <p>Provides two query types:
 * <ul>
 *   <li>{@link #getConnectedVertices(int)} — unordered list of adjacent unique vertex IDs</li>
 *   <li>{@link #getOrderedVertexRing(int)} — vertices in winding order around the center vertex</li>
 * </ul>
 *
 * <p>The ordered ring walks adjacent faces around the center vertex, collecting
 * the "next" vertex (in face winding order) from each face. For boundary vertices,
 * the walk starts from a boundary edge and appends the trailing vertex at the end.
 *
 * <p>Constructed by {@link MeshTopology} and composed as a sub-service.
 * All methods are pure read-only queries — no mutable state.
 */
public final class VertexRingQuery {

    private final MeshEdge[] edges;
    private final MeshFace[] faces;
    private final List<List<Integer>> vertexToEdges;
    private final List<List<Integer>> vertexToFaces;
    private final Map<Long, Integer> edgeKeyToId;

    /**
     * Package-private constructor used by MeshTopology.
     *
     * @param edges          Edge array (shared reference)
     * @param faces          Face array (shared reference)
     * @param vertexToEdges  Per-vertex edge ID lists (shared reference)
     * @param vertexToFaces  Per-vertex face ID lists (shared reference)
     * @param edgeKeyToId    Canonical vertex-pair key → edge ID map (shared reference)
     */
    VertexRingQuery(MeshEdge[] edges, MeshFace[] faces,
                    List<List<Integer>> vertexToEdges,
                    List<List<Integer>> vertexToFaces,
                    Map<Long, Integer> edgeKeyToId) {
        this.edges = edges;
        this.faces = faces;
        this.vertexToEdges = vertexToEdges;
        this.vertexToFaces = vertexToFaces;
        this.edgeKeyToId = edgeKeyToId;
    }

    /**
     * Get all vertices connected to a vertex by edges (unordered).
     *
     * <p>For each edge incident to the given vertex, collects the other endpoint.
     * The result order matches the edge list order (not geometrically meaningful).
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return Unmodifiable list of adjacent unique vertex IDs, or empty list if out of range
     */
    public List<Integer> getConnectedVertices(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexToEdges.size()) {
            return Collections.emptyList();
        }
        List<Integer> edgeIds = vertexToEdges.get(uniqueVertexIdx);
        if (edgeIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> result = new ArrayList<>(edgeIds.size());
        for (int edgeId : edgeIds) {
            MeshEdge edge = edges[edgeId];
            int other = edge.vertexA() == uniqueVertexIdx ? edge.vertexB() : edge.vertexA();
            result.add(other);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Get the ordered ring of vertices connected to a vertex, sorted by face winding.
     *
     * <p>Walks adjacent faces in order around the center vertex, collecting the
     * "next" vertex (in face winding order) from each face. For boundary vertices,
     * starts from a boundary edge and appends the trailing "previous" vertex from
     * the first face at the end.
     *
     * <p>For interior vertices, the ring forms a closed loop (first vertex is adjacent
     * to last via the center vertex). For boundary vertices, the ring covers the full
     * open fan from one boundary edge to the other.
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return Unmodifiable list of vertices in winding order around the center,
     *         or empty list if out of range or no adjacent faces
     */
    public List<Integer> getOrderedVertexRing(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexToEdges.size()) {
            return Collections.emptyList();
        }
        List<Integer> adjEdges = vertexToEdges.get(uniqueVertexIdx);
        List<Integer> adjFaces = vertexToFaces.get(uniqueVertexIdx);
        if (adjEdges.isEmpty() || adjFaces.isEmpty()) {
            return Collections.emptyList();
        }

        // Find boundary edges (edges with exactly 1 adjacent face)
        int firstBoundaryEdge = -1;
        int secondBoundaryEdge = -1;
        for (int eid : adjEdges) {
            if (edges[eid].adjacentFaceCount() == 1) {
                if (firstBoundaryEdge == -1) {
                    firstBoundaryEdge = eid;
                } else {
                    secondBoundaryEdge = eid;
                }
            }
        }
        boolean isBoundary = firstBoundaryEdge != -1;

        // Determine starting face
        int startFace;
        if (isBoundary) {
            // Start from the boundary edge whose face has it as the "prev" edge,
            // so walking "next" covers the entire fan
            startFace = findBoundaryStartFace(uniqueVertexIdx, firstBoundaryEdge, secondBoundaryEdge);
        } else {
            startFace = adjFaces.get(0);
        }

        // Walk the face fan collecting the "next" vertex from each face
        List<Integer> ring = new ArrayList<>(adjEdges.size());
        Set<Integer> visited = new HashSet<>();
        int currentFace = startFace;

        while (currentFace >= 0 && currentFace < faces.length && !visited.contains(currentFace)) {
            visited.add(currentFace);
            MeshFace face = faces[currentFace];
            int[] verts = face.vertexIndices();
            int pos = indexOf(uniqueVertexIdx, verts);
            if (pos < 0) break;

            int nextVert = verts[(pos + 1) % verts.length];
            ring.add(nextVert);

            // Cross to the adjacent face via the edge (center → nextVert)
            currentFace = crossToOtherFace(uniqueVertexIdx, nextVert, currentFace);
        }

        // For boundary vertices, the vertex before center in the start face
        // was not collected during the walk — append it now
        if (isBoundary && startFace >= 0 && startFace < faces.length) {
            MeshFace face = faces[startFace];
            int[] verts = face.vertexIndices();
            int pos = indexOf(uniqueVertexIdx, verts);
            if (pos >= 0) {
                int prevVert = verts[(pos - 1 + verts.length) % verts.length];
                ring.add(prevVert);
            }
        }

        return Collections.unmodifiableList(ring);
    }

    // =========================================================================
    // INTERNAL
    // =========================================================================

    /**
     * Find the correct starting face for a boundary vertex walk.
     * Selects the boundary edge whose face has it as the "previous" edge
     * relative to the center vertex, ensuring the forward walk covers all faces.
     */
    private int findBoundaryStartFace(int centerVertex, int boundaryEdge1, int boundaryEdge2) {
        MeshEdge edge = edges[boundaryEdge1];
        int startFace = edge.adjacentFaceIds()[0];

        int otherVertex = edge.vertexA() == centerVertex ? edge.vertexB() : edge.vertexA();
        if (startFace < 0 || startFace >= faces.length) {
            return startFace;
        }
        MeshFace face = faces[startFace];
        int[] verts = face.vertexIndices();
        int pos = indexOf(centerVertex, verts);
        if (pos < 0) return startFace;

        int prevInFace = verts[(pos - 1 + verts.length) % verts.length];
        if (prevInFace == otherVertex) {
            // Boundary edge is the "prev" edge — correct orientation
            return startFace;
        }

        // First boundary edge has wrong orientation; use the second
        if (boundaryEdge2 != -1) {
            return edges[boundaryEdge2].adjacentFaceIds()[0];
        }

        return startFace;
    }

    /**
     * Cross an edge (defined by two vertices) to the face on the other side.
     *
     * @return The other face ID, or -1 if boundary or edge not found
     */
    private int crossToOtherFace(int v0, int v1, int currentFace) {
        long key = MeshEdge.canonicalKey(v0, v1);
        Integer edgeId = edgeKeyToId.get(key);
        if (edgeId == null) return -1;

        int[] adjFaces = edges[edgeId].adjacentFaceIds();
        if (adjFaces.length != 2) return -1;

        if (adjFaces[0] == currentFace) return adjFaces[1];
        if (adjFaces[1] == currentFace) return adjFaces[0];
        return -1;
    }

    private static int indexOf(int value, int[] array) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) return i;
        }
        return -1;
    }
}
