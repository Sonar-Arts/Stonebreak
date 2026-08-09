package com.stonebreak.blocks.waterSystem;

import com.stonebreak.world.World;
import com.stonebreak.world.chunk.ChunkWaterLayer;

/**
 * Shared water level-to-height conversion, used by both mesh generation
 * ({@code MmsWaterGenerator}) and player physics (submersion depth).
 */
public final class WaterHeightUtil {

    public static final float MIN_DISPLAYED_WATER_HEIGHT = 0.0625f; // 1/16th block - allows level 7 to render properly
    public static final float MAX_WATER_HEIGHT = 0.875f; // 7/8 block height for source blocks

    private WaterHeightUtil() {
        // Utility class
    }

    /**
     * Resolves the water surface height fraction (within its block, 0..1) at a specific
     * position: sources and falling columns are full height, flowing water steps down
     * with its level. NaN when the position is not water.
     */
    public static float resolveSurfaceHeightFraction(World world, int x, int y, int z) {
        int value = world.getWaterLevelAt(x, y, z);
        if (value < 0) {
            return Float.NaN;
        }
        float height = (value == ChunkWaterLayer.SOURCE || value == ChunkWaterLayer.FALLING)
            ? MAX_WATER_HEIGHT
            : (8 - value) * MAX_WATER_HEIGHT / 8.0f;
        return clampWaterHeight(height);
    }

    private static float clampWaterHeight(float height) {
        return Math.max(MIN_DISPLAYED_WATER_HEIGHT, Math.min(MAX_WATER_HEIGHT, height));
    }
}
