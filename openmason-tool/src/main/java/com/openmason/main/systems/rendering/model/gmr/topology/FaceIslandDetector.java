package com.openmason.main.systems.rendering.model.gmr.topology;

import java.util.*;

/**
 * Detects connected components ("islands") of faces in the mesh topology.
 *
 * <p>Two faces belong to the same island if they are reachable from each
 * other via shared edges — i.e., connected in the face-to-face adjacency
 * graph. A mesh with a single contiguous surface has one island; a mesh
 * with disconnected parts (e.g., separate limbs in a character model)
 * has multiple islands.
 *
 * <p>Useful for:
 * <ul>
 *   <li>Selecting disconnected mesh parts (click one face → select entire island)</li>
 *   <li>Mesh validation (detecting unexpected disconnects)</li>
 *   <li>Future multi-object support (split islands into separate objects)</li>
 * </ul>
 *
 * <p>Results are computed lazily on first request and cached. The cache is
 * immutable once built — face adjacency is fixed at topology build time.
 *
 * <p>Constructed by {@link MeshTopology} and composed as a sub-service.
 */
public final class FaceIslandDetector {

    private final int faceCount;
    private final List<List<Integer>> faceToAdjacentFaces;

    /** Lazily computed and cached. */
    private List<Set<Integer>> islands;

    /**
     * Package-private constructor used by MeshTopology.
     *
     * @param faceCount           Total number of faces in the mesh
     * @param faceToAdjacentFaces Face adjacency index (shared reference, not copied)
     */
    FaceIslandDetector(int faceCount, List<List<Integer>> faceToAdjacentFaces) {
        this.faceCount = faceCount;
        this.faceToAdjacentFaces = faceToAdjacentFaces;
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Get all face islands (connected components) in the mesh.
     *
     * <p>Each set contains face IDs that are mutually reachable via shared
     * edges. The returned list is ordered by the smallest face ID in each
     * island (ascending). Each set is unmodifiable.
     *
     * <p>Results are cached after first computation. Subsequent calls
     * return the same list instance.
     *
     * @return Unmodifiable list of unmodifiable face ID sets (one per island),
     *         or an empty list if the mesh has no faces
     */
    public List<Set<Integer>> getIslands() {
        if (islands == null) {
            islands = computeIslands();
        }
        return islands;
    }

    /**
     * Get the number of face islands in the mesh.
     *
     * @return Number of connected components (0 if no faces)
     */
    public int getIslandCount() {
        return getIslands().size();
    }

    /**
     * Get the island containing the given face.
     *
     * @param faceId Face identifier
     * @return Unmodifiable set of face IDs in the same island,
     *         or an empty set if the face ID is out of range
     */
    public Set<Integer> getIslandForFace(int faceId) {
        if (faceId < 0 || faceId >= faceCount) {
            return Collections.emptySet();
        }
        for (Set<Integer> island : getIslands()) {
            if (island.contains(faceId)) {
                return island;
            }
        }
        return Collections.emptySet();
    }

    // =========================================================================
    // ISLAND COMPUTATION
    // =========================================================================

    /**
     * BFS over the face adjacency graph to find all connected components.
     */
    private List<Set<Integer>> computeIslands() {
        if (faceCount == 0) {
            return Collections.emptyList();
        }

        boolean[] visited = new boolean[faceCount];
        List<Set<Integer>> result = new ArrayList<>();
        Deque<Integer> queue = new ArrayDeque<>();

        for (int faceId = 0; faceId < faceCount; faceId++) {
            if (visited[faceId]) {
                continue;
            }

            // Skip gap entries (deleted faces with no adjacency data)
            if (faceId >= faceToAdjacentFaces.size()) {
                continue;
            }
            List<Integer> initialNeighbors = faceToAdjacentFaces.get(faceId);
            if (initialNeighbors.isEmpty()) {
                // Mark as visited to avoid revisiting, but don't create an island
                // for a deleted face gap. Real isolated faces (single-face meshes)
                // are handled correctly because they still have triangle data.
                visited[faceId] = true;
                continue;
            }

            // BFS from this unvisited face
            Set<Integer> island = new LinkedHashSet<>();
            queue.addLast(faceId);
            visited[faceId] = true;

            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                island.add(current);

                List<Integer> neighbors = faceToAdjacentFaces.get(current);
                for (int neighbor : neighbors) {
                    if (neighbor >= 0 && neighbor < faceCount && !visited[neighbor]) {
                        visited[neighbor] = true;
                        queue.addLast(neighbor);
                    }
                }
            }

            result.add(Collections.unmodifiableSet(island));
        }

        return Collections.unmodifiableList(result);
    }
}
