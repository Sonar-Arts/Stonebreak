package com.openmason.main.systems.rendering.model.gmr.topology;

import java.util.List;

/**
 * Stateless vertex classification queries over mesh topology data.
 *
 * <p>All methods are pure read-only queries — no mutable state.
 * Takes shared references to the edge and adjacency arrays owned by
 * {@link MeshTopology}; does not copy or own any data.
 */
public final class VertexClassifier {

    private final MeshEdge[] edges;
    private final List<List<Integer>> vertexToEdges;
    private final boolean uniformTopology;
    private final int uniformVerticesPerFace;

    /**
     * Package-private constructor used by MeshTopology.
     *
     * @param edges                  Edge array (shared reference)
     * @param vertexToEdges          Per-vertex edge ID lists (shared reference)
     * @param uniformTopology        Whether all faces have the same vertex count
     * @param uniformVerticesPerFace Vertices per face (if uniform)
     */
    VertexClassifier(MeshEdge[] edges,
                     List<List<Integer>> vertexToEdges,
                     boolean uniformTopology,
                     int uniformVerticesPerFace) {
        this.edges = edges;
        this.vertexToEdges = vertexToEdges;
        this.uniformTopology = uniformTopology;
        this.uniformVerticesPerFace = uniformVerticesPerFace;
    }

    /**
     * Get the valence (edge count) of a unique vertex.
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return Edge count, or 0 if out of range
     */
    public int getValence(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexToEdges.size()) {
            return 0;
        }
        return vertexToEdges.get(uniqueVertexIdx).size();
    }

    /**
     * Check if a vertex lies on the mesh boundary.
     * A boundary vertex has at least one connected edge with only 1 adjacent face
     * (an open edge).
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return true if any connected edge is open (single adjacent face)
     */
    public boolean isBoundary(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexToEdges.size()) {
            return false;
        }
        List<Integer> edgeIds = vertexToEdges.get(uniqueVertexIdx);
        for (int edgeId : edgeIds) {
            if (edges[edgeId].adjacentFaceCount() == 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a vertex is fully interior (not on the mesh boundary).
     * An interior vertex has all connected edges shared by exactly 2 faces.
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return true if all connected edges are shared by 2 faces
     */
    public boolean isInterior(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexToEdges.size()) {
            return false;
        }
        List<Integer> edgeIds = vertexToEdges.get(uniqueVertexIdx);
        if (edgeIds.isEmpty()) {
            return false;
        }
        for (int edgeId : edgeIds) {
            if (edges[edgeId].adjacentFaceCount() != 2) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if a vertex is a topology pole — a vertex whose valence differs
     * from the expected regular valence for the mesh type.
     *
     * <p>Expected valence by mesh type:
     * <ul>
     *   <li>Quad mesh (4 vertices/face): expected valence = 4</li>
     *   <li>Triangle mesh (3 vertices/face): expected valence = 6</li>
     * </ul>
     *
     * <p>Only meaningful for uniform-topology meshes. Returns false for
     * mixed-topology meshes or unrecognized face types.
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return true if valence differs from the expected regular valence
     */
    public boolean isPole(int uniqueVertexIdx) {
        if (!uniformTopology) {
            return false;
        }
        int expectedValence = switch (uniformVerticesPerFace) {
            case 4 -> 4;  // Quad mesh: regular vertex has 4 edges
            case 3 -> 6;  // Triangle mesh: regular vertex has 6 edges
            default -> -1;
        };
        if (expectedValence == -1) {
            return false;
        }
        return getValence(uniqueVertexIdx) != expectedValence;
    }
}
