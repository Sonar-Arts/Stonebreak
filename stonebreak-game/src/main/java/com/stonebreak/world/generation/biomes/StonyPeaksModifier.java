package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;

/**
 * Terrain modifier for Stony Peaks biome creating jagged mountain features.
 *
 * Features:
 * - Vertical spire amplification: Makes peaks VERY tall
 * - Rocky outcrop variation: Adds jagged surfaces
 * - Height-dependent: Only affects high altitude areas
 *
 * The terrain hint system already handles amplified erosion and PV.
 * This modifier adds fine details:
 * - Extreme vertical amplification for dramatic spires
 * - Small-scale variation for jagged rocky surfaces
 * - Keeps within ±20 block constraint for consistency
 *
 * Thread-safe (noise generators are thread-safe).
 */
public class StonyPeaksModifier implements BiomeTerrainModifier {

    private final NoiseGenerator spireNoise;
    private final NoiseGenerator outcropNoise;
    private final int seaLevel;

    /**
     * Creates a new Stony Peaks modifier with the given seed.
     *
     * @param seed World seed for deterministic generation
     */
    public StonyPeaksModifier(long seed) {
        // Spire noise: Medium scale for individual peak amplification
        this.spireNoise = new NoiseGenerator(seed + 3000, NoiseConfigFactory.peaksValleys());

        // Outcrop noise: Very small scale for surface jaggedness
        this.outcropNoise = new NoiseGenerator(seed + 4000, NoiseConfigFactory.detail());

        this.seaLevel = 64;
    }

    @Override
    public int modifyHeight(int baseHeight, MultiNoiseParameters params, int x, int z) {
        int modifiedHeight = baseHeight;

        // 1. Vertical spire amplification (only for high areas)
        if (baseHeight > 100) {  // Only affect mountains
            float spireValue = (float) spireNoise.noise(x / 30.0f, z / 30.0f);
            if (spireValue > 0.4f) {
                // Make peaks dramatically taller
                int amplification = Math.round((spireValue - 0.4f) * 35);  // Up to +20 blocks
                modifiedHeight += amplification;
            }
        }

        // 2. Rocky outcrop variation (for all peaks)
        if (baseHeight > seaLevel + 20) {
            float outcropValue = (float) outcropNoise.noise(x / 12.0f, z / 12.0f);
            modifiedHeight += Math.round(outcropValue * 8);  // ±8 block variation for jaggedness
        }

        return modifiedHeight;
    }

    @Override
    public boolean shouldApplyModifier(MultiNoiseParameters params, int baseHeight) {
        // Only apply in peak areas (high PV parameter)
        return params.peaksValleys > 0.2f;
    }

    @Override
    public BiomeType getBiomeType() {
        return BiomeType.STONY_PEAKS;
    }
}
