package com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsBufferLayout;

/**
 * Mighty Mesh System - Snow Layer Geometry Generator.
 *
 * Generates variable-height geometry for snow layers based on the SnowLayerManager.
 * Snow layers can be 1-8 units high (0.125 to 1.0 blocks), allowing for gradual snow accumulation.
 *
 * Design Philosophy:
 * - Single Responsibility: Only handles snow layer geometry generation
 * - DRY: Reuses face generation logic with configurable height
 * - Performance: Pre-computed normals, minimal allocations
 *
 * Face Indices:
 * - 0: Top (+Y) - Always rendered at snow height
 * - 1: Bottom (-Y) - Always at y=0
 * - 2: North (-Z) - Height adjusted
 * - 3: South (+Z) - Height adjusted
 * - 4: East (+X) - Height adjusted
 * - 5: West (-X) - Height adjusted
 *
 * @since MMS 1.0
 */
public class MmsSnowLayerGenerator implements MmsGeometryService {

    private final World world;

    // Pre-computed face normals (same as cuboid)
    private static final float[][] FACE_NORMALS = {
        {0, 1, 0},   // Top
        {0, -1, 0},  // Bottom
        {0, 0, -1},  // North
        {0, 0, 1},   // South
        {1, 0, 0},   // East
        {-1, 0, 0}   // West
    };

    /**
     * Creates a snow layer generator with world access for layer data.
     *
     * @param world World instance for accessing SnowLayerManager
     */
    public MmsSnowLayerGenerator(World world) {
        if (world == null) {
            throw new IllegalArgumentException("World cannot be null");
        }
        this.world = world;
    }

