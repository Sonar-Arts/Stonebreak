package com.openmason.main.systems.viewport.viewportRendering.mesh;

import com.openmason.main.systems.viewport.viewportRendering.mesh.vertexOperations.*;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages mesh resources for the viewport rendering system.
 * This class handles mesh vertex relationships, unique-to-mesh mapping,
 * and mesh data structure operations.
 *
 * Responsibilities:
 * - Building and maintaining unique vertex to mesh vertex mappings
 * - Managing mesh vertex data structures
 * - Providing mesh geometry information
 */
public class MeshManager {

    private static final Logger logger = LoggerFactory.getLogger(MeshManager.class);
    private static final float EPSILON = 0.0001f; // Tolerance for vertex position matching

    private static MeshManager instance;

    // Mesh data storage
    private float[] allMeshVertices = null; // ALL mesh vertices (e.g., 24 for cube)
    private Map<Integer, List<Integer>> uniqueToMeshMapping = new HashMap<>(); // Maps unique vertex index to mesh vertex indices

    private MeshManager() {
        // Private constructor for singleton
    }

    public static MeshManager getInstance() {
        if (instance == null) {
            instance = new MeshManager();
        }
        return instance;
    }

    /**
     * Result of a vertex merge operation.
     * Encapsulates the outcome of merging overlapping vertices.
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

    /**
     * Initializes the mesh manager.
     */
    public void initialize() {
        logger.debug("MeshManager initialized");
        clearMeshData();
    }

    /**
     * Set the mesh vertex data.
     *
     * @param meshVertices Array of all mesh vertex positions
     */
    public void setMeshVertices(float[] meshVertices) {
        this.allMeshVertices = meshVertices;
        logger.debug("Set mesh vertices: {} vertices", meshVertices != null ? meshVertices.length / 3 : 0);
    }

    /**
     * Get all mesh vertices.
     *
     * @return Array of all mesh vertex positions, or null if not set
     */
    public float[] getAllMeshVertices() {
        return allMeshVertices;
    }

    /**
     * Get the unique-to-mesh mapping.
     *
     * @return Map from unique vertex indices to lists of mesh vertex indices
     */
    public Map<Integer, List<Integer>> getUniqueToMeshMapping() {
        return uniqueToMeshMapping;
    }

