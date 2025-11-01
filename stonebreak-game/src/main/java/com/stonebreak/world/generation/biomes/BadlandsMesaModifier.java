package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;

/**
 * Terrain modifier for Badlands biome creating mesa-specific features.
 *
 * Features:
 * - Canyon carving: Deep carved valleys between mesas
 * - Hoodoo/spire generation: Tall thin rock formations
 * - Variation: Creates visual interest and dramatic terrain
 *
 * The terrain hint system already handles large terracing (16-24 blocks).
 * This modifier adds fine details:
 * - Carves canyons through noise thresholds
 * - Generates rare tall hoodoos/spires
 * - Keeps within ±20 block constraint for consistency
 *
 * Thread-safe (noise generators are thread-safe).
 */
public class BadlandsMesaModifier implements BiomeTerrainModifier {

    private final NoiseGenerator canyonNoise;
    private final NoiseGenerator hoodooNoise;

    /**
     * Creates a new Badlands mesa modifier with the given seed.
     *
     * @param seed World seed for deterministic generation
     */
    public BadlandsMesaModifier(long seed) {
        // Canyon noise: Large scale for wide valleys
        this.canyonNoise = new NoiseGenerator(seed + 1000, NoiseConfigFactory.erosion());

        // Hoodoo noise: Small scale for individual spires
        this.hoodooNoise = new NoiseGenerator(seed + 2000, NoiseConfigFactory.detail());
    }

    @Override
    public int modifyHeight(int baseHeight, MultiNoiseParameters params, int x, int z) {
        int modifiedHeight = baseHeight;

        // 1. Canyon carving (30% of area)
        float canyonValue = (float) canyonNoise.noise(x / 120.0f, z / 120.0f);
        if (canyonValue < -0.3f) {
            // Carve canyon: deeper carving in lower threshold areas
            int carveDepth = Math.round((canyonValue + 0.3f) * 40);  // Up to -20 blocks
            modifiedHeight += carveDepth;
        }

        // 2. Hoodoo/spire generation (rare - 10% of area)
        float hoodooValue = (float) hoodooNoise.noise(x / 25.0f, z / 25.0f);
        if (hoodooValue > 0.7f) {
            // Generate tall thin formation
            int spireHeight = Math.round((hoodooValue - 0.7f) * 50);  // Up to +15 blocks
            modifiedHeight += spireHeight;
        }

        return modifiedHeight;
    }

    @Override
    public boolean shouldApplyModifier(MultiNoiseParameters params, int baseHeight) {
        // Always apply for badlands (we want consistent canyon/hoodoo features)
        return true;
    }

    @Override
    public BiomeType getBiomeType() {
        return BiomeType.BADLANDS;
    }
}
