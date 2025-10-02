package com.stonebreak.world.chunk.api.commonChunkOperations.coordinates;

import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Pure functions for coordinate conversions in the CCO API.
 * All methods are static, thread-safe, and allocation-free.
 *
 * Performance: < 50ns per conversion (pure arithmetic).
 */
public final class CcoCoordinates {

    private CcoCoordinates() {
        // Utility class
    }

    /**
     * Converts local X coordinate to world X coordinate.
     *
     * @param chunkX Chunk X position
     * @param localX Local X within chunk (0-15)
     * @return World X coordinate
     */
    public static int localToWorldX(int chunkX, int localX) {
        return chunkX * WorldConfiguration.CHUNK_SIZE + localX;
    }

    /**
     * Converts local Z coordinate to world Z coordinate.
     *
     * @param chunkZ Chunk Z position
     * @param localZ Local Z within chunk (0-15)
     * @return World Z coordinate
     */
    public static int localToWorldZ(int chunkZ, int localZ) {
        return chunkZ * WorldConfiguration.CHUNK_SIZE + localZ;
    }

    /**
     * Converts world X coordinate to chunk X coordinate.
     * Handles negative coordinates correctly.
     *
     * @param worldX World X coordinate
     * @return Chunk X coordinate
     */
    public static int worldToChunkX(int worldX) {
        return worldX >= 0
                ? worldX / WorldConfiguration.CHUNK_SIZE
                : (worldX - WorldConfiguration.CHUNK_SIZE + 1) / WorldConfiguration.CHUNK_SIZE;
    }

    /**
     * Converts world Z coordinate to chunk Z coordinate.
     * Handles negative coordinates correctly.
     *
     * @param worldZ World Z coordinate
     * @return Chunk Z coordinate
     */
    public static int worldToChunkZ(int worldZ) {
        return worldZ >= 0
                ? worldZ / WorldConfiguration.CHUNK_SIZE
                : (worldZ - WorldConfiguration.CHUNK_SIZE + 1) / WorldConfiguration.CHUNK_SIZE;
    }

    /**
     * Converts world X coordinate to local X within chunk.
     *
     * @param worldX World X coordinate
     * @return Local X (0-15)
     */
    public static int worldToLocalX(int worldX) {
        int local = worldX % WorldConfiguration.CHUNK_SIZE;
        return local < 0 ? local + WorldConfiguration.CHUNK_SIZE : local;
    }

    /**
     * Converts world Z coordinate to local Z within chunk.
     *
     * @param worldZ World Z coordinate
     * @return Local Z (0-15)
     */
    public static int worldToLocalZ(int worldZ) {
        int local = worldZ % WorldConfiguration.CHUNK_SIZE;
        return local < 0 ? local + WorldConfiguration.CHUNK_SIZE : local;
    }

    /**
     * Converts 3D local coordinates to 1D array index.
     * Standard indexing: [x][y][z] → x * HEIGHT * SIZE + y * SIZE + z
     *
     * @param localX Local X (0-15)
     * @param localY Local Y (0-255)
     * @param localZ Local Z (0-15)
     * @return 1D array index
     */
    public static int toIndex(int localX, int localY, int localZ) {
        return localX * WorldConfiguration.WORLD_HEIGHT * WorldConfiguration.CHUNK_SIZE +
               localY * WorldConfiguration.CHUNK_SIZE +
               localZ;
    }

    /**
     * Converts 3D local coordinates to 1D array index with custom dimensions.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @param sizeY Height dimension
     * @param sizeZ Depth dimension
     * @return 1D array index
     */
    public static int toIndex(int x, int y, int z, int sizeY, int sizeZ) {
        return x * sizeY * sizeZ + y * sizeZ + z;
    }

    /**
     * Extracts X coordinate from 1D array index.
     *
     * @param index 1D array index
     * @return Local X coordinate
     */
    public static int indexToX(int index) {
        return index / (WorldConfiguration.WORLD_HEIGHT * WorldConfiguration.CHUNK_SIZE);
    }

    /**
     * Extracts Y coordinate from 1D array index.
     *
     * @param index 1D array index
     * @return Local Y coordinate
     */
    public static int indexToY(int index) {
        int remainder = index % (WorldConfiguration.WORLD_HEIGHT * WorldConfiguration.CHUNK_SIZE);
        return remainder / WorldConfiguration.CHUNK_SIZE;
    }

    /**
     * Extracts Z coordinate from 1D array index.
     *
     * @param index 1D array index
     * @return Local Z coordinate
     */
    public static int indexToZ(int index) {
        return index % WorldConfiguration.CHUNK_SIZE;
    }

    /**
     * Calculates Manhattan distance between two chunk positions.
     *
     * @param chunkX1 First chunk X
     * @param chunkZ1 First chunk Z
     * @param chunkX2 Second chunk X
     * @param chunkZ2 Second chunk Z
     * @return Manhattan distance
     */
    public static int manhattanDistance(int chunkX1, int chunkZ1, int chunkX2, int chunkZ2) {
        return Math.abs(chunkX2 - chunkX1) + Math.abs(chunkZ2 - chunkZ1);
    }

    /**
     * Calculates squared Euclidean distance between two chunk positions.
     * Avoids sqrt for performance.
     *
     * @param chunkX1 First chunk X
     * @param chunkZ1 First chunk Z
     * @param chunkX2 Second chunk X
     * @param chunkZ2 Second chunk Z
     * @return Squared Euclidean distance
     */
    public static int distanceSquared(int chunkX1, int chunkZ1, int chunkX2, int chunkZ2) {
        int dx = chunkX2 - chunkX1;
        int dz = chunkZ2 - chunkZ1;
        return dx * dx + dz * dz;
    }
}
