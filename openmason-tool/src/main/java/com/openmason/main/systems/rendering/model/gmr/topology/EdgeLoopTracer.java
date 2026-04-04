package com.openmason.main.systems.rendering.model.gmr.topology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Traces edge loops and edge rings across quad faces — the traversal
 * primitives needed for Blender-style mesh selection operations.
 *
 * <p>An <b>edge loop</b> walks through <em>opposite</em> edges across
 * adjacent quad faces: from edge E in face F, the next loop edge is the
 * edge at index {@code (i + 2) % 4} where {@code i} is E's position in
 * F's winding order. The walk continues by crossing to the other face
 * sharing that opposite edge.
 *
 * <p>An <b>edge ring</b> walks a perpendicular face strip: from edge E
 * in face F, step to the perpendicular connecting edge at
 * {@code (i + 1) % 4}, cross it to the next face, then take the parallel
 * edge at {@code (j + 1) % 4} as the next ring edge.
 *
 * <p>Both algorithms degrade gracefully on non-quad faces — the walk
 * terminates and partial results are returned. Boundary edges (single
 * adjacent face) also terminate the walk.
 *
 * <p>Constructed by {@link MeshTopology} and composed as a sub-service.
 */
public final class EdgeLoopTracer {

    private final MeshEdge[] edges;
    private final MeshFace[] faces;

