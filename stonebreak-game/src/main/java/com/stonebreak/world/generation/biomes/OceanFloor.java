package com.stonebreak.world.generation.biomes;

import com.stonebreak.blocks.BlockType;

/**
 * The {@link BiomeType#OCEAN} sea floor: 97% sand, 2% dirt, 1% clay,
 * picked per column from a hash of the world seed and the column, so every column always gets
 * the same block whatever order chunks generate in.
 */
public final class OceanFloor {

    private OceanFloor() {}

    /** The sea-floor block for one column. */
    public static BlockType block(int worldX, int worldZ, long seed) {
        long h = seed ^ 0x6F63_6561_6E66_6CL;            // "oceanfl"
        h = (h ^ (worldX * 0x9E37_79B9_7F4A_7C15L)) * 0xBF58_476D_1CE4_E5B9L;
        h = (h ^ (worldZ * 0xC2B2_AE3D_27D4_EB4FL)) * 0x94D0_49BB_1331_11EBL;
        h ^= h >>> 31;
        int r = (int) Long.remainderUnsigned(h, 100);
        return r < 97 ? BlockType.SAND : (r < 99 ? BlockType.DIRT : BlockType.CLAY);
    }
}
