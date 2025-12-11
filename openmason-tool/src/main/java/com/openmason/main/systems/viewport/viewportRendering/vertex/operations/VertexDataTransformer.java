package com.openmason.main.systems.viewport.viewportRendering.vertex.operations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Single Responsibility: Transforms vertex data between different formats.
 * Handles expansion, compression, and format conversions for vertex positions.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles vertex data transformations
 * - Open/Closed: Can be extended with new transformation strategies
 * - Interface Segregation: Focused interface for data transformations
 * - Dependency Inversion: Works with arrays and maps, not concrete implementations
 */
public class VertexDataTransformer {

    private static final Logger logger = LoggerFactory.getLogger(VertexDataTransformer.class);

    /**
     * Standard cube vertex count (8 corners).
     */
    private static final int CUBE_VERTEX_COUNT = 8;

    private final float[] vertexPositions;
    private final int vertexCount;

    /**
     * Create a vertex data transformer.
     *
     * @param vertexPositions Array of vertex positions [x0,y0,z0, x1,y1,z1, ...]
     * @param vertexCount Number of vertices
     */
    public VertexDataTransformer(float[] vertexPositions, int vertexCount) {
        this.vertexPositions = vertexPositions;
        this.vertexCount = vertexCount;
    }

    /**
     * Expand vertex positions to a target count using index remapping.
     * After merging, we may have fewer than 8 vertices, but ModelRenderer expects 8.
     * This method expands the merged vertices back to 8 using the index remapping.
     *
     * Use Case: When vertices have been merged and we need to provide the full
     * set of original positions for rendering or export.
     *
     * @param indexRemapping Mapping from old indices to new indices
     * @param targetVertexCount Expected number of vertices (typically 8 for cubes)
     * @return Expanded vertex positions array, or original if no expansion needed
     */
    public float[] expandPositions(Map<Integer, Integer> indexRemapping, int targetVertexCount) {
        // Validation
        if (vertexPositions == null || vertexCount == 0) {
            logger.warn("Cannot expand positions: no vertex data available");
            return vertexPositions;
        }

        if (indexRemapping == null) {
            logger.debug("No index remapping provided, returning original positions");
            return vertexPositions;
        }

        // If already at target count, no expansion needed
        if (vertexCount >= targetVertexCount) {
            logger.trace("Vertex count {} already meets target {}, no expansion needed",
                    vertexCount, targetVertexCount);
            return vertexPositions;
        }

        // Perform expansion
        float[] expandedPositions = new float[targetVertexCount * 3];

        logger.debug("Expanding {} unique vertices to {} for compatibility",
                vertexCount, targetVertexCount);
        logger.debug("Index remapping: {}", indexRemapping);

        for (int oldIdx = 0; oldIdx < targetVertexCount; oldIdx++) {
            Integer newIdx = indexRemapping.get(oldIdx);

            if (newIdx != null && newIdx < vertexCount) {
                // This vertex was merged or kept - use its position
                copyVertexPosition(oldIdx, newIdx, expandedPositions);

                logger.debug("  Vertex {} -> {} at ({}, {}, {})",
                        oldIdx, newIdx,
                        String.format("%.3f", expandedPositions[oldIdx * 3]),
                        String.format("%.3f", expandedPositions[oldIdx * 3 + 1]),
                        String.format("%.3f", expandedPositions[oldIdx * 3 + 2]));
            } else {
                // Missing mapping - use zero position as fallback
                logger.warn("Missing mapping for vertex {}, using zero position", oldIdx);
                setZeroPosition(oldIdx, expandedPositions);
            }
        }

        logger.debug("Expansion complete: {} vertices", targetVertexCount);
        return expandedPositions;
    }

    /**
     * Expand positions to standard cube format (8 vertices).
     * Convenience method that uses the standard cube vertex count.
     *
     * @param indexRemapping Mapping from old indices to new indices
     * @return Expanded vertex positions (24 floats for 8 vertices)
     */
    public float[] expandToCubeFormat(Map<Integer, Integer> indexRemapping) {
        return expandPositions(indexRemapping, CUBE_VERTEX_COUNT);
    }

    /**
     * Copy a vertex position from the current positions to the expanded array.
     */
    private void copyVertexPosition(int destIdx, int srcIdx, float[] destArray) {
        int srcPosIdx = srcIdx * 3;
        int destPosIdx = destIdx * 3;

        destArray[destPosIdx] = vertexPositions[srcPosIdx];
        destArray[destPosIdx + 1] = vertexPositions[srcPosIdx + 1];
        destArray[destPosIdx + 2] = vertexPositions[srcPosIdx + 2];
    }

    /**
     * Set a vertex position to zero in the destination array.
     */
    private void setZeroPosition(int destIdx, float[] destArray) {
        int destPosIdx = destIdx * 3;
        destArray[destPosIdx] = 0.0f;
        destArray[destPosIdx + 1] = 0.0f;
        destArray[destPosIdx + 2] = 0.0f;
    }

    /**
     * Get all vertex positions without transformation.
     * Simple accessor for the underlying data.
     *
     * @return Array of vertex positions
     */
    public float[] getPositions() {
        return vertexPositions;
    }

    /**
     * Get the number of vertices.
     *
     * @return Vertex count
     */
    public int getVertexCount() {
        return vertexCount;
    }
}
