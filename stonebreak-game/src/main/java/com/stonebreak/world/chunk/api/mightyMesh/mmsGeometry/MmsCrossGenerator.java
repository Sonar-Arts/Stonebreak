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

    // Cross consists of 2 planes, each with 2 sides (front and back)
    private static final int PLANES = 2;
    private static final int SIDES_PER_PLANE = 2;
    private static final int VERTICES_PER_SIDE = 4;

    /**
     * Generates complete cross geometry (8 vertices for 2 intersecting quads).
     *
     * @param worldX World X coordinate
     * @param worldY World Y coordinate
     * @param worldZ World Z coordinate
     * @return Array of vertex positions for entire cross (24 floats = 8 vertices * 3 components)
     */
    public float[] generateCrossVertices(float worldX, float worldY, float worldZ) {
        float[] vertices = new float[MmsBufferLayout.VERTICES_PER_CROSS * MmsBufferLayout.POSITION_SIZE];

        // Cross pattern uses diagonal planes
        // Plane 1: Southwest to Northeast (diagonal \)
        // Plane 2: Northwest to Southeast (diagonal /)

        int idx = 0;

        // Plane 1 - Front face
        vertices[idx++] = worldX + 0.15f; vertices[idx++] = worldY; vertices[idx++] = worldZ + 0.85f;
        vertices[idx++] = worldX + 0.85f; vertices[idx++] = worldY; vertices[idx++] = worldZ + 0.15f;
        vertices[idx++] = worldX + 0.85f; vertices[idx++] = worldY + 1.0f; vertices[idx++] = worldZ + 0.15f;
        vertices[idx++] = worldX + 0.15f; vertices[idx++] = worldY + 1.0f; vertices[idx++] = worldZ + 0.85f;

        // Plane 1 - Back face
        vertices[idx++] = worldX + 0.85f; vertices[idx++] = worldY; vertices[idx++] = worldZ + 0.15f;
        vertices[idx++] = worldX + 0.15f; vertices[idx++] = worldY; vertices[idx++] = worldZ + 0.85f;
        vertices[idx++] = worldX + 0.15f; vertices[idx++] = worldY + 1.0f; vertices[idx++] = worldZ + 0.85f;
        vertices[idx++] = worldX + 0.85f; vertices[idx++] = worldY + 1.0f; vertices[idx++] = worldZ + 0.15f;

        // Plane 2 - Front face
        vertices[idx++] = worldX + 0.85f; vertices[idx++] = worldY; vertices[idx++] = worldZ + 0.85f;
        vertices[idx++] = worldX + 0.15f; vertices[idx++] = worldY; vertices[idx++] = worldZ + 0.15f;
        vertices[idx++] = worldX + 0.15f; vertices[idx++] = worldY + 1.0f; vertices[idx++] = worldZ + 0.15f;
        vertices[idx++] = worldX + 0.85f; vertices[idx++] = worldY + 1.0f; vertices[idx++] = worldZ + 0.85f;

        // Plane 2 - Back face
        vertices[idx++] = worldX + 0.15f; vertices[idx++] = worldY; vertices[idx++] = worldZ + 0.15f;
        vertices[idx++] = worldX + 0.85f; vertices[idx++] = worldY; vertices[idx++] = worldZ + 0.85f;
        vertices[idx++] = worldX + 0.85f; vertices[idx++] = worldY + 1.0f; vertices[idx++] = worldZ + 0.85f;
        vertices[idx++] = worldX + 0.15f; vertices[idx++] = worldY + 1.0f; vertices[idx++] = worldZ + 0.15f;

        return vertices;
    }

    /**
     * Generates normals for cross geometry.
     * Each plane has distinct normals for proper lighting.
     *
     * @return Array of normal vectors (24 floats = 8 vertices * 3 components)
     */
    public float[] generateCrossNormals() {
        float[] normals = new float[MmsBufferLayout.VERTICES_PER_CROSS * MmsBufferLayout.NORMAL_SIZE];

        // Normals calculated for diagonal planes
        // Plane 1: diagonal \ (front faces northeast, back faces southwest)
        float nx1 = 0.7071f;  // 1/sqrt(2)
        float nz1 = -0.7071f;

        // Plane 2: diagonal / (front faces northwest, back faces southeast)
        float nx2 = -0.7071f;
        float nz2 = -0.7071f;

        int idx = 0;

        // Plane 1 - Front face
        for (int i = 0; i < VERTICES_PER_SIDE; i++) {
            normals[idx++] = nx1; normals[idx++] = 0; normals[idx++] = nz1;
        }

        // Plane 1 - Back face
        for (int i = 0; i < VERTICES_PER_SIDE; i++) {
            normals[idx++] = -nx1; normals[idx++] = 0; normals[idx++] = -nz1;
        }

        // Plane 2 - Front face
        for (int i = 0; i < VERTICES_PER_SIDE; i++) {
            normals[idx++] = nx2; normals[idx++] = 0; normals[idx++] = nz2;
        }

        // Plane 2 - Back face
        for (int i = 0; i < VERTICES_PER_SIDE; i++) {
            normals[idx++] = -nx2; normals[idx++] = 0; normals[idx++] = -nz2;
        }

        return normals;
    }

    /**
     * Generates indices for cross geometry (4 quads = 24 indices).
     *
     * @param baseVertexIndex Base vertex index to offset from
     * @return Array of 24 indices for 4 quads
     */
    public int[] generateCrossIndices(int baseVertexIndex) {
        int[] indices = new int[MmsBufferLayout.INDICES_PER_CROSS];
        int idx = 0;

        // Generate indices for 4 quads (2 planes * 2 sides)
        for (int quad = 0; quad < 4; quad++) {
            int base = baseVertexIndex + (quad * VERTICES_PER_SIDE);

            // Triangle 1
            indices[idx++] = base;
            indices[idx++] = base + 1;
            indices[idx++] = base + 2;

            // Triangle 2
            indices[idx++] = base;
            indices[idx++] = base + 2;
            indices[idx++] = base + 3;
        }

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
