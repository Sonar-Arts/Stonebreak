package com.stonebreak.world.generation.heightmap;

import com.stonebreak.util.SplineInterpolator;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.debug.HeightCalculationDebugInfo;
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
 * Terrain Hint System (Phase 1 Enhancement):
 * Parameter patterns are detected and terrain-specific generation applied:
 * - MESA: Large terracing, plateau flattening
 * - SHARP_PEAKS: Amplified spikes, jagged surfaces
 * - GENTLE_HILLS: Reduced variation, smooth terrain
 * - FLAT_PLAINS: Minimal variation
 * - NORMAL: Standard terrain generation
 *
 * Follows Single Responsibility Principle - only handles height calculations.
 * Follows Dependency Inversion Principle - configuration injected via constructor.
 */
public class HeightMapGenerator {
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;

    private final long seed;
    private final NoiseGenerator continentalnessNoise;
    private final NoiseGenerator plateauNoise;  // For mesa plateau variation
    private final NoiseGenerator spireNoise;    // For peak jaggedness
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
        this.plateauNoise = new NoiseGenerator(seed + 1000, NoiseConfigFactory.temperature()); // Reuse config for variety
        this.spireNoise = new NoiseGenerator(seed + 2000, NoiseConfigFactory.moisture()); // Reuse config for jaggedness
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
    public int generateHeight(int x, int z) {
        float continentalness = getContinentalness(x, z);
        int height = (int) terrainSpline.interpolate(continentalness);
        return Math.max(1, Math.min(height, WORLD_HEIGHT - 1));
    }

    /**
     * Generates terrain height using multi-noise parameters with terrain hint system.
     *
     * This is the core of terrain-independent generation with Phase 1 enhancements.
     * Height is determined by:
     * 1. Continentalness → Base height (ocean vs inland)
     * 2. Terrain hint detection → Classify terrain type (mesa, peaks, hills, plains, normal)
     * 3. Hint-specific generation → Apply appropriate terrain logic
     *
     * The terrain hint system creates natural alignment between terrain shape and biomes
     * because both use the same underlying parameters (the "soft link" approach).
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

        // Step 2: Detect terrain hint from parameter pattern
        TerrainHint hint = TerrainHintClassifier.classifyTerrain(params);

        // Step 3: Apply hint-specific terrain generation
        int height = switch (hint) {
            case MESA -> applyMesaTerrain(baseHeight, params, x, z);
            case SHARP_PEAKS -> applySharpPeaksTerrain(baseHeight, params, x, z);
            case GENTLE_HILLS -> applyGentleHillsTerrain(baseHeight, params, x, z);
            case FLAT_PLAINS -> applyFlatPlainsTerrain(baseHeight, params, x, z);
            case NORMAL -> applyNormalTerrain(baseHeight, params, x, z);
        };

        // Clamp to world bounds
        return Math.max(1, Math.min(height, WORLD_HEIGHT - 1));
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

        // Map erosion [-1, 1] to amplification factor [1.60, 0.25]
        // Low erosion (-1) = 1.60x amplification (60% stronger mountains)
        // Medium erosion (0) = 0.70x amplification (30% reduction)
        // High erosion (1) = 0.25x amplification (75% reduction, very flat)
        float erosionFactor = 0.70f - (0.675f * erosion) + (0.225f * erosion * erosion);

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

    // ========== TERRAIN HINT-SPECIFIC GENERATION METHODS ==========

    /**
     * Applies mesa terrain generation (Badlands-style).
     *
     * Mesa characteristics:
     * - Large terracing (16-24 block steps) for plateau effect
     * - Flattened plateaus (reduced erosion effect)
     * - Plateau height variation adds different mesa levels
     *
     * @param baseHeight Base height from continentalness
     * @param params Multi-noise parameters
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Height with mesa terrain applied
     */
    private int applyMesaTerrain(int baseHeight, MultiNoiseParameters params, int x, int z) {
        int deltaFromSeaLevel = baseHeight - seaLevel;

        // Apply erosion with new quadratic curve (same as all terrain types)
        float erosionFactor = 0.70f - (0.675f * params.erosion) + (0.225f * params.erosion * params.erosion);
        int adjustedHeight = seaLevel + Math.round(deltaFromSeaLevel * erosionFactor);

        // Apply PV with reduced effect (mesas are flatter on top)
        if (Math.abs(adjustedHeight - seaLevel) > 20) {
            float heightFactor = (adjustedHeight - seaLevel) / 150.0f;
            int pvDelta = Math.round(params.peaksValleys * heightFactor * 15);  // Half normal (30 → 15)
            adjustedHeight += pvDelta;
        }

        // Strong terracing effect for mesas (16-24 block layers)
        if (params.weirdness > 0.7f) {
            // Layer height varies based on weirdness intensity
            int layerHeight = 16 + Math.round((params.weirdness - 0.7f) * 27);  // 16-24 blocks
            adjustedHeight = (adjustedHeight / layerHeight) * layerHeight;

            // Add plateau variation (different mesa heights)
            float plateauValue = (float) plateauNoise.noise(x / 80.0f, z / 80.0f);
            if (plateauValue > 0.3f) {
                adjustedHeight += layerHeight;  // Add one layer for variety
            }
        }

        return adjustedHeight;
    }

