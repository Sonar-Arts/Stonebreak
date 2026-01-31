package com.openmason.main.systems.rendering.model.gmr.mesh.vertexOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL15.*;

/**
 * Single Responsibility: Updates vertex positions in memory, GPU, and mesh instances.
 * This class encapsulates the complex logic of keeping unique vertices, VBO data,
 * and mesh vertex instances synchronized when a vertex position changes.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles vertex position updates
 * - Open/Closed: Can be extended for different update strategies
 * - Liskov Substitution: Could be abstracted to IVertexUpdater if needed
 * - Interface Segregation: Focused interface for position updates
 * - Dependency Inversion: Depends on abstractions (arrays, maps) not concrete implementations
 *
 * Shape-Blind Design:
 * This operation is data-driven and works with vertex data from GenericModelRenderer (GMR).
 * GMR is the single source of truth for mesh topology. Vertex updates work with any
 * vertex count and mesh structure determined by GMR's data model.
 *
 * Data Flow: Position change → Memory update → GPU VBO update → Mesh instance synchronization
 */
public class MeshVertexPositionUpdater {

    private static final Logger logger = LoggerFactory.getLogger(MeshVertexPositionUpdater.class);

    // References to vertex data (not owned by this class - Dependency Inversion)
    private final float[] vertexPositions;
    private final float[] allMeshVertices;
    private final Map<Integer, List<Integer>> uniqueToMeshMapping;
    private final int vbo;
    private final int vertexCount;

    /**
     * Create a vertex position updater.
     *
     * @param vertexPositions Array of unique vertex positions (will be modified)
     * @param allMeshVertices Array of all mesh vertices (will be modified, can be null)
     * @param uniqueToMeshMapping Mapping from unique vertex index to mesh vertex indices
     * @param vbo OpenGL VBO handle
     * @param vertexCount Number of unique vertices
     */
    public MeshVertexPositionUpdater(float[] vertexPositions,
                                     float[] allMeshVertices,
                                     Map<Integer, List<Integer>> uniqueToMeshMapping,
                                     int vbo,
                                     int vertexCount) {
        this.vertexPositions = vertexPositions;
        this.allMeshVertices = allMeshVertices;
        this.uniqueToMeshMapping = uniqueToMeshMapping;
        this.vbo = vbo;
        this.vertexCount = vertexCount;
    }

    /**
     * Update a single unique vertex position.
     * This updates:
     * 1. In-memory position array (unique vertices)
     * 2. GPU VBO (for rendering)
     * 3. All mesh instances (to prevent duplication bug)
     *
     * @param uniqueIndex Unique vertex index to update
     * @param position New position in model space
     * @return true if update succeeded, false otherwise
     */
    public boolean updatePosition(int uniqueIndex, Vector3f position) {
        // Validate inputs
        if (!validateInputs(uniqueIndex, position)) {
            return false;
        }

        try {
            // Step 1: Update in-memory position array
            updateInMemoryPosition(uniqueIndex, position);

            // Step 2: Update GPU VBO
            updateVBO(uniqueIndex, position);

            // Step 3: Update all mesh instances
            updateMeshInstances(uniqueIndex, position);

            logger.trace("Updated unique vertex {} position to ({}, {}, {})",
                    uniqueIndex,
                    String.format("%.2f", position.x),
                    String.format("%.2f", position.y),
                    String.format("%.2f", position.z));

            return true;

        } catch (Exception e) {
            logger.error("Error updating vertex position for index {}", uniqueIndex, e);
            return false;
        }
    }

    /**
     * Validate update inputs.
     */
    private boolean validateInputs(int uniqueIndex, Vector3f position) {
        if (uniqueIndex < 0 || uniqueIndex >= vertexCount) {
            logger.warn("Invalid vertex index for position update: {} (max: {})",
                    uniqueIndex, vertexCount - 1);
            return false;
        }

        if (position == null) {
            logger.warn("Cannot update vertex position: position is null");
            return false;
        }

        if (vertexPositions == null) {
            logger.warn("Cannot update vertex position: vertexPositions array is null");
            return false;
        }

        return true;
    }

    /**
     * Update in-memory position array.
     */
    private void updateInMemoryPosition(int uniqueIndex, Vector3f position) {
        int posIndex = uniqueIndex * 3;
        vertexPositions[posIndex] = position.x;
        vertexPositions[posIndex + 1] = position.y;
        vertexPositions[posIndex + 2] = position.z;
    }

    /**
     * Update GPU VBO with new position.
     * Only updates the position components, leaving color unchanged.
     */
    private void updateVBO(int uniqueIndex, Vector3f position) {
        // VBO layout: 6 floats per vertex (3 position, 3 color)
        int dataIndex = uniqueIndex * 6;
        long offset = (long) dataIndex * Float.BYTES;

        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        // Create temporary array with just position data
        float[] positionData = new float[] { position.x, position.y, position.z };

        // Update only position floats in VBO (leave color unchanged)
        glBufferSubData(GL_ARRAY_BUFFER, offset, positionData);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Update ALL mesh instances of this unique vertex.
     * CRITICAL FIX: Prevents the "cloning" bug where old vertex stays visible.
     *
     * Mesh instances are determined by GMR's data model - a unique vertex may appear
     * in multiple mesh instances depending on the topology.
     */
    private void updateMeshInstances(int uniqueIndex, Vector3f position) {
        if (allMeshVertices == null || uniqueToMeshMapping == null) {
            return; // No mesh synchronization needed
        }

        List<Integer> meshIndices = uniqueToMeshMapping.get(uniqueIndex);
        if (meshIndices == null) {
            return; // No mesh instances for this vertex
        }

        int updatedCount = 0;
        for (Integer meshIndex : meshIndices) {
            int meshPosIndex = meshIndex * 3;

            // Bounds check
            if (meshPosIndex + 2 < allMeshVertices.length) {
                allMeshVertices[meshPosIndex] = position.x;
                allMeshVertices[meshPosIndex + 1] = position.y;
                allMeshVertices[meshPosIndex + 2] = position.z;
                updatedCount++;
            }
        }

        logger.trace("Updated {} mesh instances for unique vertex {}",
                updatedCount, uniqueIndex);
    }
}
