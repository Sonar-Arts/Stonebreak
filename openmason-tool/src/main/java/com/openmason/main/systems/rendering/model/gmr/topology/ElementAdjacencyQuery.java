package com.openmason.main.systems.rendering.model.gmr.topology;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Read-only element adjacency lookups for vertices, edges, and faces.
 *
 * <p>Provides O(1) adjacency queries via pre-built indices.
 * No dirty tracking, no dependencies on other sub-services.
 *
 * @see MeshTopologyBuilder
 */
public final class ElementAdjacencyQuery {

    private final MeshEdge[] edges;
    private final MeshFace[] faces;
    private final List<List<Integer>> vertexToEdges;
    private final List<List<Integer>> vertexToFaces;
    private final List<List<Integer>> faceToAdjacentFaces;
    private final Map<Long, Integer> facePairToEdgeId;

    /**
     * Package-private constructor used by {@link MeshTopologyBuilder}.
     */
    ElementAdjacencyQuery(MeshEdge[] edges, MeshFace[] faces,
                          List<List<Integer>> vertexToEdges,
                          List<List<Integer>> vertexToFaces,
                          List<List<Integer>> faceToAdjacentFaces,
                          Map<Long, Integer> facePairToEdgeId) {
        this.edges = edges;
        this.faces = faces;
        this.vertexToEdges = vertexToEdges;
        this.vertexToFaces = vertexToFaces;
        this.faceToAdjacentFaces = faceToAdjacentFaces;
        this.facePairToEdgeId = facePairToEdgeId;
    }

    /**
     * Get all edge IDs connected to a unique vertex.
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return Unmodifiable list of edge IDs, or empty list if out of range
     */
    public List<Integer> getEdgesForVertex(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexToEdges.size()) {
            return Collections.emptyList();
        }
        return vertexToEdges.get(uniqueVertexIdx);
    }

    /**
     * Get all face IDs adjacent to a unique vertex.
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return Unmodifiable list of face IDs, or empty list if out of range
     */
    public List<Integer> getFacesForVertex(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexToFaces.size()) {
            return Collections.emptyList();
        }
        return vertexToFaces.get(uniqueVertexIdx);
    }

    /**
     * Get all face IDs adjacent to a given face (sharing an edge).
     *
     * @param faceId Face identifier
     * @return Unmodifiable list of neighbor face IDs, or empty list if out of range
     */
    public List<Integer> getAdjacentFaces(int faceId) {
        if (faceId < 0 || faceId >= faceToAdjacentFaces.size()) {
            return Collections.emptyList();
        }
        return faceToAdjacentFaces.get(faceId);
    }

    /**
     * Get the edge shared by two adjacent faces.
     *
     * @param faceIdA First face identifier
     * @param faceIdB Second face identifier
     * @return The shared edge, or null if the faces are not adjacent
     */
    public MeshEdge getSharedEdge(int faceIdA, int faceIdB) {
        long key = MeshGeometry.canonicalFacePairKey(faceIdA, faceIdB);
        Integer edgeId = facePairToEdgeId.get(key);
        return edgeId != null ? edges[edgeId] : null;
    }

    /**
     * Get the face IDs adjacent to an edge.
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return Array of adjacent face IDs, or empty array if out of range
     */
    public int[] getFacesForEdge(int edgeId) {
        if (edgeId < 0 || edgeId >= edges.length) {
            return new int[0];
        }
        return edges[edgeId].adjacentFaceIds();
    }

    /**
     * Get the face on the other side of an edge from a known face.
     * Only works for manifold edges (exactly 2 adjacent faces).
     *
     * @param edgeId      Edge identifier (0..edgeCount-1)
     * @param knownFaceId The face we're coming from
     * @return The other face ID, or -1 if boundary, non-manifold, out of range,
     *         or knownFaceId is not adjacent
     */
    public int getOtherFace(int edgeId, int knownFaceId) {
        if (edgeId < 0 || edgeId >= edges.length) {
            return -1;
        }
        int[] adjFaces = edges[edgeId].adjacentFaceIds();
        if (adjFaces == null || adjFaces.length != 2) {
            return -1;
        }
        if (adjFaces[0] == knownFaceId) return adjFaces[1];
        if (adjFaces[1] == knownFaceId) return adjFaces[0];
        return -1;
    }

    /**
     * Get the edge across a quad face from the given edge (the opposite edge).
     * Only meaningful for quad faces (4 vertices/edges).
     *
     * @param faceId Face identifier (must be a quad)
     * @param edgeId Edge identifier (must belong to this face)
     * @return The opposite edge ID, or -1 if not a quad, edge not in face, or out of range
     */
    public int getOppositeEdge(int faceId, int edgeId) {
        if (faceId < 0 || faceId >= faces.length) {
            return -1;
        }
        MeshFace face = faces[faceId];
        if (face.vertexCount() != 4) {
            return -1;
        }
        int[] faceEdges = face.edgeIds();
        if (faceEdges == null) {
            return -1;
        }
        for (int i = 0; i < faceEdges.length; i++) {
            if (faceEdges[i] == edgeId) {
                return faceEdges[(i + 2) % 4];
            }
        }
        return -1;
    }
}
