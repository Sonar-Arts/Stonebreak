package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Reads Standard Generation's output back in the {@link StandardTerrain} frame, for the cave
 * tests: their surfaces, ratchets and depth bands were all measured in the 256-tall column,
 * and {@link TerrainGenerationSystem} now hands out chunks and profiles lifted into world Y.
 */
final class StandardFrame {

    private StandardFrame() {}

    /** The block at Standard-frame {@code y}. */
    static BlockType block(Chunk chunk, int x, int y, int z) {
        return chunk.getBlock(x, y + StandardTerrain.Y_OFFSET, z);
    }

    /** A lifted column profile as the Standard-frame one generation started from. */
    static ColumnProfile lower(ColumnProfile profile) {
        int[] heights = profile.heights().clone();
        int[] waterLevels = profile.waterLevels().clone();
        for (int i = 0; i < heights.length; i++) {
            heights[i] -= StandardTerrain.Y_OFFSET;
            if (waterLevels[i] != WorldConfiguration.NO_WATER) {
                waterLevels[i] -= StandardTerrain.Y_OFFSET;
            }
        }
        return new ColumnProfile(heights, profile.biomes(), waterLevels);
    }
}
