package com.openmason.engine.voxel.cco.coordinates;

import com.openmason.engine.voxel.VoxelWorldConfig;

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
        return chunkX * CcoBounds.getConfig().chunkSize() + localX;
    }

    /**
     * Converts local Z coordinate to world Z coordinate.
     *
     * @param chunkZ Chunk Z position
     * @param localZ Local Z within chunk (0-15)
     * @return World Z coordinate
     */
    public static int localToWorldZ(int chunkZ, int localZ) {
        return chunkZ * CcoBounds.getConfig().chunkSize() + localZ;
    }

    /**
     * Converts world X coordinate to chunk X coordinate.
     * Handles negative coordinates correctly.
     *
     * @param worldX World X coordinate
     * @return Chunk X coordinate
     */
    public static int worldToChunkX(int worldX) {
        int chunkSize = CcoBounds.getConfig().chunkSize();
        return worldX >= 0
                ? worldX / chunkSize
                : (worldX - chunkSize + 1) / chunkSize;
    }

    /**
     * Converts world Z coordinate to chunk Z coordinate.
     * Handles negative coordinates correctly.
     *
     * @param worldZ World Z coordinate
     * @return Chunk Z coordinate
     */
    public static int worldToChunkZ(int worldZ) {
        int chunkSize = CcoBounds.getConfig().chunkSize();
        return worldZ >= 0
                ? worldZ / chunkSize
                : (worldZ - chunkSize + 1) / chunkSize;
    }

    /**
     * Converts world X coordinate to local X within chunk.
     *
     * @param worldX World X coordinate
     * @return Local X (0 to chunkSize-1)
     */
    public static int worldToLocalX(int worldX) {
        int chunkSize = CcoBounds.getConfig().chunkSize();
        int local = worldX % chunkSize;
        return local < 0 ? local + chunkSize : local;
    }

    /**
     * Converts world Z coordinate to local Z within chunk.
     *
     * @param worldZ World Z coordinate
     * @return Local Z (0 to chunkSize-1)
     */
    public static int worldToLocalZ(int worldZ) {
        int chunkSize = CcoBounds.getConfig().chunkSize();
        int local = worldZ % chunkSize;
        return local < 0 ? local + chunkSize : local;
    }

    /**
     * Converts 3D local coordinates to 1D array index.
     * Standard indexing: [x][y][z] -> x * HEIGHT * SIZE + y * SIZE + z
     *
     * @param localX Local X (0 to chunkSize-1)
     * @param localY Local Y (0 to worldHeight-1)
     * @param localZ Local Z (0 to chunkSize-1)
     * @return 1D array index
     */
    public static int toIndex(int localX, int localY, int localZ) {
        VoxelWorldConfig cfg = CcoBounds.getConfig();
        return localX * cfg.worldHeight() * cfg.chunkSize() +
               localY * cfg.chunkSize() +
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
        VoxelWorldConfig cfg = CcoBounds.getConfig();
        return index / (cfg.worldHeight() * cfg.chunkSize());
    }

    /**
     * Extracts Y coordinate from 1D array index.
     *
     * @param index 1D array index
     * @return Local Y coordinate
     */
    public static int indexToY(int index) {
        VoxelWorldConfig cfg = CcoBounds.getConfig();
        int remainder = index % (cfg.worldHeight() * cfg.chunkSize());
        return remainder / cfg.chunkSize();
    }

    /**
     * Extracts Z coordinate from 1D array index.
     *
     * @param index 1D array index
     * @return Local Z coordinate
     */
    public static int indexToZ(int index) {
        return index % CcoBounds.getConfig().chunkSize();
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

    /**
     * Checks if the given local coordinates are valid within a chunk.
     *
     * @param localX Local X coordinate
     * @param localY Local Y coordinate
     * @param localZ Local Z coordinate
     * @return true if coordinates are within chunk bounds
     */
    public static boolean isValidLocalCoordinate(int localX, int localY, int localZ) {
        VoxelWorldConfig cfg = CcoBounds.getConfig();
        return localX >= 0 && localX < cfg.chunkSize() &&
               localY >= 0 && localY < cfg.worldHeight() &&
               localZ >= 0 && localZ < cfg.chunkSize();
    }

    /**
     * Converts 1D array index back to 3D local coordinates.
     *
     * @param index 1D array index
     * @return Array containing [localX, localY, localZ]
     */
    public static int[] indexToCoordinate(int index) {
        int localX = indexToX(index);
        int localY = indexToY(index);
        int localZ = indexToZ(index);
        return new int[]{localX, localY, localZ};
    }
}
