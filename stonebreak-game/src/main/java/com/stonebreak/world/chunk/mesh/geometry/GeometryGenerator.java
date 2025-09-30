package com.stonebreak.world.chunk.mesh.geometry;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.Water;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.world.World;

/**
 * Service responsible for generating vertex positions and normals for block faces.
 * Follows Single Responsibility Principle by handling only geometry generation.
 */
public class GeometryGenerator {

    private static final float MIN_DISPLAYED_WATER_HEIGHT = 0.0625f; // 1/16th block - allows level 7 to render properly
    private static final float WATER_ATTACHMENT_EPSILON = 0.001f;
    
    /**
     * Generates vertices for a specific face of a block.
     * Returns the number of vertices added (always 12 floats = 4 vertices * 3 components each).
     */
    public int generateFaceVertices(int face, BlockType blockType, float worldX, float worldY, float worldZ,
                                    float blockHeight, float[] waterCornerHeights, float waterBottomY,
                                    float[] vertexArray, int vertexIndex) {
        float bottomY = (blockType == BlockType.WATER) ? waterBottomY : worldY;

        switch (face) {
            case 0 -> { // Top face (y+1)
                if (blockType == BlockType.WATER && waterCornerHeights != null) {
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = worldY + waterCornerHeights[0]; vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY + waterCornerHeights[1]; vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY + waterCornerHeights[2]; vertexArray[vertexIndex++] = worldZ + 1;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = worldY + waterCornerHeights[3]; vertexArray[vertexIndex++] = worldZ + 1;
                } else {
                    float topY = worldY + blockHeight;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ + 1;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ + 1;
                }
            }
            case 1 -> { // Bottom face (y-1)
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = bottomY; vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = bottomY; vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = bottomY; vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = bottomY; vertexArray[vertexIndex++] = worldZ;
            }
            case 2 -> { // Front face (z+1)
                if (blockType == BlockType.WATER && waterCornerHeights != null) {
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = bottomY;                         vertexArray[vertexIndex++] = worldZ + 1;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = worldY + waterCornerHeights[3]; vertexArray[vertexIndex++] = worldZ + 1;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY + waterCornerHeights[2]; vertexArray[vertexIndex++] = worldZ + 1;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = bottomY;                         vertexArray[vertexIndex++] = worldZ + 1;
                } else {
                    float topY = worldY + blockHeight;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = bottomY; vertexArray[vertexIndex++] = worldZ + 1;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = topY;    vertexArray[vertexIndex++] = worldZ + 1;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = topY;    vertexArray[vertexIndex++] = worldZ + 1;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = bottomY; vertexArray[vertexIndex++] = worldZ + 1;
                }
            }
            case 3 -> { // Back face (z-1)
                if (blockType == BlockType.WATER && waterCornerHeights != null) {
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = bottomY;                         vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = bottomY;                         vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY + waterCornerHeights[1]; vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = worldY + waterCornerHeights[0]; vertexArray[vertexIndex++] = worldZ;
                } else {
                    float topY = worldY + blockHeight;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = bottomY; vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = bottomY; vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = topY;    vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = topY;    vertexArray[vertexIndex++] = worldZ;
                }
            }
            case 4 -> { // Right face (x+1)
                if (blockType == BlockType.WATER && waterCornerHeights != null) {
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = bottomY;                         vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = bottomY;                         vertexArray[vertexIndex++] = worldZ + 1;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY + waterCornerHeights[2]; vertexArray[vertexIndex++] = worldZ + 1;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY + waterCornerHeights[1]; vertexArray[vertexIndex++] = worldZ;
                } else {
                    float topY = worldY + blockHeight;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = bottomY; vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = bottomY; vertexArray[vertexIndex++] = worldZ + 1;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = topY;    vertexArray[vertexIndex++] = worldZ + 1;
                    vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = topY;    vertexArray[vertexIndex++] = worldZ;
                }
            }
            case 5 -> { // Left face (x-1)
                if (blockType == BlockType.WATER && waterCornerHeights != null) {
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = bottomY;                         vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = worldY + waterCornerHeights[0]; vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = worldY + waterCornerHeights[3]; vertexArray[vertexIndex++] = worldZ + 1;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = bottomY;                         vertexArray[vertexIndex++] = worldZ + 1;
                } else {
                    float topY = worldY + blockHeight;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = bottomY; vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = topY;    vertexArray[vertexIndex++] = worldZ;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = topY;    vertexArray[vertexIndex++] = worldZ + 1;
                    vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = bottomY; vertexArray[vertexIndex++] = worldZ + 1;
                }
            }
        }
        return 12; // Always 12 floats added (4 vertices * 3 components each)
    }

    /**
     * Generates normals for a specific face of a block.
     * Returns the number of normals added (always 12 floats = 4 normals * 3 components each).
     */
    public int generateFaceNormals(int face, float[] normalArray, int normalIndex) {
        switch (face) {
            case 0 -> { // Top face
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f;
            }
            case 1 -> { // Bottom face
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f;
            }
            case 2 -> { // Front face
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f;
            }
            case 3 -> { // Back face
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f;
            }
            case 4 -> { // Right face
                normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
            }
            case 5 -> { // Left face
                normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
            }
        }
        return 12; // Always 12 floats added (4 normals * 3 components each)
    }
    
