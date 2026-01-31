package com.openmason.main.systems.rendering.model.gmr.mesh.vertexOperations;

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
 *
 * Shape-Blind Design:
 * This operation is data-driven and works with vertex data from GenericModelRenderer (GMR).
 * GMR is the single source of truth for mesh topology. Vertex counts and positions
 * are determined by GMR's data model, not hardcoded geometry assumptions.
 *
 * Data Flow: GMR provides vertex positions → Group detection → Merge groups
 */
public class MeshMergeGroupDetector {

    private static final Logger logger = LoggerFactory.getLogger(MeshMergeGroupDetector.class);

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
    public MeshMergeGroupDetector(float[] vertexPositions, int vertexCount, float epsilon) {
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
        float epsilonSq = epsilon * epsilon;

        for (int i = 0; i < vertexCount; i++) {
            if (processed[i]) continue;

            // Start new group with vertex i
            List<Integer> group = new ArrayList<>();
            group.add(i);

            int posI = i * 3;
            float ix = vertexPositions[posI];
            float iy = vertexPositions[posI + 1];
            float iz = vertexPositions[posI + 2];

            // Find all other vertices at the same position
            for (int j = i + 1; j < vertexCount; j++) {
                if (processed[j]) continue;

                int posJ = j * 3;
                float dx = ix - vertexPositions[posJ];
                float dy = iy - vertexPositions[posJ + 1];
                float dz = iz - vertexPositions[posJ + 2];

                if (dx * dx + dy * dy + dz * dz < epsilonSq) {
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

}