    /**
     * Applies sharp peaks terrain generation (Stony Peaks-style).
     *
     * Sharp peaks characteristics:
     * - Amplified erosion effect (MORE mountainous)
     * - Strong PV amplification (dramatic peaks and valleys)
     * - High-frequency noise for jaggedness
     *
     * @param baseHeight Base height from continentalness
     * @param params Multi-noise parameters
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Height with sharp peaks terrain applied
     */
    private int applySharpPeaksTerrain(int baseHeight, MultiNoiseParameters params, int x, int z) {
        int deltaFromSeaLevel = baseHeight - seaLevel;

        // Apply erosion with new quadratic curve (same as all terrain types)
        float erosionFactor = 0.70f - (0.675f * params.erosion) + (0.225f * params.erosion * params.erosion);
        int adjustedHeight = seaLevel + Math.round(deltaFromSeaLevel * erosionFactor);

        // Strong PV amplification for jagged peaks
        if (Math.abs(adjustedHeight - seaLevel) > 20) {
            float heightFactor = (adjustedHeight - seaLevel) / 100.0f;
            int pvDelta = Math.round(params.peaksValleys * heightFactor * 50);  // Amplified (30 → 50)
            adjustedHeight += pvDelta;
        }

        // Add high-frequency noise for jaggedness
        if (adjustedHeight > seaLevel + 30) {  // Only affect mountains
            float spireValue = (float) spireNoise.noise(x / 15.0f, z / 15.0f);  // Small scale for detail
            adjustedHeight += Math.round(spireValue * 15);  // ±15 block variation
        }

        // NO weirdness terracing (we want spires, not plateaus)

        return adjustedHeight;
    }

    /**
     * Applies gentle hills terrain generation.
     *
     * Gentle hills characteristics:
     * - Strong erosion effect (flattening)
     * - Minimal PV effect
     *
     * @param baseHeight Base height from continentalness
     * @param params Multi-noise parameters
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Height with gentle hills terrain applied
     */
    private int applyGentleHillsTerrain(int baseHeight, MultiNoiseParameters params, int x, int z) {
        int deltaFromSeaLevel = baseHeight - seaLevel;

        // Apply erosion with new quadratic curve (same as all terrain types)
        float erosionFactor = 0.70f - (0.675f * params.erosion) + (0.225f * params.erosion * params.erosion);
        int adjustedHeight = seaLevel + Math.round(deltaFromSeaLevel * erosionFactor);

        // Minimal PV effect for gentle terrain
        if (Math.abs(adjustedHeight - seaLevel) > 20) {
            float heightFactor = (adjustedHeight - seaLevel) / 100.0f;
            int pvDelta = Math.round(params.peaksValleys * heightFactor * 20);  // Reduced (30 → 20)
            adjustedHeight += pvDelta;
        }

        return adjustedHeight;
    }

    /**
     * Applies flat plains terrain generation.
     *
     * Flat plains characteristics:
     * - Maximum erosion effect (very flat)
     * - No PV or weirdness effects
     *
     * @param baseHeight Base height from continentalness
     * @param params Multi-noise parameters
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Height with flat plains terrain applied
     */
    private int applyFlatPlainsTerrain(int baseHeight, MultiNoiseParameters params, int x, int z) {
        int deltaFromSeaLevel = baseHeight - seaLevel;

        // Apply erosion with new quadratic curve (same as all terrain types)
        float erosionFactor = 0.70f - (0.675f * params.erosion) + (0.225f * params.erosion * params.erosion);
        int adjustedHeight = seaLevel + Math.round(deltaFromSeaLevel * erosionFactor);

        // No PV or weirdness effects (keep it flat)

        return adjustedHeight;
    }

