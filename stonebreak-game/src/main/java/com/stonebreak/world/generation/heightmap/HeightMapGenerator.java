package com.stonebreak.world.generation.heightmap;

import com.stonebreak.util.SplineInterpolator;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.biomes.BiomeBlendResult;
import com.stonebreak.world.generation.biomes.BiomeHeightModifier;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Map;

/**
 * Handles height map generation using noise functions and spline interpolation.
 * Generates terrain height based on continentalness values, producing varied landscapes
 * from deep oceans to high mountain peaks.
 *
 * Phase 1 Enhancement: Adds biome-specific height modifications using additive noise layers.
 * Phase 3 Enhancement: Adds blended height generation for smooth biome transitions.
 * Architecture: Base Height (continentalness) + Biome Modifier + Blending = Final Height
 *
 * Follows Single Responsibility Principle - only handles height calculations.
 */
public class HeightMapGenerator {
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;

    private final long seed;
    private final NoiseGenerator continentalnessNoise;
    private final SplineInterpolator terrainSpline;
    private final BiomeHeightModifier biomeHeightModifier;

    /**
     * Creates a new height map generator with the given seed.
     *
     * @param seed World seed for deterministic generation
     */
    public HeightMapGenerator(long seed) {
        this.seed = seed;
        this.continentalnessNoise = new NoiseGenerator(seed + 2);
        this.biomeHeightModifier = new BiomeHeightModifier(seed);
        this.terrainSpline = new SplineInterpolator();
        initializeTerrainSpline();
    }

    /**
     * Initializes the terrain spline with height control points.
     * Maps continentalness values (-1.0 to 1.0) to terrain heights.
     */
    private void initializeTerrainSpline() {
        terrainSpline.addPoint(-1.0, 70);  // Islands (above sea level)
        terrainSpline.addPoint(-0.8, 20);  // Deep ocean
        terrainSpline.addPoint(-0.4, 60);  // Approaching coast
        terrainSpline.addPoint(-0.2, 70);  // Just above sea level
        terrainSpline.addPoint(0.1, 75);   // Lowlands
        terrainSpline.addPoint(0.3, 120);  // Mountain foothills
        terrainSpline.addPoint(0.7, 140);  // Common foothills
        terrainSpline.addPoint(1.0, 200);  // High peaks
    }

    /**
     * Generates base terrain height for the specified world position.
     * This returns the height from continentalness only, without biome-specific modifications.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Base terrain height at the given position (clamped to world bounds)
     */
    public int generateHeight(int x, int z) {
        float continentalness = getContinentalness(x, z);
        int height = (int) terrainSpline.interpolate(continentalness);
        return Math.max(1, Math.min(height, WORLD_HEIGHT - 1));
    }

    /**
     * Applies biome-specific height modification to the base terrain height.
     *
     * This method implements Phase 1 of the biome enhancement system, adding unique
     * terrain characteristics to each biome type. Uses the additive pattern:
     *   Final Height = Base Height + Biome Modifier Delta
     *
     * Each biome has its own noise parameters creating distinct terrain:
     * - PLAINS: Gentle rolling hills (+/- 7.5 blocks)
     * - DESERT: Flat with subtle dunes (+/- 3.5 blocks)
     * - RED_SAND_DESERT: Rolling volcanic hills (+/- 10 blocks)
     * - SNOWY_PLAINS: Gentle snowy hills (+/- 12.5 blocks)
     *
     * @param baseHeight The base height from continentalness
     * @param biome      The biome type at this location
     * @param x          World X coordinate
     * @param z          World Z coordinate
     * @return Final height with biome-specific modifications applied (clamped to world bounds)
     */
    public int applyBiomeModifier(int baseHeight, BiomeType biome, int x, int z) {
        int heightDelta = biomeHeightModifier.calculateHeightDelta(biome, x, z);
        int modifiedHeight = baseHeight + heightDelta;
        return Math.max(1, Math.min(modifiedHeight, WORLD_HEIGHT - 1));
    }

    /**
     * Generates blended height using weighted biome influences.
     *
     * Phase 3: Creates smooth terrain transitions between biomes by blending
     * heights from multiple nearby biomes based on their weights.
     *
     * Algorithm:
     * 1. Start with base height from continentalness
     * 2. For each biome in the blend result:
     *    - Calculate height with that biome's modifier
     *    - Multiply by biome's weight
     *    - Add to total
     * 3. Return weighted average of all biome heights
     *
     * This eliminates harsh height cliffs at biome boundaries, creating
     * natural-looking transitions (e.g., desert gradually rising into mountains).
     *
     * @param baseHeight  The base height from continentalness
     * @param blendResult The biome blend result with weighted influences
     * @param x           World X coordinate
     * @param z           World Z coordinate
     * @return Blended height from multiple biomes (clamped to world bounds)
     */
    public int generateBlendedHeight(int baseHeight, BiomeBlendResult blendResult, int x, int z) {
        // If one biome is strongly dominant (>80% weight), skip blending for performance
        if (blendResult.isStronglyDominant(0.8f)) {
            BiomeType dominantBiome = blendResult.getDominantBiome();
            return applyBiomeModifier(baseHeight, dominantBiome, x, z);
        }

        // Blend heights from all influencing biomes
        float blendedHeight = 0.0f;

        for (Map.Entry<BiomeType, Float> entry : blendResult.getWeights().entrySet()) {
            BiomeType biome = entry.getKey();
            float weight = entry.getValue();

            // Calculate height for this biome
            int biomeHeight = applyBiomeModifier(baseHeight, biome, x, z);

            // Add weighted contribution
            blendedHeight += biomeHeight * weight;
        }

        // Round and clamp to world bounds
        int finalHeight = Math.round(blendedHeight);
        return Math.max(1, Math.min(finalHeight, WORLD_HEIGHT - 1));
    }

    /**
     * Gets the continentalness value at the specified world position.
     * Continentalness determines whether terrain is ocean, coast, or land.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Continentalness value in range [-1.0, 1.0]
     */
    public float getContinentalness(int x, int z) {
        return continentalnessNoise.noise(x / 800.0f, z / 800.0f);
    }

    /**
     * Gets the world seed used by this generator.
     *
     * @return World seed
     */
    public long getSeed() {
        return seed;
    }
}
