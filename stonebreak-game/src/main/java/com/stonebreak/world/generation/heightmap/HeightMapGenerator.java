package com.stonebreak.world.generation.heightmap;

import com.stonebreak.util.SplineInterpolator;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.biomes.BiomeBlendResult;
import com.stonebreak.world.generation.biomes.BiomeHeightModifier;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Map;

/**
 * Handles height map generation using noise functions and spline interpolation.
 * Generates terrain height based on continentalness values, producing varied landscapes
 * from deep oceans to high mountain peaks.
 *
 * Phase 1 Enhancement: Uses configurable noise parameters for different terrain characteristics.
 * Phase 3 Enhancement: Adds blended height generation for smooth biome transitions.
 * Architecture: Base Height (continentalness) + Biome Modifier + Blending = Final Height
 *
 * Follows Single Responsibility Principle - only handles height calculations.
 * Follows Dependency Inversion Principle - configuration injected via constructor.
 *
 * Implements IHeightMapGenerator for dependency inversion and testability.
 */
public class HeightMapGenerator implements IHeightMapGenerator {
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;

    private final long seed;
    private final NoiseGenerator continentalnessNoise;
    private final SplineInterpolator terrainSpline;
    private final BiomeHeightModifier biomeHeightModifier;
    private final ErosionNoiseGenerator erosionNoise;
    private final TerrainShaper terrainShaper;
    private final float continentalnessNoiseScale;

    /**
     * Creates a new height map generator with the given seed and configuration.
     * Uses continentalness noise config for large-scale landmass distribution.
     * Phase 1: Adds erosion noise for subtle terrain variation.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public HeightMapGenerator(long seed, TerrainGenerationConfig config) {
        this.seed = seed;
        this.continentalnessNoise = new NoiseGenerator(seed + 2, NoiseConfigFactory.continentalness());
        this.biomeHeightModifier = new BiomeHeightModifier(seed);
        this.erosionNoise = new ErosionNoiseGenerator(seed, config);
        this.terrainShaper = new TerrainShaper(seed);
        this.terrainSpline = new SplineInterpolator();
        this.continentalnessNoiseScale = config.continentalnessNoiseScale;
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
    @Override
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
    @Override
    public int applyBiomeModifier(int baseHeight, BiomeType biome, int x, int z) {
        int heightDelta = biomeHeightModifier.calculateHeightDelta(biome, x, z);
        int modifiedHeight = baseHeight + heightDelta;
        return Math.max(1, Math.min(modifiedHeight, WORLD_HEIGHT - 1));
    }

    /**
     * Generates blended height using weighted biome influences.
     *
     * Phase 1: Adds erosion noise for subtle terrain variation.
     * Phase 3: Creates smooth terrain transitions between biomes by blending
     * heights from multiple nearby biomes based on their weights.
     *
     * Algorithm:
     * 1. Start with base height from continentalness
     * 2. For each biome in the blend result:
     *    - Calculate height with that biome's modifier
     *    - Multiply by biome's weight
     *    - Add to total
     * 3. Apply erosion noise for weathering effects
     * 4. Return weighted average of all biome heights
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
    @Override
    public int generateBlendedHeight(int baseHeight, BiomeBlendResult blendResult, int x, int z) {
        // If one biome is strongly dominant (>80% weight), skip blending for performance
        if (blendResult.isStronglyDominant(0.8f)) {
            BiomeType dominantBiome = blendResult.getDominantBiome();
            int height = applyBiomeModifier(baseHeight, dominantBiome, x, z);

            // NEW: Apply terrain shaping (PV, Ridged, Weirdness noise)
            height = terrainShaper.shapeHeight(height, dominantBiome, x, z);

            // Apply erosion noise for subtle variation
            return erosionNoise.applyErosion(height, x, z);
        }

        // Blend heights from all influencing biomes
        float blendedHeight = 0.0f;

        for (Map.Entry<BiomeType, Float> entry : blendResult.getWeights().entrySet()) {
            BiomeType biome = entry.getKey();
            float weight = entry.getValue();

            // Calculate height for this biome
            int biomeHeight = applyBiomeModifier(baseHeight, biome, x, z);

            // NEW: Apply terrain shaping for this biome
            biomeHeight = terrainShaper.shapeHeight(biomeHeight, biome, x, z);

            // Add weighted contribution
            blendedHeight += biomeHeight * weight;
        }

        // Round to integer
        int finalHeight = Math.round(blendedHeight);

        // Apply erosion noise for subtle weathering effects
        finalHeight = erosionNoise.applyErosion(finalHeight, x, z);

        // Clamp to world bounds
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
    @Override
    public float getContinentalness(int x, int z) {
        return continentalnessNoise.noise(x / continentalnessNoiseScale, z / continentalnessNoiseScale);
    }

    /**
     * Gets the world seed used by this generator.
     *
     * @return World seed
     */
    @Override
    public long getSeed() {
        return seed;
    }

    /**
     * Gets the erosion noise value at the specified position.
     * This is the raw noise value before being applied to height.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Erosion noise value in range approximately [-0.3, 0.3]
     */
    public float getErosionNoiseValue(int x, int z) {
        return erosionNoise.getErosionNoise(x, z);
    }

    /**
     * Gets the base terrain height (before erosion) at the specified position.
     * Useful for debugging to see the effect of erosion noise.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Base height before erosion effects
     */
    public int getBaseHeightBeforeErosion(int x, int z) {
        return generateHeight(x, z);
    }
}
