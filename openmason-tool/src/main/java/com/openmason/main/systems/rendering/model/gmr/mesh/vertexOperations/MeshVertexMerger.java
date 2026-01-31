package com.openmason.main.systems.rendering.model.gmr.mesh.vertexOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Single Responsibility: Orchestrates vertex merging operations.
 * Coordinates merge group detection, index remapping, position averaging,
 * and mesh vertex updates for merging overlapping vertices.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles vertex merge orchestration
 * - Open/Closed: Can be extended without modification
 * - Liskov Substitution: Could implement IMerger interface if needed
 * - Interface Segregation: Focused interface for merge operations
 * - Dependency Inversion: Depends on abstractions (arrays, maps), delegates to specialized classes
 *
 * Shape-Blind Design:
 * This operation is data-driven and orchestrates merge operations on vertex data from
 * GenericModelRenderer (GMR). GMR is the single source of truth for mesh topology.
 * Merging works with any vertex count determined by GMR's data model.
 *
 * Data Flow: GMR vertex data → Group detection → Index remapping → Position averaging → Merged vertices
 */
public class MeshVertexMerger {

    private static final Logger logger = LoggerFactory.getLogger(MeshVertexMerger.class);

    /**
     * Result of a vertex merge operation.
     */
    public static class MergeResult {
        public final float[] newVertexPositions;
        public final int newVertexCount;
        public final Map<Integer, Integer> indexMapping;
        public final Map<Integer, Integer> updatedOriginalMapping;

        public MergeResult(float[] newVertexPositions, int newVertexCount,
                          Map<Integer, Integer> indexMapping,
                          Map<Integer, Integer> updatedOriginalMapping) {
            this.newVertexPositions = newVertexPositions;
            this.newVertexCount = newVertexCount;
            this.indexMapping = indexMapping;
            this.updatedOriginalMapping = updatedOriginalMapping;
        }
    }

    private final float[] vertexPositions;
    private final float[] allMeshVertices;
    private final int vertexCount;
    private final float epsilon;
    private final Map<Integer, Integer> originalToCurrentMapping;

    /**
     * Create a vertex merger.
     *
     * @param vertexPositions Array of unique vertex positions
     * @param allMeshVertices Array of all mesh vertices (can be null)
     * @param vertexCount Number of unique vertices
     * @param epsilon Distance threshold for merging
     * @param originalToCurrentMapping Persistent mapping from original to current indices
     */
    public MeshVertexMerger(float[] vertexPositions,
                            float[] allMeshVertices,
                            int vertexCount,
                            float epsilon,
                            Map<Integer, Integer> originalToCurrentMapping) {
        this.vertexPositions = vertexPositions;
        this.allMeshVertices = allMeshVertices;
        this.vertexCount = vertexCount;
        this.epsilon = epsilon;
        this.originalToCurrentMapping = originalToCurrentMapping;
    }

    /**
     * Merge overlapping vertices.
     * Returns null if no vertices were merged.
     *
     * @return MergeResult with new positions and mappings, or null if no merge occurred
     */
    public MergeResult merge() {
        // Step 1: Detect merge groups using specialized detector
        MeshMergeGroupDetector detector = new MeshMergeGroupDetector(vertexPositions, vertexCount, epsilon);
        List<List<Integer>> mergeGroups = detector.detectGroups();

        // Step 2: Build index remapping using specialized remapper
        MeshVertexIndexRemapper remapper = new MeshVertexIndexRemapper(mergeGroups);
        MeshVertexIndexRemapper.RemapResult remapResult = remapper.buildRemapping();

        // Check if any merging actually occurred
        if (remapResult.verticesToKeep.size() == vertexCount) {
            logger.debug("No overlapping vertices found to merge");
            return null; // No merge needed
        }

        // Step 3: Update persistent original-to-current mapping
        Map<Integer, Integer> updatedOriginalMapping = MeshVertexIndexRemapper.updatePersistentMapping(
                originalToCurrentMapping,
                remapResult.oldToNewIndex
        );

        // Step 4: Build new vertex position array with averaged positions
        float[] newVertexPositions = buildMergedPositions(
                mergeGroups,
                remapResult.verticesToKeep.size()
        );

        // Step 5: Update mesh vertices if present
        if (allMeshVertices != null) {
            updateMeshVertices(newVertexPositions, epsilon);
        }

        int mergedCount = vertexCount - remapResult.verticesToKeep.size();
        logger.info("Merged {} duplicate vertices, {} unique vertices remaining",
                mergedCount, remapResult.verticesToKeep.size());

        return new MergeResult(
                newVertexPositions,
                remapResult.verticesToKeep.size(),
                remapResult.oldToNewIndex,
                updatedOriginalMapping
        );
    }

    /**
     * Build new vertex positions array with averaged positions for each merge group.
     */
    private float[] buildMergedPositions(List<List<Integer>> mergeGroups, int newVertexCount) {
        float[] newVertexPositions = new float[newVertexCount * 3];

        for (int i = 0; i < newVertexCount; i++) {
            int newPosIndex = i * 3;

            // Calculate average position for this group
            List<Integer> group = mergeGroups.get(i);
            Vector3f avgPos = calculateAveragePosition(group);

            newVertexPositions[newPosIndex] = avgPos.x;
            newVertexPositions[newPosIndex + 1] = avgPos.y;
            newVertexPositions[newPosIndex + 2] = avgPos.z;

            if (group.size() > 1) {
                logger.debug("Merged {} vertices at position ({}, {}, {}) -> new index {}",
                        group.size(),
                        String.format("%.3f", avgPos.x),
                        String.format("%.3f", avgPos.y),
                        String.format("%.3f", avgPos.z),
                        i);
            }
        }

        return newVertexPositions;
    }

    /**
     * Calculate average position for a group of vertices.
     */
    private Vector3f calculateAveragePosition(List<Integer> group) {
        Vector3f avgPos = new Vector3f();

        for (int vertexIdx : group) {
            avgPos.add(
                    vertexPositions[vertexIdx * 3],
                    vertexPositions[vertexIdx * 3 + 1],
                    vertexPositions[vertexIdx * 3 + 2]
            );
        }

        avgPos.div(group.size());
        return avgPos;
    }

    /**
     * Update all mesh vertices to reference the new merged positions.
     */
    private void updateMeshVertices(float[] newVertexPositions, float epsilon) {
        int meshVertexCount = allMeshVertices.length / 3;
        int newVertexCount = newVertexPositions.length / 3;

        for (int meshIdx = 0; meshIdx < meshVertexCount; meshIdx++) {
            int meshPosIdx = meshIdx * 3;
            Vector3f meshPos = new Vector3f(
                    allMeshVertices[meshPosIdx],
                    allMeshVertices[meshPosIdx + 1],
                    allMeshVertices[meshPosIdx + 2]
            );

            // Find which new vertex this mesh vertex should reference
            for (int newIdx = 0; newIdx < newVertexCount; newIdx++) {
                Vector3f newPos = new Vector3f(
                        newVertexPositions[newIdx * 3],
                        newVertexPositions[newIdx * 3 + 1],
                        newVertexPositions[newIdx * 3 + 2]
                );

                if (meshPos.distance(newPos) < epsilon) {
                    allMeshVertices[meshPosIdx] = newPos.x;
                    allMeshVertices[meshPosIdx + 1] = newPos.y;
                    allMeshVertices[meshPosIdx + 2] = newPos.z;
                    break;
                }
            }
        }
    }
}
