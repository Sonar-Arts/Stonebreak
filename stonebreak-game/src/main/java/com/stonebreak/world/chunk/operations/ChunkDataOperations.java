package com.stonebreak.world.chunk.operations;

import com.stonebreak.blocks.BlockType;

/**
 * Operations for managing chunk block data with proper dirty marking and neighbor notifications.
 * Handles all block get/set operations with automatic state management.
 */
public class ChunkDataOperations {
    
    private final ChunkDataBuffer chunkData;
    private final ChunkInternalStateManager stateManager;
    
    /**
     * Creates chunk data operations for the given chunk data and state manager.
     * @param chunkData The chunk data to operate on
     * @param stateManager The state manager for dirty marking
     */
    public ChunkDataOperations(ChunkDataBuffer chunkData, ChunkInternalStateManager stateManager) {
        this.chunkData = chunkData;
        this.stateManager = stateManager;
    }
    
    /**
     * Gets a block at the specified local coordinates.
     * @param localX Local X coordinate (0-15)
     * @param localY Local Y coordinate (0-255)
     * @param localZ Local Z coordinate (0-15)
     * @return The block type, or AIR if coordinates are out of bounds
     */
    public BlockType getBlock(int localX, int localY, int localZ) {
        return chunkData.getBlock(localX, localY, localZ);
    }
    
    /**
     * Sets a block at the specified local coordinates with automatic dirty marking.
     * @param localX Local X coordinate (0-15)
     * @param localY Local Y coordinate (0-255)
     * @param localZ Local Z coordinate (0-15)
     * @param blockType The block type to set
     * @return true if the block was changed (and chunk marked dirty)
     */
    public boolean setBlock(int localX, int localY, int localZ, BlockType blockType) {
        boolean changed = chunkData.setBlock(localX, localY, localZ, blockType);
        
        if (changed) {
            // Mark chunk as dirty when block data changes
            stateManager.markMeshDirty();
            
            // TODO: Handle neighbor chunk notifications for edge blocks
            // This would be implemented when we have access to the World/chunk manager
            handleNeighborUpdates(localX, localY, localZ);
        }
        
        return changed;
    }
    
    /**
     * Fills a region with the specified block type.
     * @param startX Start local X coordinate
     * @param startY Start local Y coordinate  
     * @param startZ Start local Z coordinate
     * @param endX End local X coordinate (inclusive)
     * @param endY End local Y coordinate (inclusive)
     * @param endZ End local Z coordinate (inclusive)
     * @param blockType The block type to fill with
     * @return Number of blocks changed
     */
    public int fillRegion(int startX, int startY, int startZ, int endX, int endY, int endZ, BlockType blockType) {
        int changedCount = 0;
        boolean anyChanged = false;
        
        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    if (chunkData.setBlock(x, y, z, blockType)) {
                        changedCount++;
                        anyChanged = true;
                    }
                }
            }
        }
        
        if (anyChanged) {
            stateManager.markMeshDirty();
        }
        
        return changedCount;
    }
    
    /**
     * Replaces all blocks of one type with another type.
     * @param fromType The block type to replace
     * @param toType The block type to replace with
     * @return Number of blocks changed
     */
    public int replaceBlocks(BlockType fromType, BlockType toType) {
        int changedCount = 0;
        boolean anyChanged = false;
        
        BlockType[][][] blocks = chunkData.getBlocks();
        for (int x = 0; x < blocks.length; x++) {
            for (int y = 0; y < blocks[x].length; y++) {
                for (int z = 0; z < blocks[x][y].length; z++) {
                    if (blocks[x][y][z] == fromType) {
                        if (chunkData.setBlock(x, y, z, toType)) {
                            changedCount++;
                            anyChanged = true;
                        }
                    }
                }
            }
        }
        
        if (anyChanged) {
            stateManager.markMeshDirty();
        }
        
        return changedCount;
    }
    
    /**
     * Gets the chunk data being operated on.
     * @return The chunk data
     */
    public ChunkDataBuffer getChunkData() {
        return chunkData;
    }
    
    /**
     * Handles neighbor chunk updates when blocks are changed on chunk boundaries.
     * This is a placeholder for future implementation when we have access to neighboring chunks.
     * @param localX The local X coordinate of the changed block
     * @param localY The local Y coordinate of the changed block
     * @param localZ The local Z coordinate of the changed block
     */
    private void handleNeighborUpdates(int localX, int localY, int localZ) {
        // TODO: Implement neighbor chunk dirty marking when block is on edge
        // This would require access to the World or chunk manager to:
        // 1. Check if the block is on a chunk boundary (x=0, x=15, z=0, z=15)
        // 2. Mark neighboring chunks as dirty if they exist
        // 3. Handle lighting updates across chunk boundaries
        
        // For now, this is left as a placeholder for future implementation
    }
}