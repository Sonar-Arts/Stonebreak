package com.stonebreak.world.chunk.mesh.texture;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.Water;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.core.Game;
import com.stonebreak.rendering.WaterEffects;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Service responsible for determining if faces should be rendered and getting adjacent blocks.
 * Follows Single Responsibility Principle by handling only face visibility logic.
 */
public class FaceRenderingService {
    
    /**
     * Determines if a face should be rendered based on block types and water logic
     */
    public boolean shouldRenderFace(BlockType blockType, BlockType adjacentBlock, int lx, int ly, int lz, int face, int chunkX, int chunkZ) {
        if (blockType == BlockType.WATER) {
            // For water blocks, use improved face culling logic
            if (adjacentBlock == BlockType.WATER) {
                // Get world coordinates for both blocks
                int worldX = lx + chunkX * WorldConfiguration.CHUNK_SIZE;
                int worldZ = lz + chunkZ * WorldConfiguration.CHUNK_SIZE;

                int adjWorldX = worldX;
                int adjWorldY = ly;
                int adjWorldZ = worldZ;

                switch (face) {
                    case 0 -> adjWorldY += 1; // Top
                    case 1 -> adjWorldY -= 1; // Bottom
                    case 2 -> adjWorldZ += 1; // Front
                    case 3 -> adjWorldZ -= 1; // Back
                    case 4 -> adjWorldX += 1; // Right
                    case 5 -> adjWorldX -= 1; // Left
                }

                // Get water blocks from the new water system
                WaterBlock currentWater = Water.getWaterBlock(worldX, ly, worldZ);
                WaterBlock adjacentWater = Water.getWaterBlock(adjWorldX, adjWorldY, adjWorldZ);

                if (currentWater != null && adjacentWater != null) {
                    // For horizontal faces (sides), cull if depths are the same
                    if (face >= 2 && face <= 5) { // Side faces
                        // Cull side faces between water blocks with same or similar depths
                        int currentDepth = currentWater.getDepth();
                        int adjacentDepth = adjacentWater.getDepth();

                        // Cull if depths are the same (creates seamless water surface)
                        if (currentDepth == adjacentDepth) {
                            return false; // Don't render this face
                        }

                        // Also cull if depth difference is minimal for smooth transitions
                        if (Math.abs(currentDepth - adjacentDepth) <= 1) {
                            return false; // Don't render this face
                        }
                    }

                    // For top faces, only render if there's a significant height difference
                    if (face == 0) { // Top face
                        float currentHeight = currentWater.getVisualHeight();
                        float adjacentHeight = adjacentWater.getVisualHeight();

                        // Only render top face if height difference is significant
                        return Math.abs(currentHeight - adjacentHeight) > 0.0625f; // 1/16 block threshold
                    }

                    // For bottom faces, always cull between water blocks
                    if (face == 1) { // Bottom face
                        return false; // Never render bottom faces between water blocks
                    }
                }

                // Fallback: don't render faces between water blocks
                return false;
            } else {
                // Water vs non-water: render if adjacent is transparent or air
                return adjacentBlock.isTransparent();
            }
        } else {
            // For non-water blocks, use the original logic
            return (adjacentBlock.isTransparent() && (blockType != BlockType.WATER || adjacentBlock != BlockType.WATER)) ||
                   (blockType.isTransparent() && !adjacentBlock.isTransparent());
        }
    }

    /**
     * Gets the block adjacent to the specified position in the given direction.
     */
    public BlockType getAdjacentBlock(int x, int y, int z, int face, BlockType[][][] blocks, int chunkX, int chunkZ, World world) {
        // Determine adjacent block coordinates based on the face
        // 0: Top, 1: Bottom, 2: Front, 3: Back, 4: Right, 5: Left
        try {
            return switch (face) {
                case 0 -> // Top
                    y + 1 < WorldConfiguration.WORLD_HEIGHT ? blocks[x][y + 1][z] : BlockType.AIR;
                case 1 -> // Bottom
                    y - 1 >= 0 ? blocks[x][y - 1][z] : BlockType.AIR;
                case 2 -> { // Front
                    if (z + 1 < WorldConfiguration.CHUNK_SIZE) {
                        yield blocks[x][y][z + 1];
                    } else {
                        // Get block from neighboring chunk
                        int worldX = x + chunkX * WorldConfiguration.CHUNK_SIZE;
                        int worldZ = z + chunkZ * WorldConfiguration.CHUNK_SIZE + 1;
                        yield world.getBlockAt(worldX, y, worldZ);
                    }
                }
                case 3 -> { // Back
                    if (z - 1 >= 0) {
                        yield blocks[x][y][z - 1];
                    } else {
                        // Get block from neighboring chunk
                        int worldX = x + chunkX * WorldConfiguration.CHUNK_SIZE;
                        int worldZ = z + chunkZ * WorldConfiguration.CHUNK_SIZE - 1;
                        yield world.getBlockAt(worldX, y, worldZ);
                    }
                }
                case 4 -> { // Right
                    if (x + 1 < WorldConfiguration.CHUNK_SIZE) {
                        yield blocks[x + 1][y][z];
                    } else {
                        // Get block from neighboring chunk
                        int worldX = x + chunkX * WorldConfiguration.CHUNK_SIZE + 1;
                        int worldZ = z + chunkZ * WorldConfiguration.CHUNK_SIZE;
                        yield world.getBlockAt(worldX, y, worldZ);
                    }
                }
                case 5 -> { // Left
                    if (x - 1 >= 0) {
                        yield blocks[x - 1][y][z];
                    } else {
                        // Get block from neighboring chunk
                        int worldX = x + chunkX * WorldConfiguration.CHUNK_SIZE - 1;
                        int worldZ = z + chunkZ * WorldConfiguration.CHUNK_SIZE;
                        yield world.getBlockAt(worldX, y, worldZ);
                    }
                }
                default -> BlockType.AIR;
            };
        } catch (Exception e) {
            // If there's any issue getting the adjacent block, assume it's air
            // This prevents crashes when dealing with chunk borders
            return BlockType.AIR;
        }
    }
}