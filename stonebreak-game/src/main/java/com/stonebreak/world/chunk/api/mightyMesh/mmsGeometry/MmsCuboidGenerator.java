package com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsBufferLayout;

/**
 * Mighty Mesh System - Standard cuboid (box) geometry generator.
 *
 * Generates vertex positions and normals for standard cube-shaped blocks.
 * This is the most common block type in voxel games.
 *
 * Design Philosophy:
 * - DRY: Single implementation for all cube blocks
 * - Performance: Pre-computed face normals
 * - KISS: Simple, well-tested geometry
 *
 * Face Indices:
 * - 0: Top (+Y)
 * - 1: Bottom (-Y)
 * - 2: North (-Z)
 * - 3: South (+Z)
 * - 4: East (+X)
 * - 5: West (-X)
 *
 * @since MMS 1.0
 */
public class MmsCuboidGenerator implements MmsGeometryService {

    // Pre-computed face normals (constant across all cubes)
    private static final float[][] FACE_NORMALS = {
        {0, 1, 0},   // Top
        {0, -1, 0},  // Bottom
        {0, 0, -1},  // North
        {0, 0, 1},   // South
        {1, 0, 0},   // East
        {-1, 0, 0}   // West
    };

    /**
     * Face vertex offsets for standard cube.
     * Each face has 4 vertices with (x, y, z) offsets from block origin.
     * Vertices are ordered counter-clockwise when viewed from OUTSIDE the cube.
     *
     * With index pattern [0,1,2] and [0,2,3] creating two triangles:
     * - Triangle 1: vertices 0 -> 1 -> 2 (CCW from outside)
     * - Triangle 2: vertices 0 -> 2 -> 3 (CCW from outside)
     */
    private static final float[][][] FACE_VERTEX_OFFSETS = {
        // Top face (+Y) - looking down at top, CCW = bottom-left, bottom-right, top-right, top-left
        {{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}},

        // Bottom face (-Y) - looking up from below, CCW = bottom-left, bottom-right, top-right, top-left
        {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},

        // North face (-Z) - looking at front (z=0), CCW = bottom-left, bottom-right, top-right, top-left
        {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}},

        // South face (+Z) - looking at back (z=1), CCW = bottom-left, bottom-right, top-right, top-left
        {{1, 0, 1}, {0, 0, 1}, {0, 1, 1}, {1, 1, 1}},

        // East face (+X) - looking at right side (x=1), CCW = bottom-left, bottom-right, top-right, top-left
        {{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}},

        // West face (-X) - looking at left side (x=0), CCW = bottom-left, bottom-right, top-right, top-left
        {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}
    };

    @Override
    public float[] generateFaceVertices(int face, BlockType blockType,
                                        float worldX, float worldY, float worldZ,
                                        float blockHeight) {
        return generateFaceVerticesWithWater(face, blockType, worldX, worldY, worldZ,
            blockHeight, null, worldY);
    }

    @Override
    public float[] generateFaceVerticesWithWater(int face, BlockType blockType,
                                                 float worldX, float worldY, float worldZ,
                                                 float blockHeight,
                                                 float[] waterCornerHeights,
                                                 float waterBottomHeight) {
        if (face < 0 || face >= 6) {
            throw new IllegalArgumentException("Invalid face index: " + face);
        }

        float[] vertices = new float[MmsBufferLayout.POSITION_SIZE * MmsBufferLayout.VERTICES_PER_QUAD];
        float[][] offsets = FACE_VERTEX_OFFSETS[face];

        // Handle water blocks with variable corner heights
        boolean isWater = blockType == BlockType.WATER && waterCornerHeights != null;

        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_QUAD; i++) {
            int baseIdx = i * MmsBufferLayout.POSITION_SIZE;

            // Calculate base vertex position
            float x = worldX + offsets[i][0];
            float y = worldY + offsets[i][1] * blockHeight;
            float z = worldZ + offsets[i][2];

            // Apply water-specific modifications
            if (isWater) {
                if (face == 0) {
                    // Top face: use corner-specific heights
                    y = worldY + waterCornerHeights[i];
                } else if (face == 1) {
                    // Bottom face: attach to ground or lower water block
                    y = waterBottomHeight + offsets[i][1] * blockHeight;
                } else {
                    // Side faces: adjust top vertices to water surface
                    if (offsets[i][1] > 0) {
                        // Top vertices use corner heights
                        int cornerIndex = getWaterCornerIndexForSideFace(face, i);
                        if (cornerIndex >= 0) {
                            y = worldY + waterCornerHeights[cornerIndex];
                        }
                    }
                }
            }

            vertices[baseIdx] = x;
            vertices[baseIdx + 1] = y;
            vertices[baseIdx + 2] = z;
        }

        return vertices;
    }

    /**
     * Maps a vertex index on a side face to a water corner height index.
     *
     * @param face Side face index (2-5)
     * @param vertexIndex Vertex index on that face (0-3)
     * @return Corner index (0-3) or -1 if not applicable
     */
    private int getWaterCornerIndexForSideFace(int face, int vertexIndex) {
        // Map side face vertices to water corner heights
        // Corner order: [0,0], [1,0], [1,1], [0,1]
        switch (face) {
            case 2: // North (-Z): z=0
                return (vertexIndex == 2 || vertexIndex == 3) ?
                    (vertexIndex == 2 ? 1 : 0) : -1; // Top vertices only
            case 3: // South (+Z): z=1
                return (vertexIndex == 2 || vertexIndex == 3) ?
                    (vertexIndex == 2 ? 2 : 3) : -1;
            case 4: // East (+X): x=1
                return (vertexIndex == 2 || vertexIndex == 3) ?
                    (vertexIndex == 2 ? 2 : 1) : -1;
            case 5: // West (-X): x=0
                return (vertexIndex == 2 || vertexIndex == 3) ?
                    (vertexIndex == 2 ? 3 : 0) : -1;
            default:
                return -1;
        }
    }

    @Override
    public float[] generateFaceNormals(int face) {
        if (face < 0 || face >= 6) {
            throw new IllegalArgumentException("Invalid face index: " + face);
        }

        float[] normals = new float[MmsBufferLayout.NORMAL_SIZE * MmsBufferLayout.VERTICES_PER_QUAD];
        float[] faceNormal = FACE_NORMALS[face];

        // All 4 vertices of a face share the same normal
        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_QUAD; i++) {
            int baseIdx = i * MmsBufferLayout.NORMAL_SIZE;
            normals[baseIdx] = faceNormal[0];
            normals[baseIdx + 1] = faceNormal[1];
            normals[baseIdx + 2] = faceNormal[2];
        }

        return normals;
    }

    @Override
    public float calculateBlockHeight(BlockType blockType, float worldX, float worldY, float worldZ) {
        // Standard cubes always have full height
        // Override for blocks with variable heights
        if (blockType == BlockType.WATER) {
            return 0.9f; // Water slightly lower than full block
        }
        return 1.0f;
    }

    @Override
    public float[] calculateWaterCornerHeights(int blockX, int blockY, int blockZ, float baseHeight) {
        // This implementation returns uniform height
        // Override in subclass for realistic water surface simulation
        return new float[] {baseHeight, baseHeight, baseHeight, baseHeight};
    }

    /**
     * Validates a face index.
     *
     * @param face Face index to validate
     * @throws IllegalArgumentException if face index is invalid
     */
    protected void validateFaceIndex(int face) {
        if (face < 0 || face >= 6) {
            throw new IllegalArgumentException("Face index must be 0-5, got: " + face);
        }
    }
}
