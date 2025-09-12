package com.stonebreak.world.chunk.operations;

import com.stonebreak.world.operations.WorldConfiguration;

public final class ChunkCoordinateUtils {
    
    private ChunkCoordinateUtils() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Converts a local X coordinate to a world X coordinate.
     * @param chunkX The chunk's X position
     * @param localX The local X coordinate within the chunk (0-15)
     * @return The world X coordinate
     */
    public static int localToWorldX(int chunkX, int localX) {
        return chunkX * WorldConfiguration.CHUNK_SIZE + localX;
    }
    
    /**
     * Converts a local Z coordinate to a world Z coordinate.
     * @param chunkZ The chunk's Z position
     * @param localZ The local Z coordinate within the chunk (0-15)
     * @return The world Z coordinate
     */
    public static int localToWorldZ(int chunkZ, int localZ) {
        return chunkZ * WorldConfiguration.CHUNK_SIZE + localZ;
    }
    
    /**
     * Converts a world X coordinate to chunk X coordinate.
     * @param worldX The world X coordinate
     * @return The chunk X coordinate
     */
    public static int worldToChunkX(int worldX) {
        return worldX >= 0 ? worldX / WorldConfiguration.CHUNK_SIZE : (worldX - WorldConfiguration.CHUNK_SIZE + 1) / WorldConfiguration.CHUNK_SIZE;
    }
    
    /**
     * Converts a world Z coordinate to chunk Z coordinate.
     * @param worldZ The world Z coordinate
     * @return The chunk Z coordinate
     */
    public static int worldToChunkZ(int worldZ) {
        return worldZ >= 0 ? worldZ / WorldConfiguration.CHUNK_SIZE : (worldZ - WorldConfiguration.CHUNK_SIZE + 1) / WorldConfiguration.CHUNK_SIZE;
    }
    
    /**
     * Converts a world X coordinate to local X coordinate within its chunk.
     * @param worldX The world X coordinate
     * @return The local X coordinate (0-15)
     */
    public static int worldToLocalX(int worldX) {
        int localX = worldX % WorldConfiguration.CHUNK_SIZE;
        return localX < 0 ? localX + WorldConfiguration.CHUNK_SIZE : localX;
    }
    
    /**
     * Converts a world Z coordinate to local Z coordinate within its chunk.
     * @param worldZ The world Z coordinate
     * @return The local Z coordinate (0-15)
     */
    public static int worldToLocalZ(int worldZ) {
        int localZ = worldZ % WorldConfiguration.CHUNK_SIZE;
        return localZ < 0 ? localZ + WorldConfiguration.CHUNK_SIZE : localZ;
    }
    
    /**
     * Checks if the given local coordinates are valid within a chunk.
     * @param localX The local X coordinate
     * @param localY The local Y coordinate
     * @param localZ The local Z coordinate
     * @return true if coordinates are within chunk bounds
     */
    public static boolean isValidLocalCoordinate(int localX, int localY, int localZ) {
        return localX >= 0 && localX < WorldConfiguration.CHUNK_SIZE &&
               localY >= 0 && localY < WorldConfiguration.WORLD_HEIGHT &&
               localZ >= 0 && localZ < WorldConfiguration.CHUNK_SIZE;
    }
    
    /**
     * Converts 3D local coordinates to a 1D array index.
     * @param localX The local X coordinate (0-15)
     * @param localY The local Y coordinate (0-255)
     * @param localZ The local Z coordinate (0-15)
     * @return The 1D array index
     */
    public static int coordinateToIndex(int localX, int localY, int localZ) {
        return localX * WorldConfiguration.WORLD_HEIGHT * WorldConfiguration.CHUNK_SIZE + 
               localY * WorldConfiguration.CHUNK_SIZE + 
               localZ;
    }
    
    /**
     * Converts a 1D array index back to 3D local coordinates.
     * @param index The 1D array index
     * @return Array containing [localX, localY, localZ]
     */
    public static int[] indexToCoordinate(int index) {
        int localX = index / (WorldConfiguration.WORLD_HEIGHT * WorldConfiguration.CHUNK_SIZE);
        int remainder = index % (WorldConfiguration.WORLD_HEIGHT * WorldConfiguration.CHUNK_SIZE);
        int localY = remainder / WorldConfiguration.CHUNK_SIZE;
        int localZ = remainder % WorldConfiguration.CHUNK_SIZE;
        return new int[]{localX, localY, localZ};
    }
}