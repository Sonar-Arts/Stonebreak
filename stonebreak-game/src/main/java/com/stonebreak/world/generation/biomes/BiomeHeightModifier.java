package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.NoiseGenerator;

/**
 * Computes an additive height delta per biome from a dedicated noise channel.
 *
 * Final height = base continentalness height + this delta.
 *
 * Noise seed offset map (must stay distinct so channels are independent):
 *   seed + 0 : moisture         (BiomeManager)
 *   seed + 1 : temperature      (BiomeManager)
 *   seed + 2 : continentalness  (HeightMapGenerator)
 *   seed + 3 : biome detail     (this class)
 */
public final class BiomeHeightModifier {

    private final NoiseGenerator detailNoise;

    public BiomeHeightModifier(long seed) {
        this.detailNoise = new NoiseGenerator(seed + 3);
    }

    /**
     * Height delta to add to the base height, in blocks. Range is
     * approximately [-amplitude, +amplitude] per the biome's configuration.
     */
    public int calculateHeightDelta(BiomeType biome, int x, int z) {
        BiomeHeightConfig.BiomeConfig config = BiomeHeightConfig.getConfig(biome);
        float noise = detailNoise.noise(x / config.noiseScale, z / config.noiseScale);
        return (int) (noise * config.amplitude);
    }
}