    /**
     * Gets the visual height for blocks that can have variable heights.
     */
    public float getBlockHeight(BlockType blockType, float worldX, float worldY, float worldZ, World world) {
        if (blockType == BlockType.WATER) {
            int blockX = (int)Math.floor(worldX);
            int blockY = (int)Math.floor(worldY);
            int blockZ = (int)Math.floor(worldZ);

            WaterBlock waterBlock = Water.getWaterBlock(blockX, blockY, blockZ);
            if (waterBlock != null) {
                float height = waterBlock.level() == WaterBlock.SOURCE_LEVEL ? 0.875f : (8 - waterBlock.level()) * 0.875f / 8.0f;
                return clampWaterHeight(height);
            }

            float level = Water.getWaterLevel(blockX, blockY, blockZ);
            if (level > 0.0f) {
                return clampWaterHeight(level * 0.875f);
            }

            if (world != null && world.getBlockAt(blockX, blockY, blockZ) == BlockType.WATER) {
                return clampWaterHeight(0.875f);
            }

            return 0.0f;
        } else if (blockType == BlockType.SNOW) {
            // Get snow layer height from world
            if (world != null) {
                return world.getSnowLayerManager().getSnowHeight((int)worldX, (int)worldY, (int)worldZ);
            }
        }
        return 1.0f; // Default full height
    }

    public float[] computeWaterCornerHeights(int blockX, int blockY, int blockZ, float blockHeight, World world) {
        float initialHeight = clampWaterHeight(blockHeight);
        float[] heights = new float[] {initialHeight, initialHeight, initialHeight, initialHeight};

        // Offsets of blocks contributing to each corner (dx, dz)
        int[][][] cornerOffsets = new int[][][] {
            { {0, 0}, {-1, 0}, {0, -1}, {-1, -1} }, // Corner 0 (x, z)
            { {0, 0}, {1, 0}, {0, -1}, {1, -1} },   // Corner 1 (x+1, z)
            { {0, 0}, {1, 0}, {0, 1}, {1, 1} },     // Corner 2 (x+1, z+1)
            { {0, 0}, {-1, 0}, {0, 1}, {-1, 1} }    // Corner 3 (x, z+1)
        };

        for (int corner = 0; corner < 4; corner++) {
            float minHeight = clampWaterHeight(blockHeight);
            boolean hasWater = true; // current block is always water

            for (int[] offset : cornerOffsets[corner]) {
                int sampleX = blockX + offset[0];
                int sampleZ = blockZ + offset[1];

                float height = resolveWaterHeight(sampleX, blockY, sampleZ, world);
                if (!Float.isNaN(height)) {
                    minHeight = Math.min(minHeight, height);
                    hasWater = true;
                }
            }

            heights[corner] = hasWater ? clampWaterHeight(minHeight) : 0.0f;
        }

        return heights;
    }

    public float computeWaterBottomAttachmentHeight(float worldX, float worldY, float worldZ, World world) {
        if (world == null) {
            return worldY;
        }

        int blockX = (int)Math.floor(worldX);
        int blockY = (int)Math.floor(worldY);
        int blockZ = (int)Math.floor(worldZ);

        if (blockY <= 0) {
            return worldY;
        }

        int belowY = blockY - 1;
        BlockType belowType = world.getBlockAt(blockX, belowY, blockZ);
        if (belowType == null) {
            return worldY;
        }

        float neighborHeight = getBlockHeight(belowType, blockX, belowY, blockZ, world);
        neighborHeight = Math.max(0.0f, Math.min(1.0f, neighborHeight));

        float attachmentHeight = Math.min(worldY, belowY + neighborHeight);
        if (attachmentHeight >= worldY - WATER_ATTACHMENT_EPSILON) {
            return worldY;
        }

        float adjustedAttachment = Math.max(belowY, attachmentHeight - WATER_ATTACHMENT_EPSILON);
        return Math.max(worldY - 1.0f, adjustedAttachment);
    }

    private float resolveWaterHeight(int x, int y, int z, World world) {
        WaterBlock waterBlock = Water.getWaterBlock(x, y, z);
        if (waterBlock != null) {
            float height = waterBlock.level() == WaterBlock.SOURCE_LEVEL ? 0.875f : (8 - waterBlock.level()) * 0.875f / 8.0f;
            return clampWaterHeight(height);
        }

        float level = Water.getWaterLevel(x, y, z);
        if (level > 0.0f) {
            return clampWaterHeight(level * 0.875f);
        }

        if (world != null && world.getBlockAt(x, y, z) == BlockType.WATER) {
            return clampWaterHeight(0.875f);
        }

        return Float.NaN;
    }

    private float clampWaterHeight(float height) {
        return Math.max(MIN_DISPLAYED_WATER_HEIGHT, Math.min(0.875f, height));
    }
}
