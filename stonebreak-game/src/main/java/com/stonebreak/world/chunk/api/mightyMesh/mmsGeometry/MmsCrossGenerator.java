package com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsBufferLayout;

/**
 * Mighty Mesh System - Cross-section geometry generator for flower blocks.
 *
 * Generates X-shaped geometry for decorative blocks like flowers.
 * Creates two intersecting quads at 45-degree angles.
 *
 * Design Philosophy:
 * - Reusable: Works for all cross-section blocks
 * - Efficient: Pre-computed geometry offsets
 * - KISS: Simple cross pattern
 *
 * @since MMS 1.0
 */
public class MmsCrossGenerator implements MmsGeometryService {

    // Cross consists of 2 planes, each with 4 vertices (shared for both sides via index winding)
    private static final int PLANES = 2;
    private static final int VERTICES_PER_PLANE = 4;

    /**
     * Generates cross geometry with 2 planes (8 vertices total).
     * Each plane is rendered double-sided via index winding (no duplicate vertices).
     * This prevents z-fighting while maintaining visibility from all angles.
     *
     * @param worldX World X coordinate
     * @param worldY World Y coordinate
     * @param worldZ World Z coordinate
     * @return Array of vertex positions for cross (24 floats = 8 vertices * 3 components)
     */
    public float[] generateCrossVertices(float worldX, float worldY, float worldZ) {
        float[] vertices = new float[MmsBufferLayout.VERTICES_PER_CROSS * MmsBufferLayout.POSITION_SIZE];

        // Cross pattern uses 2 diagonal planes
        // Plane 1: Southwest to Northeast (diagonal \)
        // Plane 2: Northwest to Southeast (diagonal /)
        // Each plane uses index winding for double-sided rendering

        int idx = 0;

        // Plane 1 (vertices 0-3)
        vertices[idx++] = worldX + 0.15f; vertices[idx++] = worldY; vertices[idx++] = worldZ + 0.85f;
        vertices[idx++] = worldX + 0.85f; vertices[idx++] = worldY; vertices[idx++] = worldZ + 0.15f;
        vertices[idx++] = worldX + 0.85f; vertices[idx++] = worldY + 1.0f; vertices[idx++] = worldZ + 0.15f;
        vertices[idx++] = worldX + 0.15f; vertices[idx++] = worldY + 1.0f; vertices[idx++] = worldZ + 0.85f;

        // Plane 2 (vertices 4-7)
        vertices[idx++] = worldX + 0.85f; vertices[idx++] = worldY; vertices[idx++] = worldZ + 0.85f;
        vertices[idx++] = worldX + 0.15f; vertices[idx++] = worldY; vertices[idx++] = worldZ + 0.15f;
        vertices[idx++] = worldX + 0.15f; vertices[idx++] = worldY + 1.0f; vertices[idx++] = worldZ + 0.15f;
        vertices[idx++] = worldX + 0.85f; vertices[idx++] = worldY + 1.0f; vertices[idx++] = worldZ + 0.85f;

        return vertices;
    }

    /**
     * Generates normals for cross geometry (2 planes, 8 vertices).
     * Each plane has consistent normals for proper lighting on both sides.
     *
     * @return Array of normal vectors (24 floats = 8 vertices * 3 components)
     */
    public float[] generateCrossNormals() {
        float[] normals = new float[MmsBufferLayout.VERTICES_PER_CROSS * MmsBufferLayout.NORMAL_SIZE];

        // Normals calculated for diagonal planes
        // Plane 1: diagonal \ (faces northeast)
        float nx1 = 0.7071f;  // 1/sqrt(2)
        float nz1 = -0.7071f;

        // Plane 2: diagonal / (faces northwest)
        float nx2 = -0.7071f;
        float nz2 = -0.7071f;

        int idx = 0;

        // Plane 1 (vertices 0-3)
        for (int i = 0; i < VERTICES_PER_PLANE; i++) {
            normals[idx++] = nx1; normals[idx++] = 0; normals[idx++] = nz1;
        }

        // Plane 2 (vertices 4-7)
        for (int i = 0; i < VERTICES_PER_PLANE; i++) {
            normals[idx++] = nx2; normals[idx++] = 0; normals[idx++] = nz2;
        }

        return normals;
    }

