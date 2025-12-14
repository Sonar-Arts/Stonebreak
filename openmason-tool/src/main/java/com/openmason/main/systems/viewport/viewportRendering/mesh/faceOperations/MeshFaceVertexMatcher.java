package com.openmason.main.systems.viewport.viewportRendering.mesh.faceOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single Responsibility: Matches vertex positions using epsilon-based comparison.
 * This class encapsulates the logic of finding which unique vertex matches a given position.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles vertex position matching
 * - Open/Closed: Can be extended for different matching strategies (e.g., grid-based)
 * - Liskov Substitution: Could be abstracted to IVertexMatcher if needed
 * - Interface Segregation: Focused interface for position matching
 * - Dependency Inversion: Depends on abstractions (Vector3f) not concrete implementations
 *
 * KISS Principle: Simple linear search with epsilon comparison.
 * DRY Principle: All vertex matching logic centralized in one place.
 * YAGNI Principle: Only implements what's needed - no complex spatial indexing.
 */
public class MeshFaceVertexMatcher {

    private static final Logger logger = LoggerFactory.getLogger(MeshFaceVertexMatcher.class);
    private static final int COMPONENTS_PER_POSITION = 3; // x, y, z

    private final float epsilon;

    /**
     * Create a vertex matcher with specified epsilon.
     *
     * @param epsilon Distance threshold for considering vertices matching
     */
    public MeshFaceVertexMatcher(float epsilon) {
        this.epsilon = epsilon;
    }

    /**
     * Find the index of a unique vertex that matches the given position.
     * Uses epsilon-based distance comparison.
     *
     * @param position Position to match
     * @param uniqueVertexPositions Array of unique vertex positions [x0,y0,z0, x1,y1,z1, ...]
     * @param uniqueVertexCount Number of unique vertices
     * @return Index of matching vertex, or -1 if no match found
     */
    public int findMatchingVertexIndex(Vector3f position, float[] uniqueVertexPositions,
                                      int uniqueVertexCount) {
        // Validate inputs
        if (position == null) {
            logger.warn("Cannot find matching vertex: position is null");
            return -1;
        }

        if (uniqueVertexPositions == null || uniqueVertexCount == 0) {
            logger.warn("Cannot find matching vertex: no unique vertices");
            return -1;
        }

        // Linear search with epsilon comparison (KISS: simple and sufficient for small vertex counts)
        for (int vIdx = 0; vIdx < uniqueVertexCount; vIdx++) {
            Vector3f uniqueVertex = extractVertexPosition(uniqueVertexPositions, vIdx);

            if (position.distance(uniqueVertex) < epsilon) {
                return vIdx;
            }
        }

        // No match found
        return -1;
    }

    /**
     * Extract a vertex position from the positions array.
     *
     * @param positions Array of positions
     * @param vertexIdx Vertex index
     * @return Position vector
     */
    private Vector3f extractVertexPosition(float[] positions, int vertexIdx) {
        int vPosIdx = vertexIdx * COMPONENTS_PER_POSITION;
        return new Vector3f(
            positions[vPosIdx],
            positions[vPosIdx + 1],
            positions[vPosIdx + 2]
        );
    }
}
