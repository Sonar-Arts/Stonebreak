package com.openmason.main.systems.rendering.model.gmr.topology;

/**
 * Topological classification of a mesh edge based on its face adjacency.
 *
 * <p>Determined at build time from {@link MeshEdge#adjacentFaceIds()} length:
 * <ul>
 *   <li>{@link #OPEN} — single adjacent face (mesh boundary / outline)</li>
 *   <li>{@link #MANIFOLD} — exactly two adjacent faces (standard interior edge)</li>
 *   <li>{@link #NON_MANIFOLD} — three or more adjacent faces (degenerate topology)</li>
 * </ul>
 */
public enum EdgeKind {

    /**
     * Edge belongs to only one face — lies on the mesh boundary.
     * Always considered sharp for rendering purposes.
     */
    OPEN,

    /**
     * Edge shared by exactly two faces — standard manifold interior edge.
     * Sharpness determined by dihedral angle threshold or manual override.
     */
    MANIFOLD,

    /**
     * Edge shared by three or more faces — non-manifold topology.
     * Always considered sharp; indicates a topological defect.
     */
    NON_MANIFOLD;

    /**
     * Derive the edge kind from face adjacency count.
     *
     * @param adjacentFaceCount Number of faces sharing the edge
     * @return The corresponding EdgeKind
     */
    public static EdgeKind fromAdjacentFaceCount(int adjacentFaceCount) {
        return switch (adjacentFaceCount) {
            case 0, 1 -> OPEN;
            case 2 -> MANIFOLD;
            default -> NON_MANIFOLD;
        };
    }
}
