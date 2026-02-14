package com.openmason.main.systems.rendering.model.gmr.topology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Traces face loops across quad faces — the face-level traversal
 * primitive for Blender-style Ctrl+Alt+click face selection.
 *
 * <p>A <b>face loop</b> is a row of faces following an edge flow
 * direction. Starting from a face and a direction edge, the walk
 * crosses the direction edge to the adjacent face, then continues
 * through the opposite edge of each subsequent face.
 *
 * <p>The algorithm walks both directions from the start face:
 * <ul>
 *   <li><b>Forward</b>: crosses the direction edge, then continues
 *       through opposite edges in each subsequent quad face</li>
 *   <li><b>Backward</b>: crosses the opposite edge of the direction
 *       edge in the start face, then continues the same way</li>
 * </ul>
 *
 * <p>The walk terminates in each direction when:
 * <ul>
 *   <li>The loop closes (returns to the start face)</li>
 *   <li>A boundary edge is reached (single adjacent face)</li>
 *   <li>A non-quad face is encountered</li>
 *   <li>A non-manifold edge is reached (3+ adjacent faces)</li>
 * </ul>
 *
 * <p>Constructed by {@link MeshTopology} and composed as a sub-service.
 */
public final class FaceLoopTracer {

    private final MeshEdge[] edges;
    private final MeshFace[] faces;

    /**
     * Package-private constructor used by MeshTopology.
     *
     * @param edges Edge array (shared reference, not copied)
     * @param faces Face array (shared reference, not copied)
     */
    FaceLoopTracer(MeshEdge[] edges, MeshFace[] faces) {
        this.edges = edges;
        this.faces = faces;
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Trace the face loop passing through the given start face in the
     * direction defined by an edge.
     *
     * <p>The direction edge determines the flow: the loop walks across
     * this edge to adjacent faces, continuing through opposite edges
     * in each subsequent quad. The walk proceeds in both directions
     * from the start face, producing an ordered list with the start
     * face somewhere in the middle.
     *
     * @param startFaceId     Face identifier to start from
     * @param directionEdgeId Edge identifier giving the flow direction
     *                        (must belong to the start face)
     * @return Ordered list of face IDs forming the loop, or empty list
     *         if either ID is invalid or the edge does not belong to
     *         the start face
     */
    public List<Integer> traceFaceLoop(int startFaceId, int directionEdgeId) {
        MeshFace startFace = getFaceOrNull(startFaceId);
        if (startFace == null || startFace.vertexCount() != 4) {
            return Collections.emptyList();
        }
        if (directionEdgeId < 0 || directionEdgeId >= edges.length) {
            return Collections.emptyList();
        }
        if (findEdgeIndex(startFace, directionEdgeId) < 0) {
            return Collections.emptyList();
        }

        // Walk forward: cross the direction edge
        List<Integer> forward = walkFaceLoop(startFaceId, directionEdgeId);

        // Walk backward: cross the opposite edge of the direction edge
        int oppositeEdgeId = getOppositeEdgeInQuad(startFace, directionEdgeId);
        List<Integer> backward;
        if (oppositeEdgeId >= 0) {
            backward = walkFaceLoop(startFaceId, oppositeEdgeId);
        } else {
            backward = Collections.emptyList();
        }

        // Check if the loop closed — last forward face arrived back at start
        boolean closed = !forward.isEmpty()
                && forward.getLast() == startFaceId;

        if (closed) {
            // Full loop: forward already contains the complete cycle
            // Replace the duplicate start at the end with the start at the front
            List<Integer> result = new ArrayList<>(forward.size());
            result.add(startFaceId);
            result.addAll(forward.subList(0, forward.size() - 1));
            return result;
        }

        // Open loop: combine backward (reversed) + start + forward
        List<Integer> result = new ArrayList<>(backward.size() + 1 + forward.size());
        for (int i = backward.size() - 1; i >= 0; i--) {
            result.add(backward.get(i));
        }
        result.add(startFaceId);
        result.addAll(forward);
        return result;
    }

    // =========================================================================
    // FACE LOOP WALKING
    // =========================================================================

    /**
     * Walk the face loop in one direction from startFaceId by crossing
     * the given edge.
     *
     * @param startFaceId The face we're walking away from
     * @param crossEdgeId The edge to cross first
     * @return List of face IDs visited (excluding startFaceId), or including
     *         startFaceId at the end if the loop closed
     */
    private List<Integer> walkFaceLoop(int startFaceId, int crossEdgeId) {
        List<Integer> result = new ArrayList<>();
        int currentFaceId = startFaceId;
        int currentCrossEdgeId = crossEdgeId;

        while (true) {
            // Cross the edge to the adjacent face
            int nextFaceId = getOtherFaceId(edges[currentCrossEdgeId], currentFaceId);
            if (nextFaceId < 0) {
                break; // Boundary or non-manifold — terminate
            }

            // Loop closed?
            if (nextFaceId == startFaceId) {
                result.add(nextFaceId);
                return result;
            }

            // Prevent infinite loops on degenerate topology
            if (result.contains(nextFaceId)) {
                break;
            }

            MeshFace nextFace = getFaceOrNull(nextFaceId);
            if (nextFace == null || nextFace.vertexCount() != 4) {
                break; // Non-quad or invalid — terminate
            }

            result.add(nextFaceId);

            // Continue through the opposite edge in the next face
            int nextCrossEdgeId = getOppositeEdgeInQuad(nextFace, currentCrossEdgeId);
            if (nextCrossEdgeId < 0) {
                break; // Edge not found in face — shouldn't happen
            }

            currentFaceId = nextFaceId;
            currentCrossEdgeId = nextCrossEdgeId;
        }

        return result;
    }

    // =========================================================================
    // INTERNAL HELPERS
    // =========================================================================

    /**
     * Get the face on the other side of an edge from the known face.
     *
     * @param edge        The edge to cross
     * @param knownFaceId The face we're coming from
     * @return The other face ID, or -1 for boundary (single face) or
     *         non-manifold edges (3+ faces)
     */
    private int getOtherFaceId(MeshEdge edge, int knownFaceId) {
        int[] adjFaces = edge.adjacentFaceIds();
        if (adjFaces == null || adjFaces.length != 2) {
            return -1; // Boundary or non-manifold
        }
        if (adjFaces[0] == knownFaceId) return adjFaces[1];
        if (adjFaces[1] == knownFaceId) return adjFaces[0];
        return -1; // knownFaceId not adjacent — shouldn't happen
    }

    /**
     * Get the opposite edge in a quad face (index + 2).
     *
     * @param face   The quad face
     * @param edgeId The edge to find the opposite of
     * @return The opposite edge ID, or -1 if the edge is not in the face
     */
    private int getOppositeEdgeInQuad(MeshFace face, int edgeId) {
        int idx = findEdgeIndex(face, edgeId);
        if (idx < 0) {
            return -1;
        }
        return face.edgeIds()[(idx + 2) % 4];
    }

    private MeshFace getFaceOrNull(int faceId) {
        if (faceId < 0 || faceId >= faces.length) return null;
        return faces[faceId];
    }

    /**
     * Find the position of an edge in a face's winding order.
     * O(n) scan, where n is the face's vertex count (typically 3-4).
     */
    private static int findEdgeIndex(MeshFace face, int edgeId) {
        int[] faceEdges = face.edgeIds();
        if (faceEdges == null) return -1;
        for (int i = 0; i < faceEdges.length; i++) {
            if (faceEdges[i] == edgeId) return i;
        }
        return -1;
    }
}
