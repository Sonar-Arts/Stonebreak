package com.stonebreak.world.chunk.operations;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Immutable data container for chunk block data.
 * Represents the 3D block array and metadata for a chunk.
 */
public class ChunkData {
    
    private final int chunkX;
    private final int chunkZ;
    private final BlockType[][][] blocks;
    
    /**
     * Creates chunk data for the specified chunk position.
     * @param chunkX The chunk's X coordinate
     * @param chunkZ The chunk's Z coordinate
     */
    public ChunkData(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.blocks = new BlockType[WorldConfiguration.CHUNK_SIZE][WorldConfiguration.WORLD_HEIGHT][WorldConfiguration.CHUNK_SIZE];
        
        // Initialize all blocks to air
        for (int x = 0; x < WorldConfiguration.CHUNK_SIZE; x++) {
            for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
                for (int z = 0; z < WorldConfiguration.CHUNK_SIZE; z++) {
                    blocks[x][y][z] = BlockType.AIR;
                }
            }
        }
    }
    
    /**
     * Gets the chunk X coordinate.
     * @return The chunk X coordinate
     */
    public int getChunkX() {
        return chunkX;
    }
    
    /**
     * Gets the chunk Z coordinate.
     * @return The chunk Z coordinate
     */
    public int getChunkZ() {
        return chunkZ;
    }
    
    /**
     * Gets the block array. Should not be modified directly - use ChunkDataOperations instead.
     * @return The block array
     */
    public BlockType[][][] getBlocks() {
        return blocks;
    }
    
    /**
     * Gets a block at the specified local coordinates.
     * @param localX Local X coordinate (0-15)
     * @param localY Local Y coordinate (0-255)
     * @param localZ Local Z coordinate (0-15)
     * @return The block type, or AIR if coordinates are out of bounds
     */
    public BlockType getBlock(int localX, int localY, int localZ) {
        if (!ChunkCoordinateUtils.isValidLocalCoordinate(localX, localY, localZ)) {
            return BlockType.AIR;
        }
        return blocks[localX][localY][localZ];
    }
    
    /**
     * Sets a block at the specified local coordinates.
     * This is package-private as it should only be called by ChunkDataOperations.
     * @param localX Local X coordinate (0-15)
     * @param localY Local Y coordinate (0-255)
     * @param localZ Local Z coordinate (0-15)
     * @param blockType The block type to set
     * @return true if the block was changed, false if coordinates are invalid or block is the same
     */
    boolean setBlock(int localX, int localY, int localZ, BlockType blockType) {
        if (!ChunkCoordinateUtils.isValidLocalCoordinate(localX, localY, localZ)) {
            return false;
        }
        
        BlockType currentBlock = blocks[localX][localY][localZ];
        if (currentBlock == blockType) {
            return false; // No change
        }
        
        blocks[localX][localY][localZ] = blockType;
        return true;
    }
}