    /**
     * Generates indices for cross geometry (4 quads via index winding = 24 indices).
     * Each plane is rendered from both sides using front-facing and back-facing index orders.
     * This prevents z-fighting by using a single set of vertices with proper winding.
     *
     * @param baseVertexIndex Base vertex index to offset from
     * @return Array of 24 indices for 2 double-sided quads
     */
    public int[] generateCrossIndices(int baseVertexIndex) {
        int[] indices = new int[MmsBufferLayout.INDICES_PER_CROSS];
        int idx = 0;

        // Cross uses 8 vertices (2 planes × 4 vertices each)
        // Each plane is rendered from both sides via index winding
        // Front face: CCW winding (0→1→2, 0→2→3)
        // Back face: CW winding (0→3→2, 0→2→1) which appears as CCW from the back

        // Plane 1 Front (vertices 0-3, CCW from front)
        indices[idx++] = baseVertexIndex + 0;
        indices[idx++] = baseVertexIndex + 1;
        indices[idx++] = baseVertexIndex + 2;

        indices[idx++] = baseVertexIndex + 0;
        indices[idx++] = baseVertexIndex + 2;
        indices[idx++] = baseVertexIndex + 3;

        // Plane 1 Back (vertices 0-3, reversed winding for back visibility)
        indices[idx++] = baseVertexIndex + 2;
        indices[idx++] = baseVertexIndex + 1;
        indices[idx++] = baseVertexIndex + 0;

        indices[idx++] = baseVertexIndex + 3;
        indices[idx++] = baseVertexIndex + 2;
        indices[idx++] = baseVertexIndex + 0;

        // Plane 2 Front (vertices 4-7, CCW from front)
        indices[idx++] = baseVertexIndex + 4;
        indices[idx++] = baseVertexIndex + 5;
        indices[idx++] = baseVertexIndex + 6;

        indices[idx++] = baseVertexIndex + 4;
        indices[idx++] = baseVertexIndex + 6;
        indices[idx++] = baseVertexIndex + 7;

        // Plane 2 Back (vertices 4-7, reversed winding for back visibility)
        indices[idx++] = baseVertexIndex + 6;
        indices[idx++] = baseVertexIndex + 5;
        indices[idx++] = baseVertexIndex + 4;

        indices[idx++] = baseVertexIndex + 7;
        indices[idx++] = baseVertexIndex + 6;
        indices[idx++] = baseVertexIndex + 4;

        return indices;
    }

    // === MmsGeometryService Implementation ===
    // Cross blocks don't use standard face-based generation

    @Override
    public float[] generateFaceVertices(int face, BlockType blockType,
                                        float worldX, float worldY, float worldZ,
                                        float blockHeight) {
        throw new UnsupportedOperationException(
            "Cross blocks use generateCrossVertices() instead of per-face generation"
        );
    }

    @Override
    public float[] generateFaceVerticesWithWater(int face, BlockType blockType,
                                                 float worldX, float worldY, float worldZ,
                                                 float blockHeight,
                                                 float[] waterCornerHeights,
                                                 float waterBottomHeight) {
        throw new UnsupportedOperationException(
            "Cross blocks use generateCrossVertices() instead of per-face generation"
        );
    }

    @Override
    public float[] generateFaceNormals(int face) {
        throw new UnsupportedOperationException(
            "Cross blocks use generateCrossNormals() instead of per-face generation"
        );
    }

    @Override
    public float calculateBlockHeight(BlockType blockType, float worldX, float worldY, float worldZ) {
        return 1.0f; // Cross blocks always full height
    }

    @Override
    public float[] calculateWaterCornerHeights(int blockX, int blockY, int blockZ, float baseHeight) {
        return null; // Cross blocks don't support water
    }

    @Override
    public boolean requiresCustomGeometry(BlockType blockType) {
        return blockType == BlockType.ROSE || blockType == BlockType.DANDELION;
    }
}
