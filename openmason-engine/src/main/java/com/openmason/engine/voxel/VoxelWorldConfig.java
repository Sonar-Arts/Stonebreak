package com.openmason.engine.voxel;

/**
 * Immutable configuration for a voxel world.
 *
 * <p>Holds dimension constants that engine systems (MMS, CCO) need
 * for iteration bounds, coordinate conversion, and buffer sizing.
 *
 * @param chunkSize  blocks per chunk in X and Z (e.g., 16)
 * @param worldHeight  total blocks in Y (e.g., 256)
 * @param seaLevel  Y-level of the water surface
 */
public record VoxelWorldConfig(int chunkSize, int worldHeight, int seaLevel) {

    public VoxelWorldConfig {
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be positive");
        if (worldHeight <= 0) throw new IllegalArgumentException("worldHeight must be positive");
    }
}
