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

        // Check if water exists above or below for vertical blending
        WaterBlock waterAbove = Water.getWaterBlock(blockX, blockY + 1, blockZ);
        WaterBlock waterBelow = Water.getWaterBlock(blockX, blockY - 1, blockZ);
        BlockType blockBelow = (blockY > 0) ? world.getBlockAt(blockX, blockY - 1, blockZ) : BlockType.AIR;

        // If there's water above that's flowing down, corners should be full height
        boolean hasWaterAbove = waterAbove != null;

        // If there's water below and this water can flow down, adjust bottom connection
        boolean shouldConnectBelow = waterBelow != null || blockBelow == BlockType.WATER;

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
            int waterNeighborCount = 0;
            int solidNeighborCount = 0;
            boolean allSourceBlocks = true;
            float heightSum = 0.0f;

            // Sample horizontal neighbors
            for (int[] offset : cornerOffsets[corner]) {
                int sampleX = blockX + offset[0];
                int sampleZ = blockZ + offset[1];

                // Check if this is a solid block (wall)
                BlockType neighborType = world.getBlockAt(sampleX, blockY, sampleZ);
                if (neighborType != null && !neighborType.isTransparent() && neighborType != BlockType.WATER) {
                    solidNeighborCount++;
                    continue; // Skip solid blocks in height calculation
                }

                float height = resolveWaterHeight(sampleX, blockY, sampleZ, world);
                if (!Float.isNaN(height)) {
                    waterNeighborCount++;
                    heightSum += height;

                    // Check if this is a source block
                    WaterBlock neighborWater = Water.getWaterBlock(sampleX, blockY, sampleZ);
                    if (neighborWater == null || !neighborWater.isSource()) {
                        allSourceBlocks = false;
                    }

                    minHeight = Math.min(minHeight, height);
                    hasWater = true;
                }

                // Also check diagonally adjacent water blocks above for better blending
                if (hasWaterAbove && (offset[0] != 0 || offset[1] != 0)) {
                    float heightAbove = resolveWaterHeight(sampleX, blockY + 1, sampleZ, world);
                    if (!Float.isNaN(heightAbove)) {
                        // Blend with water above if it exists diagonally
                        minHeight = Math.max(minHeight, heightAbove * 0.5f);
                    }
                }

                // Check diagonally adjacent water blocks below for better downward connections
                if (shouldConnectBelow && (offset[0] != 0 || offset[1] != 0)) {
                    float heightBelow = resolveWaterHeight(sampleX, blockY - 1, sampleZ, world);
                    if (!Float.isNaN(heightBelow)) {
                        // Ensure smooth connection to water below
                        minHeight = Math.min(minHeight, Math.max(minHeight, heightBelow * 0.75f));
                    }
                }
            }

            // Calculate final corner height with improved blending
            WaterBlock currentWater = Water.getWaterBlock(blockX, blockY, blockZ);

            if (solidNeighborCount > 0) {
                // Corner is against one or more walls
                if (allSourceBlocks && currentWater != null && currentWater.isSource()) {
                    // All water neighbors are sources - maintain full height
                    heights[corner] = clampWaterHeight(blockHeight);
                } else if (waterNeighborCount > 0) {
                    // Flowing water against walls - use minimal blending to prevent bulging
                    // Calculate average but only apply small correction to minimum height
                    float avgHeight = heightSum / waterNeighborCount;

                    // Only lift the corner slightly above minimum if there's a wall
                    // This prevents both sharp dips AND unnatural bulging
                    float targetHeight = minHeight;

                    // If the average is significantly higher than minimum, lift corner slightly
                    if (avgHeight - minHeight > 0.05f) {
                        targetHeight = minHeight + (avgHeight - minHeight) * 0.25f;
                    }

                    heights[corner] = clampWaterHeight(targetHeight);
                } else {
                    heights[corner] = clampWaterHeight(blockHeight);
                }
            } else {
                // Open corner (no walls) - use minimum height for natural flow appearance
                heights[corner] = hasWater ? clampWaterHeight(minHeight) : 0.0f;
            }
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

        // If there's water below, ensure seamless connection
        if (belowType == BlockType.WATER) {
            WaterBlock waterBelow = Water.getWaterBlock(blockX, belowY, blockZ);
            if (waterBelow != null) {
                // Connect to the top of the water below
                float waterBelowHeight = waterBelow.level() == WaterBlock.SOURCE_LEVEL ?
                    0.875f : (8 - waterBelow.level()) * 0.875f / 8.0f;
                waterBelowHeight = clampWaterHeight(waterBelowHeight);

                // Return the top of the water block below, ensuring smooth connection
                return belowY + waterBelowHeight;
            }
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
