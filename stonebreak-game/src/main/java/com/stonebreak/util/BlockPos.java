package com.stonebreak.util;

/**
 * Immutable block position in 3D space.
 * Used for tracking block coordinates in world space.
 */
public record BlockPos(int x, int y, int z) {
    /**
     * Creates a new BlockPos offset from this position.
     */
    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }
}
