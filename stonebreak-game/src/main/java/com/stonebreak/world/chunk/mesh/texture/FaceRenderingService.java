package com.stonebreak.world.chunk.mesh.texture;

import com.stonebreak.blocks.BlockType;
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
            if (adjacentBlock == BlockType.WATER) {
                // Never render faces between water blocks - they should blend seamlessly
                return false;
            } else {
                // Water vs non-water: render top face when adjacent to opaque blocks, other faces when transparent (but not water)
                if (face == 0) { // Top face
                    return !adjacentBlock.isTransparent() || adjacentBlock == BlockType.AIR;
                } else {
                    return adjacentBlock.isTransparent() && adjacentBlock != BlockType.WATER;
                }
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
