package com.openmason.main.systems.viewport.viewportRendering.mesh.vertexOperations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single Responsibility: Builds index remapping from merge groups.
 * Manages the mapping between old vertex indices and new merged indices.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles index remapping logic
 * - Open/Closed: Could be extended for different remapping strategies
 * - Interface Segregation: Simple, focused interface
 * - Dependency Inversion: Works with generic collections
 *
 * Shape-Blind Design:
 * This operation is data-driven and works with merge groups from GenericModelRenderer (GMR).
 * GMR is the single source of truth for mesh topology. Index mappings are built
 * from detected merge groups without assumptions about vertex counts or geometry.
 *
 * Data Flow: Merge groups → Index remapping → Updated vertex references
 */
public class MeshVertexIndexRemapper {

    private static final Logger logger = LoggerFactory.getLogger(MeshVertexIndexRemapper.class);

    /**
     * Result of index remapping operation.
     */
    public static class RemapResult {
        public final Map<Integer, Integer> oldToNewIndex;
        public final List<Integer> verticesToKeep;

        public RemapResult(Map<Integer, Integer> oldToNewIndex, List<Integer> verticesToKeep) {
            this.oldToNewIndex = oldToNewIndex;
            this.verticesToKeep = verticesToKeep;
        }
    }

    private final List<List<Integer>> mergeGroups;

    /**
     * Create an index remapper.
     *
     * @param mergeGroups Groups of vertex indices to merge
     */
    public MeshVertexIndexRemapper(List<List<Integer>> mergeGroups) {
        this.mergeGroups = mergeGroups;
    }

    /**
     * Build index remapping from merge groups.
     * For each group, all vertices map to the first vertex in the group.
     *
     * @return RemapResult containing the mapping and vertices to keep
     */
    public RemapResult buildRemapping() {
        Map<Integer, Integer> oldToNewIndex = new HashMap<>();
        List<Integer> verticesToKeep = new ArrayList<>();
        int newIndex = 0;

        for (List<Integer> group : mergeGroups) {
            // Keep first vertex in each group
            int keepIndex = group.get(0);
            verticesToKeep.add(keepIndex);

            // Map all vertices in this group to the kept vertex's new index
            for (int oldIndex : group) {
                oldToNewIndex.put(oldIndex, newIndex);
            }

            if (group.size() > 1) {
                logger.trace("Merge group {} -> new index {}: vertices {}",
                        keepIndex, newIndex, group);
            }

            newIndex++;
        }

        logger.debug("Built remapping: {} old vertices -> {} new vertices",
                oldToNewIndex.size(), verticesToKeep.size());

        return new RemapResult(oldToNewIndex, verticesToKeep);
    }

    /**
     * Update a persistent mapping through a new merge operation.
     * This ensures multi-stage merges are tracked correctly.
     *
     * @param originalToCurrentMapping Existing mapping from original to current indices
     * @param newRemapping New remapping from current to merged indices
     * @return Updated mapping from original to merged indices
     */
    public static Map<Integer, Integer> updatePersistentMapping(
            Map<Integer, Integer> originalToCurrentMapping,
            Map<Integer, Integer> newRemapping) {

        Map<Integer, Integer> updatedMapping = new HashMap<>();

        for (Map.Entry<Integer, Integer> entry : originalToCurrentMapping.entrySet()) {
            int originalIdx = entry.getKey();
            int currentIdx = entry.getValue();

            // Remap current index through the new merge
            Integer newCurrentIdx = newRemapping.get(currentIdx);
            if (newCurrentIdx != null) {
                updatedMapping.put(originalIdx, newCurrentIdx);
            } else {
                // Fallback: keep old mapping if remapping missing
                logger.warn("Lost mapping for original vertex {} (was at current {})",
                        originalIdx, currentIdx);
                updatedMapping.put(originalIdx, currentIdx);
            }
        }

        logger.debug("Updated persistent mapping: {} entries", updatedMapping.size());
        return updatedMapping;
    }
}