    /**
     * Applies normal terrain generation (standard erosion/PV/weirdness).
     *
     * This is the original terrain generation logic, used as the default
     * for biomes that don't match specific terrain hints.
     *
     * @param baseHeight Base height from continentalness
     * @param params Multi-noise parameters
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Height with normal terrain applied
     */
    private int applyNormalTerrain(int baseHeight, MultiNoiseParameters params, int x, int z) {
        // Apply standard erosion factor
        int height = applyErosionFactor(baseHeight, params.erosion);

        // Apply standard peaks & valleys
        height = applyPeaksValleys(height, params.peaksValleys);

        // Apply standard weirdness (if significant)
        if (Math.abs(params.weirdness) > 0.5f) {
            height = applyWeirdnessTerrain(height, params.weirdness);
        }

        return height;
    }

    // ========== HELPER METHODS (Standard terrain logic) ==========

    /**
     * Gets the continentalness value at the specified world position.
     * Continentalness determines whether terrain is ocean, coast, or land.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Continentalness value in range [-1.0, 1.0]
     */
    public float getContinentalness(int x, int z) {
        return continentalnessNoise.noise(x / continentalnessNoiseScale, z / continentalnessNoiseScale);
    }

    /**
     * Gets the world seed used by this generator.
     *
     * @return World seed
     */
    public long getSeed() {
        return seed;
    }

    /**
     * Gets comprehensive height calculation debug information for F3 visualization.
     * Replicates the generation logic but captures all intermediate values.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param params Multi-noise parameters at this position
     * @return Debug information with all calculation steps
     */
    public HeightCalculationDebugInfo getHeightCalculationDebugInfo(int x, int z, MultiNoiseParameters params) {
        // Step 1: Base height from continentalness
        double baseHeight = terrainSpline.interpolate(params.continentalness);

        // Step 2: Detect terrain hint
        TerrainHint hint = TerrainHintClassifier.classifyTerrain(params);

        // Step 3: Calculate intermediate values based on terrain hint
        // For NORMAL terrain, we can capture exact erosion/PV/weirdness steps
        // For other hints, we approximate the steps since they have custom logic
        double heightAfterHint = baseHeight;  // After hint-specific initial processing
        double erosionFactor = 0.70f - (0.675f * params.erosion) + (0.225f * params.erosion * params.erosion);  // New quadratic erosion factor
        double heightAfterErosion;
        double pvAmplification;
        double heightAfterPV;
        double weirdnessTerracing;
        double heightAfterWeirdness;
        double finalHeight;

        if (hint == TerrainHint.NORMAL) {
            // For NORMAL terrain, we can trace exact steps
            int baseHeightInt = (int) baseHeight;
            heightAfterHint = baseHeightInt;

            // Apply erosion
            int heightAfterErosionInt = applyErosionFactor(baseHeightInt, params.erosion);
            heightAfterErosion = heightAfterErosionInt;
            erosionFactor = 0.70f - (0.675f * params.erosion) + (0.225f * params.erosion * params.erosion);

            // Calculate PV amplification
            int heightAfterPVInt = applyPeaksValleys(heightAfterErosionInt, params.peaksValleys);
            heightAfterPV = heightAfterPVInt;
            pvAmplification = heightAfterPV - heightAfterErosion;

            // Apply weirdness
            int heightAfterWeirdnessInt = (Math.abs(params.weirdness) > 0.5f) ?
                applyWeirdnessTerrain(heightAfterPVInt, params.weirdness) : heightAfterPVInt;
            heightAfterWeirdness = heightAfterWeirdnessInt;
            weirdnessTerracing = heightAfterWeirdness - heightAfterPV;

            finalHeight = Math.max(1, Math.min(heightAfterWeirdnessInt, WORLD_HEIGHT - 1));
        } else {
            // For other hints, approximate the steps (custom logic inside hint methods)
            int heightInt = generateHeight(x, z, params);
            finalHeight = heightInt;

            // Approximate intermediate values for visualization
            int deltaFromSeaLevel = (int)baseHeight - seaLevel;
            heightAfterHint = baseHeight;

            // All terrain hints now use the same erosion formula (already set above)
            heightAfterErosion = seaLevel + (deltaFromSeaLevel * erosionFactor);

            // Estimate PV and weirdness (these are approximations)
            heightAfterPV = heightAfterErosion + ((finalHeight - heightAfterErosion) * 0.7);  // 70% from PV
            pvAmplification = heightAfterPV - heightAfterErosion;
            heightAfterWeirdness = finalHeight;  // Weirdness applied at end
            weirdnessTerracing = heightAfterWeirdness - heightAfterPV;
        }

        return new HeightCalculationDebugInfo.LegacyDebugInfo(
            baseHeight,
            hint,
            heightAfterHint,
            erosionFactor,
            heightAfterErosion,
            pvAmplification,
            heightAfterPV,
            weirdnessTerracing,
            heightAfterWeirdness,
            finalHeight
        );
    }
}
