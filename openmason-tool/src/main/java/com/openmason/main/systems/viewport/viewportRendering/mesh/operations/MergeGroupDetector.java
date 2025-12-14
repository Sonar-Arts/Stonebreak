package com.openmason.main.systems.viewport.viewportRendering.mesh.operations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Single Responsibility: Detects groups of overlapping vertices.
 * Groups vertices that are within epsilon distance of each other.
 *
 * SOLID Principles:
 * - Single Responsibility: Only detects merge groups
 * - Open/Closed: Could be extended for different grouping strategies
 * - Interface Segregation: Minimal, focused interface
 * - Dependency Inversion: Works with arrays, not specific data structures
 */
public class MergeGroupDetector {

    private static final Logger logger = LoggerFactory.getLogger(MergeGroupDetector.class);

    private final float[] vertexPositions;
    private final int vertexCount;
    private final float epsilon;

    /**
     * Create a merge group detector.
     *
     * @param vertexPositions Array of vertex positions [x0,y0,z0, x1,y1,z1, ...]
     * @param vertexCount Number of vertices
     * @param epsilon Distance threshold for considering vertices overlapping
     */
    public MergeGroupDetector(float[] vertexPositions, int vertexCount, float epsilon) {
        this.vertexPositions = vertexPositions;
        this.vertexCount = vertexCount;
        this.epsilon = epsilon;
    }

    /**
     * Detect groups of overlapping vertices.
     * Each group contains vertex indices that are at the same position.
     *
     * @return List of merge groups, where each group is a list of vertex indices
     */
    public List<List<Integer>> detectGroups() {
        List<List<Integer>> mergeGroups = new ArrayList<>();
        boolean[] processed = new boolean[vertexCount];

        for (int i = 0; i < vertexCount; i++) {
            if (processed[i]) continue;

            // Start new group with vertex i
            List<Integer> group = new ArrayList<>();
            group.add(i);

            Vector3f pos1 = getVertexPosition(i);

            // Find all other vertices at the same position
            for (int j = i + 1; j < vertexCount; j++) {
                if (processed[j]) continue;

                Vector3f pos2 = getVertexPosition(j);

                if (pos1.distance(pos2) < epsilon) {
                    group.add(j);
                    processed[j] = true;
                }
            }

            mergeGroups.add(group);
            processed[i] = true;
        }

        // Log statistics
        int groupsWithMultiple = (int) mergeGroups.stream()
                .filter(g -> g.size() > 1)
                .count();

        logger.debug("Detected {} merge groups ({} with multiple vertices)",
                mergeGroups.size(), groupsWithMultiple);

        return mergeGroups;
    }

    /**
     * Get vertex position by index.
     */
    private Vector3f getVertexPosition(int index) {
        int posIndex = index * 3;
        return new Vector3f(
                vertexPositions[posIndex],
                vertexPositions[posIndex + 1],
                vertexPositions[posIndex + 2]
        );
    }
}
