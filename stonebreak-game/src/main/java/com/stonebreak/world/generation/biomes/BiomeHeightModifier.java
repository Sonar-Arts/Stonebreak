package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfigFactory;

/**
 * Applies biome-specific height modifications to the base terrain heightmap.
 *
 * Uses an additive approach: generates a height delta based on biome-specific noise
 * parameters, which is added to the base continentalness height. This creates
 * distinct terrain characteristics for each biome (gentle plains, flat deserts,
 * rolling volcanic hills, snowy tundra hills, etc.).
 *
 * Phase 1 Enhancement: Uses biomeDetail noise config for consistent terrain character.
 * Architecture follows industry-standard multi-noise layering pattern:
 *   Final Height = Base Height (continentalness) + Biome Modifier (this class)
 *
 * Each biome uses different noise scales and amplitudes to create unique terrain:
 * - PLAINS: Gentle rolling hills (50-block scale, 5-10 block variation)
 * - DESERT: Flat with subtle dunes (120-block scale, 2-5 block variation)
 * - RED_SAND_DESERT: Rolling volcanic hills (90-block scale, 8-12 block variation)
 * - SNOWY_PLAINS: Gentle snowy hills (100-block scale, 10-15 block variation)
 */
public class BiomeHeightModifier {

    private final NoiseGenerator detailNoise;

    /**
     * Creates a new biome height modifier with a dedicated noise generator.
     * Uses biomeDetail noise config for appropriate biome-specific terrain variation.
     *
     * @param seed The world seed (offset by +3 for independent noise from other generators)
     */
    public BiomeHeightModifier(long seed) {
        // Use seed + 3 to ensure this noise is independent from moisture (seed) and temperature (seed + 1)
        this.detailNoise = new NoiseGenerator(seed + 3, NoiseConfigFactory.biomeDetail());
    }

    /**
     * Calculates the height delta to apply based on the biome type and position.
     *
     * This method uses the additive pattern: it returns a delta value that should be
     * added to the base height, not an absolute height value. This maintains clean
     * separation between base terrain generation and biome-specific detail.
     *
     * @param biome The biome type at this location
     * @param x     World X coordinate
     * @param z     World Z coordinate
     * @return The height delta to add to the base height (can be positive or negative)
     */
    public int calculateHeightDelta(BiomeType biome, int x, int z) {
        BiomeHeightConfig.BiomeConfig config = BiomeHeightConfig.getConfig(biome);

        // Generate noise value at the biome-specific scale
        // Noise output is in range [-1, 1]
        float noise = detailNoise.noise(x / config.noiseScale, z / config.noiseScale);

        // Convert noise to height variation using biome-specific amplitude
        // Result is in range [-amplitude, +amplitude]
        int heightDelta = (int) (noise * config.amplitude);

        return heightDelta;
    }
}