    /**
     * Package-private constructor used by MeshTopology.
     *
     * @param edges Edge array (shared reference, not copied)
     * @param faces Face array (shared reference, not copied)
     */
    EdgeLoopTracer(MeshEdge[] edges, MeshFace[] faces) {
        this.edges = edges;
        this.faces = faces;
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Trace the edge loop passing through the given start edge.
     *
     * <p>Walks both directions from the start edge across opposite edges
     * of adjacent quad faces. The result is an ordered list of edge IDs
     * with the start edge somewhere in the middle.
     *
     * <p>The walk terminates in each direction when:
     * <ul>
     *   <li>The loop closes (returns to the start edge)</li>
     *   <li>A boundary edge is reached (single adjacent face)</li>
     *   <li>A non-quad face is encountered</li>
     *   <li>A non-manifold edge is reached (3+ adjacent faces)</li>
     * </ul>
     *
     * @param startEdgeId Edge identifier to start from (0..edgeCount-1)
     * @return Ordered list of edge IDs forming the loop, or empty list if
     *         the edge ID is invalid
     */
    public List<Integer> traceEdgeLoop(int startEdgeId) {
        if (startEdgeId < 0 || startEdgeId >= edges.length) {
            return Collections.emptyList();
        }

        MeshEdge startEdge = edges[startEdgeId];
        int[] adjFaces = startEdge.adjacentFaceIds();

        // Isolated edge with no faces
        if (adjFaces == null || adjFaces.length == 0) {
            return List.of(startEdgeId);
        }

        // Walk forward (using first adjacent face)
        List<Integer> forward = walkLoop(startEdgeId, adjFaces[0]);

        // Walk backward (using second adjacent face, if manifold)
        List<Integer> backward;
        if (adjFaces.length >= 2) {
            backward = walkLoop(startEdgeId, adjFaces[1]);
        } else {
            backward = Collections.emptyList();
        }

        // Check if the loop closed — last forward edge arrived back at start
        boolean closed = !forward.isEmpty()
                && forward.getLast() == startEdgeId;

        if (closed) {
            // Full loop: forward already contains the complete cycle
            // Remove the duplicate start at the end
            return forward.subList(0, forward.size() - 1);
        }

        // Open loop: combine backward (reversed) + start + forward
        List<Integer> result = new ArrayList<>(backward.size() + 1 + forward.size());
        for (int i = backward.size() - 1; i >= 0; i--) {
            result.add(backward.get(i));
        }
        result.add(startEdgeId);
        result.addAll(forward);
        return result;
    }

    /**
     * Trace the edge ring passing through the given start edge.
     *
     * <p>Walks both directions from the start edge through a perpendicular
     * face strip of adjacent quad faces. The result is an ordered list of
     * parallel edge IDs with the start edge somewhere in the middle.
     *
     * <p>The walk terminates in each direction when:
     * <ul>
     *   <li>The ring closes (returns to the start edge)</li>
     *   <li>A boundary edge is reached (single adjacent face)</li>
     *   <li>A non-quad face is encountered</li>
     *   <li>A non-manifold edge is reached (3+ adjacent faces)</li>
     * </ul>
     *
     * @param startEdgeId Edge identifier to start from (0..edgeCount-1)
     * @return Ordered list of edge IDs forming the ring, or empty list if
     *         the edge ID is invalid
     */
    public List<Integer> traceEdgeRing(int startEdgeId) {
        if (startEdgeId < 0 || startEdgeId >= edges.length) {
            return Collections.emptyList();
        }

        MeshEdge startEdge = edges[startEdgeId];
        int[] adjFaces = startEdge.adjacentFaceIds();

        // Isolated edge with no faces
        if (adjFaces == null || adjFaces.length == 0) {
            return List.of(startEdgeId);
        }

        // Walk forward (using first adjacent face, stepping +1)
        List<Integer> forward = walkRing(startEdgeId, adjFaces[0], 1);

        // Walk backward (using second adjacent face, stepping -1)
        List<Integer> backward;
        if (adjFaces.length >= 2) {
            backward = walkRing(startEdgeId, adjFaces[1], -1);
        } else {
            backward = Collections.emptyList();
        }

        // Check if the ring closed
        boolean closed = !forward.isEmpty()
                && forward.getLast() == startEdgeId;

        if (closed) {
            return forward.subList(0, forward.size() - 1);
        }

        // Open ring: combine backward (reversed) + start + forward
        List<Integer> result = new ArrayList<>(backward.size() + 1 + forward.size());
        for (int i = backward.size() - 1; i >= 0; i--) {
            result.add(backward.get(i));
        }
        result.add(startEdgeId);
        result.addAll(forward);
        return result;
    }

    // =========================================================================
    // LOOP WALKING
    // =========================================================================

    /**
     * Walk the loop in one direction from startEdge through the given face.
     *
     * @return List of edge IDs visited (excluding startEdge), or including
     *         startEdge at the end if the loop closed
     */
    private List<Integer> walkLoop(int startEdgeId, int firstFaceId) {
        List<Integer> result = new ArrayList<>();
        int currentEdgeId = startEdgeId;
        int currentFaceId = firstFaceId;

        while (true) {
            MeshFace face = getFaceOrNull(currentFaceId);
            if (face == null || face.vertexCount() != 4) {
                break; // Non-quad or invalid — terminate
            }

            int idx = findEdgeIndex(face, currentEdgeId);
            if (idx < 0) {
                break; // Edge not found in face — shouldn't happen
            }

            // Opposite edge in quad: index + 2
            int oppositeIdx = (idx + 2) % 4;
            int oppositeEdgeId = face.edgeIds()[oppositeIdx];

            // Loop closed?
            if (oppositeEdgeId == startEdgeId) {
                result.add(oppositeEdgeId);
                return result;
            }

            // Prevent infinite loops on degenerate topology
            if (result.contains(oppositeEdgeId)) {
                break;
            }

            result.add(oppositeEdgeId);

            // Cross to the other face sharing the opposite edge
            int nextFaceId = getOtherFaceId(edges[oppositeEdgeId], currentFaceId);
            if (nextFaceId < 0) {
                break; // Boundary or non-manifold — terminate
            }

            currentEdgeId = oppositeEdgeId;
            currentFaceId = nextFaceId;
        }

        return result;
    }

    // =========================================================================
    // RING WALKING
    // =========================================================================

    /**
     * Walk the ring in one direction from startEdge through the given face.
     *
     * @param step +1 for forward direction, -1 for backward
     * @return List of edge IDs visited (excluding startEdge), or including
     *         startEdge at the end if the ring closed
     */
    private List<Integer> walkRing(int startEdgeId, int firstFaceId, int step) {
        List<Integer> result = new ArrayList<>();
        int currentEdgeId = startEdgeId;
        int currentFaceId = firstFaceId;

        while (true) {
            MeshFace face = getFaceOrNull(currentFaceId);
            if (face == null || face.vertexCount() != 4) {
                break; // Non-quad or invalid — terminate
            }

            int idx = findEdgeIndex(face, currentEdgeId);
            if (idx < 0) {
                break;
            }

            // Step perpendicular to reach the connecting edge
            int perpIdx = (idx + step + 4) % 4;
            int perpEdgeId = face.edgeIds()[perpIdx];

            // Cross the perpendicular edge to the next face
            int nextFaceId = getOtherFaceId(edges[perpEdgeId], currentFaceId);
            if (nextFaceId < 0) {
                break; // Boundary — terminate
            }

            MeshFace nextFace = getFaceOrNull(nextFaceId);
            if (nextFace == null || nextFace.vertexCount() != 4) {
                break; // Non-quad — terminate
            }

            int jdx = findEdgeIndex(nextFace, perpEdgeId);
            if (jdx < 0) {
                break;
            }

            // The next ring edge is one step further in the same direction
            int ringIdx = (jdx + step + 4) % 4;
            int ringEdgeId = nextFace.edgeIds()[ringIdx];

            // Ring closed?
            if (ringEdgeId == startEdgeId) {
                result.add(ringEdgeId);
                return result;
            }

            // Prevent infinite loops on degenerate topology
            if (result.contains(ringEdgeId)) {
                break;
            }

            result.add(ringEdgeId);

            // Advance: the ring edge becomes the current edge, its other face is next
            int faceAfterRing = getOtherFaceId(edges[ringEdgeId], nextFaceId);
            if (faceAfterRing < 0) {
                break; // Boundary — terminate
            }

            currentEdgeId = ringEdgeId;
            currentFaceId = faceAfterRing;
        }

        return result;
    }

    // =========================================================================
    // INTERNAL HELPERS
    // =========================================================================

    /**
     * Get the face on the other side of an edge from the known face.
     *
     * @param edge       The edge to cross
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

    private MeshFace getFaceOrNull(int faceId) {
        if (faceId < 0 || faceId >= faces.length) return null;
        return faces[faceId];
    }

    /**
     * Find the position of an edge in a face's winding order.
     * O(n) scan, where n is the face's vertex count (typically 3–4).
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
