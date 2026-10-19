package com.stonebreak.world.generation.biomes;

import com.stonebreak.blocks.BlockType;

/**
 * The bed under inland water — lakes and river channels — for any biome that is not the sea
 * ({@link OceanFloor}). Shallow water (one block) lies on sand with some dirt; deeper water on
 * dirt with some sand and a little clay. Sandy and frozen or rocky biomes keep their own surface.
 * Picked per column from a hash of the world seed and the column, like {@link OceanFloor}.
 */
public final class LakeBed {

    private LakeBed() {}

    /**
     * @param biome the column's land biome (never {@link BiomeType#OCEAN})
     * @param depth blocks of water standing over the bed, at least 1
     * @param ownSurface the biome's dry surface block, kept where a lake bed would look wrong
     */
    public static BlockType block(BiomeType biome, int depth, int worldX, int worldZ, long seed,
                                  BlockType ownSurface) {
        switch (biome) {
            case DESERT, BEACH, RED_SAND_DESERT, BADLANDS, STONY_PEAKS, ICE_FIELDS, OCEAN -> {
                return ownSurface;
            }
            default -> { }
        }
        long h = seed ^ 0x6C_616B_6562_6564L;            // "lakebed"
        h = (h ^ (worldX * 0x9E37_79B9_7F4A_7C15L)) * 0xBF58_476D_1CE4_E5B9L;
        h = (h ^ (worldZ * 0xC2B2_AE3D_27D4_EB4FL)) * 0x94D0_49BB_1331_11EBL;
        h ^= h >>> 31;
        int r = (int) Long.remainderUnsigned(h, 100);
        if (depth <= 1) {
            return r < 75 ? BlockType.SAND : BlockType.DIRT;
        }
        return r < 70 ? BlockType.DIRT : (r < 95 ? BlockType.SAND : BlockType.CLAY);
    }
}