    @Override
    public float[] generateFaceVertices(int face, float worldX, float worldY, float worldZ) {
        if (face < 0 || face >= 6) {
            throw new IllegalArgumentException("Invalid face index: " + face);
        }

        // Get snow height from SnowLayerManager
        int blockX = (int) Math.floor(worldX);
        int blockY = (int) Math.floor(worldY);
        int blockZ = (int) Math.floor(worldZ);

        float snowHeight = world.getSnowHeight(blockX, blockY, blockZ);

        float[] vertices = new float[MmsBufferLayout.POSITION_SIZE * MmsBufferLayout.VERTICES_PER_QUAD];

        // Generate vertices based on face type
        switch (face) {
            case 0 -> generateTopFace(vertices, worldX, worldY, worldZ, snowHeight);
            case 1 -> generateBottomFace(vertices, worldX, worldY, worldZ);
            case 2 -> generateNorthFace(vertices, worldX, worldY, worldZ, snowHeight);
            case 3 -> generateSouthFace(vertices, worldX, worldY, worldZ, snowHeight);
            case 4 -> generateEastFace(vertices, worldX, worldY, worldZ, snowHeight);
            case 5 -> generateWestFace(vertices, worldX, worldY, worldZ, snowHeight);
        }

        return vertices;
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

    /**
     * Generates top face vertices at snow height.
     * CCW order when viewed from above: v0=(0,h,1) v1=(1,h,1) v2=(1,h,0) v3=(0,h,0)
     */
    private void generateTopFace(float[] vertices, float x, float y, float z, float height) {
        float topY = y + height;

        // v0: (0, h, 1) - bottom-left
        vertices[0] = x + 0;
        vertices[1] = topY;
        vertices[2] = z + 1;

        // v1: (1, h, 1) - bottom-right
        vertices[3] = x + 1;
        vertices[4] = topY;
        vertices[5] = z + 1;

        // v2: (1, h, 0) - top-right
        vertices[6] = x + 1;
        vertices[7] = topY;
        vertices[8] = z + 0;

        // v3: (0, h, 0) - top-left
        vertices[9] = x + 0;
        vertices[10] = topY;
        vertices[11] = z + 0;
    }

    /**
     * Generates bottom face vertices at y=0.
     * CCW order when viewed from below: v0=(0,0,0) v1=(1,0,0) v2=(1,0,1) v3=(0,0,1)
     */
    private void generateBottomFace(float[] vertices, float x, float y, float z) {
        // v0: (0, 0, 0)
        vertices[0] = x + 0;
        vertices[1] = y + 0;
        vertices[2] = z + 0;

        // v1: (1, 0, 0)
        vertices[3] = x + 1;
        vertices[4] = y + 0;
        vertices[5] = z + 0;

        // v2: (1, 0, 1)
        vertices[6] = x + 1;
        vertices[7] = y + 0;
        vertices[8] = z + 1;

        // v3: (0, 0, 1)
        vertices[9] = x + 0;
        vertices[10] = y + 0;
        vertices[11] = z + 1;
    }

    /**
     * Generates north face (-Z) with height-adjusted top vertices.
     * CCW order when viewed from -Z: v0=(1,0,0) v1=(0,0,0) v2=(0,h,0) v3=(1,h,0)
     */
    private void generateNorthFace(float[] vertices, float x, float y, float z, float height) {
        float topY = y + height;

        // v0: (1, 0, 0)
        vertices[0] = x + 1;
        vertices[1] = y + 0;
        vertices[2] = z + 0;

        // v1: (0, 0, 0)
        vertices[3] = x + 0;
        vertices[4] = y + 0;
        vertices[5] = z + 0;

        // v2: (0, h, 0)
        vertices[6] = x + 0;
        vertices[7] = topY;
        vertices[8] = z + 0;

        // v3: (1, h, 0)
        vertices[9] = x + 1;
        vertices[10] = topY;
        vertices[11] = z + 0;
    }

    /**
     * Generates south face (+Z) with height-adjusted top vertices.
     * CCW order when viewed from +Z: v0=(0,0,1) v1=(1,0,1) v2=(1,h,1) v3=(0,h,1)
     */
    private void generateSouthFace(float[] vertices, float x, float y, float z, float height) {
        float topY = y + height;

        // v0: (0, 0, 1)
        vertices[0] = x + 0;
        vertices[1] = y + 0;
        vertices[2] = z + 1;

        // v1: (1, 0, 1)
        vertices[3] = x + 1;
        vertices[4] = y + 0;
        vertices[5] = z + 1;

        // v2: (1, h, 1)
        vertices[6] = x + 1;
        vertices[7] = topY;
        vertices[8] = z + 1;

        // v3: (0, h, 1)
        vertices[9] = x + 0;
        vertices[10] = topY;
        vertices[11] = z + 1;
    }

    /**
     * Generates east face (+X) with height-adjusted top vertices.
     * CCW order when viewed from +X: v0=(1,0,1) v1=(1,0,0) v2=(1,h,0) v3=(1,h,1)
     */
    private void generateEastFace(float[] vertices, float x, float y, float z, float height) {
        float topY = y + height;

        // v0: (1, 0, 1)
        vertices[0] = x + 1;
        vertices[1] = y + 0;
        vertices[2] = z + 1;

        // v1: (1, 0, 0)
        vertices[3] = x + 1;
        vertices[4] = y + 0;
        vertices[5] = z + 0;

        // v2: (1, h, 0)
        vertices[6] = x + 1;
        vertices[7] = topY;
        vertices[8] = z + 0;

        // v3: (1, h, 1)
        vertices[9] = x + 1;
        vertices[10] = topY;
        vertices[11] = z + 1;
    }

    /**
     * Generates west face (-X) with height-adjusted top vertices.
     * CCW order when viewed from -X: v0=(0,0,0) v1=(0,0,1) v2=(0,h,1) v3=(0,h,0)
     */
    private void generateWestFace(float[] vertices, float x, float y, float z, float height) {
        float topY = y + height;

        // v0: (0, 0, 0)
        vertices[0] = x + 0;
        vertices[1] = y + 0;
        vertices[2] = z + 0;

        // v1: (0, 0, 1)
        vertices[3] = x + 0;
        vertices[4] = y + 0;
        vertices[5] = z + 1;

        // v2: (0, h, 1)
        vertices[6] = x + 0;
        vertices[7] = topY;
        vertices[8] = z + 1;

        // v3: (0, h, 0)
        vertices[9] = x + 0;
        vertices[10] = topY;
        vertices[11] = z + 0;
    }
}
