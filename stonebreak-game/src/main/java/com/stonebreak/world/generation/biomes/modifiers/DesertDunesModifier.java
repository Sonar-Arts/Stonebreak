package com.stonebreak.world.generation.biomes.modifiers;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;

/**
 * Terrain modifier for Desert biomes creating rolling dune patterns.
 *
 * Features:
 * - Gentle rolling dunes: Smooth undulations typical of sandy deserts
 * - Wavelength variation: Creates natural-looking dune patterns
 * - Erosion-dependent: Only applies in flatter desert areas
 *
 * The terrain hint system already handles overall flatness.
 * This modifier adds fine details:
 * - Characteristic dune wavelength (60 blocks)
 * - Smooth curves for natural sand appearance
 * - Keeps within ±12 blocks for gentle desert feel
 *
 * Thread-safe (noise generators are thread-safe).
 */
public class DesertDunesModifier implements BiomeTerrainModifier {

    private final NoiseGenerator duneNoise;

    /**
     * Creates a new Desert dunes modifier with the given seed.
     *
     * @param seed World seed for deterministic generation
     */
    public DesertDunesModifier(long seed) {
        // Dune noise: Medium-large scale for gentle rolling
        this.duneNoise = new NoiseGenerator(seed + 5000, NoiseConfigFactory.erosion());
    }

    @Override
    public int modifyHeight(int baseHeight, MultiNoiseParameters params, int x, int z) {
        // Gentle rolling dunes with characteristic wavelength
        float duneValue = (float) duneNoise.noise(x / 60.0f, z / 60.0f);
        int duneHeight = Math.round(duneValue * 12);  // ±12 blocks for gentle dunes

        return baseHeight + duneHeight;
    }

    @Override
    public boolean shouldApplyModifier(MultiNoiseParameters params, int baseHeight) {
        // Only apply in flatter desert areas (high erosion)
        return params.erosion > 0.3f;
    }

    @Override
    public BiomeType getBiomeType() {
        return BiomeType.DESERT;
    }
}
