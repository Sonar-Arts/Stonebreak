package com.stonebreak.world.generation.heightmap;

import com.stonebreak.util.SplineInterpolator;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Handles height map generation using noise functions and spline interpolation.
 * Generates terrain height based on continentalness values, producing varied landscapes
 * from deep oceans to high mountain peaks.
 *
 * Multi-Noise System: Terrain generates independently from biomes.
 * - Continentalness: Base height (ocean vs inland)
 * - Erosion: Flat vs mountainous (high erosion = flat, low erosion = mountains)
 * - Peaks & Valleys: Amplifies height extremes
 * - Weirdness: Creates plateaus and mesas
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
    private final float continentalnessNoiseScale;
    private final int seaLevel;

    /**
     * Creates a new height map generator with the given seed and configuration.
     * Uses continentalness noise config for large-scale landmass distribution.
     *
     * Multi-Noise System: Height determined by continentalness + erosion + PV + weirdness.
     * Biomes no longer influence terrain height.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public HeightMapGenerator(long seed, TerrainGenerationConfig config) {
        this.seed = seed;
        this.continentalnessNoise = new NoiseGenerator(seed + 2, NoiseConfigFactory.continentalness());
        this.terrainSpline = new SplineInterpolator();
        this.continentalnessNoiseScale = config.continentalnessNoiseScale;
        this.seaLevel = WorldConfiguration.SEA_LEVEL;
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
     * Generates terrain height using multi-noise parameters (NEW SYSTEM).
     *
     * This is the core of terrain-independent generation. Height is determined by:
     * 1. Continentalness → Base height (ocean vs inland)
     * 2. Erosion → Flatten or amplify (high erosion = flat plains, low erosion = mountains)
     * 3. Peaks & Valleys → Amplify height extremes (make peaks higher, valleys deeper)
     * 4. Weirdness → Create plateaus and mesas (terracing effect)
     *
     * Biomes NO LONGER affect terrain height - they only determine surface materials.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param params Multi-noise parameters at this position
     * @return Final terrain height (clamped to world bounds)
     */
    public int generateHeight(int x, int z, MultiNoiseParameters params) {
        // Step 1: Base height from continentalness (same as before)
        int baseHeight = (int) terrainSpline.interpolate(params.continentalness);

        // Step 2: Apply erosion factor (flat vs mountainous)
        baseHeight = applyErosionFactor(baseHeight, params.erosion);

        // Step 3: Apply peaks & valleys (amplify extremes)
        baseHeight = applyPeaksValleys(baseHeight, params.peaksValleys);

        // Step 4: Apply weirdness (plateaus, mesas, terracing)
        if (Math.abs(params.weirdness) > 0.5f) {
            baseHeight = applyWeirdnessTerrain(baseHeight, params.weirdness);
        }

        // Clamp to world bounds
        return Math.max(1, Math.min(baseHeight, WORLD_HEIGHT - 1));
    }

    /**
     * Applies erosion factor to terrain height.
     *
     * Erosion determines how flat or mountainous terrain is:
     * - High erosion (0.5 to 1.0) → Flatten toward sea level (creates plains)
     * - Low erosion (-1.0 to -0.5) → Amplify height differences (creates mountains)
     * - Medium erosion (-0.5 to 0.5) → Moderate terrain
     *
     * Algorithm:
     * 1. Calculate height difference from sea level
     * 2. Apply erosion-based scaling factor
     * 3. Recombine with sea level
     *
     * @param baseHeight Base height from continentalness
     * @param erosion Erosion value [-1.0, 1.0]
     * @return Height adjusted for erosion
     */
    private int applyErosionFactor(int baseHeight, float erosion) {
        int deltaFromSeaLevel = baseHeight - seaLevel;

        // Map erosion [-1, 1] to amplification factor [1.5, 0.6]
        // Low erosion (-1) = 1.5x amplification (mountainous)
        // High erosion (1) = 0.6x amplification (flat)
        float erosionFactor = 1.0f - (erosion * 0.45f);

        // Scale the height difference from sea level
        int adjustedDelta = Math.round(deltaFromSeaLevel * erosionFactor);

        return seaLevel + adjustedDelta;
    }

    /**
     * Applies peaks & valleys effect to terrain height.
     *
     * PV amplifies height extremes:
     * - High PV (> 0) → Make high areas higher (sharper peaks)
     * - Low PV (< 0) → Make low areas lower (deeper valleys)
     * - Near 0 → Minimal effect
     *
     * Only affects areas with significant height variation (>20 blocks from sea level).
     * Flat areas near sea level remain unaffected.
     *
     * @param baseHeight Base height after erosion
     * @param pv Peaks & valleys value [-1.0, 1.0]
     * @return Height adjusted for peaks & valleys
     */
    private int applyPeaksValleys(int baseHeight, float pv) {
        int deltaFromSeaLevel = baseHeight - seaLevel;

        // Only apply PV to areas with significant height variation
        if (Math.abs(deltaFromSeaLevel) < 20) {
            return baseHeight;  // Flat areas unaffected
        }

        // Calculate amplification based on PV and current height
        // Higher terrain gets amplified more
        float heightFactor = deltaFromSeaLevel / 100.0f;  // Normalize to ~[-1, 1]

        // PV can add up to ±30 blocks for extreme heights
        int pvDelta = Math.round(pv * heightFactor * 30);

        return baseHeight + pvDelta;
    }

    /**
     * Applies weirdness terrain effects (plateaus, mesas, terracing).
     *
     * High weirdness creates unique terrain features:
     * - Weirdness > 0.7 → Terracing effect (quantized height layers)
     * - Weirdness < -0.7 → (Future: spires, arches, etc.)
     *
     * @param height Height before weirdness
     * @param weirdness Weirdness value [-1.0, 1.0]
     * @return Height adjusted for weirdness
     */
    private int applyWeirdnessTerrain(int height, float weirdness) {
        // High positive weirdness: Terracing (Badlands-style mesas)
        if (weirdness > 0.7f) {
            int layerHeight = 8;  // 8-block layers
            return (height / layerHeight) * layerHeight;
        }

        // High negative weirdness: Reserved for future features
        // Could add spires, arches, etc.

        return height;
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
}