    /**
     * Build mapping from unique vertex indices to mesh vertex indices.
     * For a cube: Each of the 8 unique corner vertices maps to 3 mesh vertex instances.
     *
     * This is the core mesh relationship method that identifies which mesh vertices
     * correspond to each unique geometric vertex.
     *
     * @param uniquePositions Array of unique vertex positions
     * @param meshPositions Array of ALL mesh vertex positions
     */
    public void buildUniqueToMeshMapping(float[] uniquePositions, float[] meshPositions) {
        uniqueToMeshMapping.clear();

        if (uniquePositions == null || meshPositions == null) {
            logger.warn("Cannot build mapping: null positions provided");
            return;
        }

        // For each mesh vertex, find its matching unique vertex
        int meshVertexCount = meshPositions.length / 3;
        int uniqueVertexCount = uniquePositions.length / 3;

        for (int meshIndex = 0; meshIndex < meshVertexCount; meshIndex++) {
            int meshPosIndex = meshIndex * 3;
            Vector3f meshPos = new Vector3f(
                    meshPositions[meshPosIndex],
                    meshPositions[meshPosIndex + 1],
                    meshPositions[meshPosIndex + 2]
            );

            // Find which unique vertex this mesh vertex matches
            for (int uniqueIndex = 0; uniqueIndex < uniqueVertexCount; uniqueIndex++) {
                int uniquePosIndex = uniqueIndex * 3;
                Vector3f uniquePos = new Vector3f(
                        uniquePositions[uniquePosIndex],
                        uniquePositions[uniquePosIndex + 1],
                        uniquePositions[uniquePosIndex + 2]
                );

                if (meshPos.distance(uniquePos) < EPSILON) {
                    // Found matching unique vertex - add to mapping
                    uniqueToMeshMapping.computeIfAbsent(uniqueIndex, k -> new ArrayList<>()).add(meshIndex);
                    break;
                }
            }
        }

        logger.debug("Built vertex mapping: {} unique vertices → {} mesh vertices",
                uniqueVertexCount, meshVertexCount);

        // Log mapping for debugging (only for small models like cube)
        if (uniqueVertexCount <= 10) {
            for (Map.Entry<Integer, List<Integer>> entry : uniqueToMeshMapping.entrySet()) {
                logger.trace("Unique vertex {} → mesh vertices {}",
                        entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Get the mesh vertex indices that correspond to a unique vertex.
     *
     * @param uniqueIndex Index of the unique vertex
     * @return List of mesh vertex indices, or empty list if not found
     */
    public List<Integer> getMeshIndicesForUniqueVertex(int uniqueIndex) {
        return uniqueToMeshMapping.getOrDefault(uniqueIndex, Collections.emptyList());
    }

    /**
     * Update all mesh instances of a unique vertex to a new position.
     * For example, when dragging corner vertex 0 on a cube, all 3 mesh instances
     * at that corner are updated simultaneously.
     *
     * @param uniqueIndex Index of the unique vertex
     * @param newPosition New position to apply
     * @return true if any mesh vertices were updated, false otherwise
     */
    public boolean updateMeshInstancePositions(int uniqueIndex, Vector3f newPosition) {
        if (allMeshVertices == null) {
            logger.warn("Cannot update mesh instances: no mesh vertices set");
            return false;
        }

        List<Integer> meshIndices = getMeshIndicesForUniqueVertex(uniqueIndex);
        if (meshIndices.isEmpty()) {
            logger.warn("No mesh indices found for unique vertex {}", uniqueIndex);
            return false;
        }

        // Update all mesh instances at this unique vertex position
        for (int meshIndex : meshIndices) {
            int meshPosIndex = meshIndex * 3;
            allMeshVertices[meshPosIndex] = newPosition.x;
            allMeshVertices[meshPosIndex + 1] = newPosition.y;
            allMeshVertices[meshPosIndex + 2] = newPosition.z;
        }

        logger.trace("Updated {} mesh instances for unique vertex {} to position {}",
                meshIndices.size(), uniqueIndex, newPosition);

        return true;
    }

    /**
     * Get the count of mesh vertices.
     *
     * @return Number of mesh vertices, or 0 if not set
     */
    public int getMeshVertexCount() {
        return allMeshVertices != null ? allMeshVertices.length / 3 : 0;
    }

    /**
     * Get the count of unique vertices (based on mapping size).
     *
     * @return Number of unique vertices
     */
    public int getUniqueVertexCount() {
        return uniqueToMeshMapping.size();
    }

    /**
     * Clear all mesh data and mappings.
     */
    public void clearMeshData() {
        allMeshVertices = null;
        uniqueToMeshMapping.clear();
        logger.debug("Cleared mesh data");
    }

    /**
     * Validate mesh integrity - check if all mesh vertices are mapped to unique vertices.
     *
     * @param expectedUniqueCount Expected number of unique vertices
     * @return true if mesh is valid, false otherwise
     */
    public boolean validateMeshIntegrity(int expectedUniqueCount) {
        if (allMeshVertices == null) {
            logger.warn("Mesh validation failed: no mesh vertices");
            return false;
        }

        int meshCount = getMeshVertexCount();
        int uniqueCount = getUniqueVertexCount();
        int totalMappedMeshVertices = uniqueToMeshMapping.values().stream()
                .mapToInt(List::size)
                .sum();

        if (uniqueCount != expectedUniqueCount) {
            logger.warn("Mesh validation failed: expected {} unique vertices, found {}",
                    expectedUniqueCount, uniqueCount);
            return false;
        }

        if (totalMappedMeshVertices != meshCount) {
            logger.warn("Mesh validation failed: {} mesh vertices but {} mapped",
                    meshCount, totalMappedMeshVertices);
            return false;
        }

        logger.debug("Mesh validation passed: {} unique vertices, {} mesh vertices",
                uniqueCount, meshCount);
        return true;
    }

    // ========================================
    // Mesh Operations (managed through MeshManager)
    // ========================================

    /**
     * Update vertex position in memory, GPU, and all mesh instances.
     * Coordinates the complete update process using MeshVertexPositionUpdater.
     *
     * @param uniqueIndex Unique vertex index to update
     * @param position New position in model space
     * @param vertexPositions Array of unique vertex positions (will be modified)
     * @param vbo OpenGL VBO handle for GPU update
     * @param vertexCount Total number of unique vertices
     * @return true if update succeeded, false otherwise
     */
    public boolean updateVertexPosition(int uniqueIndex, Vector3f position,
                                       float[] vertexPositions, int vbo, int vertexCount) {
        MeshVertexPositionUpdater updater = new MeshVertexPositionUpdater(
                vertexPositions,
                allMeshVertices,
                uniqueToMeshMapping,
                vbo,
                vertexCount
        );
        return updater.updatePosition(uniqueIndex, position);
    }

    /**
     * Expand vertex positions to standard cube format (8 vertices).
     * Convenience method using MeshVertexDataTransformer.
     *
     * @param vertexPositions Current vertex positions
     * @param vertexCount Current vertex count
     * @param indexRemapping Mapping from old indices to new indices
     * @return Expanded vertex positions (24 floats for 8 vertices)
     */
    public float[] expandToCubeFormat(float[] vertexPositions, int vertexCount,
                                     Map<Integer, Integer> indexRemapping) {
        MeshVertexDataTransformer transformer = new MeshVertexDataTransformer(vertexPositions, vertexCount);
        return transformer.expandToCubeFormat(indexRemapping);
    }

    /**
     * Merge overlapping vertices by removing duplicates.
     * Coordinates the complete merge process using MeshVertexMerger.
     *
     * @param vertexPositions Current vertex positions
     * @param vertexCount Current vertex count
     * @param epsilon Distance threshold for considering vertices overlapping
     * @param originalToCurrentMapping Persistent mapping from original to current indices
     * @return MergeResult with new positions and mappings, or null if no merge occurred
     */
    public MergeResult mergeOverlappingVertices(float[] vertexPositions,
                                                int vertexCount,
                                                float epsilon,
                                                Map<Integer, Integer> originalToCurrentMapping) {
        MeshVertexMerger merger = new MeshVertexMerger(
                vertexPositions,
                allMeshVertices,
                vertexCount,
                epsilon,
                originalToCurrentMapping
        );
        MeshVertexMerger.MergeResult internalResult = merger.merge();

        // Return null if no merge occurred
        if (internalResult == null) {
            return null;
        }

        // Convert internal result to public API result
        return new MergeResult(
                internalResult.newVertexPositions,
                internalResult.newVertexCount,
                internalResult.indexMapping,
                internalResult.updatedOriginalMapping
        );
    }

    /**
     * Cleans up all mesh resources.
     */
    public void cleanup() {
        clearMeshData();
        logger.debug("MeshManager cleaned up");
    }
}
