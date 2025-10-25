package com.stonebreak.world.chunk.api.commonChunkOperations.coordinates;

import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Boundary validation utilities for CCO API.
 * All methods are static, thread-safe, and allocation-free.
 *
 * Performance: < 20ns per check (simple comparisons).
 */
public final class CcoBounds {

    private CcoBounds() {
        // Utility class
    }

    /**
     * Checks if local coordinates are within standard chunk bounds.
     *
     * @param localX Local X coordinate
     * @param localY Local Y coordinate
     * @param localZ Local Z coordinate
     * @return true if within bounds
     */
    public static boolean isInBounds(int localX, int localY, int localZ) {
        return isInBounds(localX, localY, localZ,
                WorldConfiguration.CHUNK_SIZE,
                WorldConfiguration.WORLD_HEIGHT,
                WorldConfiguration.CHUNK_SIZE);
    }

    /**
     * Checks if coordinates are within custom bounds.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @param sizeX X dimension
     * @param sizeY Y dimension
     * @param sizeZ Z dimension
     * @return true if within bounds
     */
    public static boolean isInBounds(int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
        return x >= 0 && x < sizeX &&
               y >= 0 && y < sizeY &&
               z >= 0 && z < sizeZ;
    }

    /**
     * Checks if local X is within chunk bounds.
     *
     * @param localX Local X coordinate
     * @return true if 0 <= localX < CHUNK_SIZE
     */
    public static boolean isXInBounds(int localX) {
        return localX >= 0 && localX < WorldConfiguration.CHUNK_SIZE;
    }

    /**
     * Checks if local Y is within world height.
     *
     * @param localY Local Y coordinate
     * @return true if 0 <= localY < WORLD_HEIGHT
     */
    public static boolean isYInBounds(int localY) {
        return localY >= 0 && localY < WorldConfiguration.WORLD_HEIGHT;
    }

    /**
     * Checks if local Z is within chunk bounds.
     *
     * @param localZ Local Z coordinate
     * @return true if 0 <= localZ < CHUNK_SIZE
     */
    public static boolean isZInBounds(int localZ) {
        return localZ >= 0 && localZ < WorldConfiguration.CHUNK_SIZE;
    }

    /**
     * Checks if local coordinate is on chunk boundary.
     * Useful for determining if neighbor chunks need updates.
     *
     * @param localX Local X coordinate
     * @param localZ Local Z coordinate
     * @return true if on edge
     */
    public static boolean isOnChunkEdge(int localX, int localZ) {
        return localX == 0 || localX == WorldConfiguration.CHUNK_SIZE - 1 ||
               localZ == 0 || localZ == WorldConfiguration.CHUNK_SIZE - 1;
    }

    /**
     * Checks if local coordinate is on minimum X boundary.
     *
     * @param localX Local X coordinate
     * @return true if localX == 0
     */
    public static boolean isOnMinX(int localX) {
        return localX == 0;
    }

    /**
     * Checks if local coordinate is on maximum X boundary.
     *
     * @param localX Local X coordinate
     * @return true if localX == CHUNK_SIZE - 1
     */
    public static boolean isOnMaxX(int localX) {
        return localX == WorldConfiguration.CHUNK_SIZE - 1;
    }

    /**
     * Checks if local coordinate is on minimum Z boundary.
     *
     * @param localZ Local Z coordinate
     * @return true if localZ == 0
     */
    public static boolean isOnMinZ(int localZ) {
        return localZ == 0;
    }

    /**
     * Checks if local coordinate is on maximum Z boundary.
     *
     * @param localZ Local Z coordinate
     * @return true if localZ == CHUNK_SIZE - 1
     */
    public static boolean isOnMaxZ(int localZ) {
        return localZ == WorldConfiguration.CHUNK_SIZE - 1;
    }

    /**
     * Clamps local X to valid chunk range.
     *
     * @param localX Local X coordinate
     * @return Clamped value (0 to CHUNK_SIZE-1)
     */
    public static int clampX(int localX) {
        return Math.max(0, Math.min(localX, WorldConfiguration.CHUNK_SIZE - 1));
    }

    /**
     * Clamps local Y to valid world height range.
     *
     * @param localY Local Y coordinate
     * @return Clamped value (0 to WORLD_HEIGHT-1)
     */
    public static int clampY(int localY) {
        return Math.max(0, Math.min(localY, WorldConfiguration.WORLD_HEIGHT - 1));
    }

    /**
     * Clamps local Z to valid chunk range.
     *
     * @param localZ Local Z coordinate
     * @return Clamped value (0 to CHUNK_SIZE-1)
     */
    public static int clampZ(int localZ) {
        return Math.max(0, Math.min(localZ, WorldConfiguration.CHUNK_SIZE - 1));
    }
}
