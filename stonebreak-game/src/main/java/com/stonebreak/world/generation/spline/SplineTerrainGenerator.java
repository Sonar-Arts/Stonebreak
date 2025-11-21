package com.stonebreak.world.generation.spline;

import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.debug.HeightCalculationDebugInfo;
import com.stonebreak.world.generation.density.TerrainDensityFunction;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.noise.NoiseRouter;

import java.util.ArrayList;

/**
 * Spline-based terrain generator using cubic Hermite interpolation
 * and three-spline system (offset, jaggedness, factor)
 *
 * Based on Minecraft 1.18+ terrain generation with density functions.
 * <p>
 * <strong>Key Features:</strong>
 * <ul>
 *   <li>Cubic Hermite spline interpolation for smooth C¹ curves</li>
 *   <li>Three separate splines: offset (base height), jaggedness (peaks), factor (3D noise)</li>
 *   <li>Optional 3D density functions for caves and overhangs</li>
 *   <li>Derivative control for plateaus and terrain variety</li>
 * </ul>
 *
 * @see OffsetSplineRouter
 * @see JaggednessSplineRouter
 * @see FactorSplineRouter
 * @see TerrainDensityFunction
 */
public class SplineTerrainGenerator implements TerrainGenerator {

    private final long seed;
    private final TerrainGenerationConfig config;
    private final OffsetSplineRouter offsetRouter;
    private final JaggednessSplineRouter jaggednessRouter;
    private final FactorSplineRouter factorRouter;
    private final TerrainDensityFunction densityFunction;
    private final boolean use3DDensity;
    private final NoiseRouter noiseRouter;

    /**
     * Create a new spline terrain generator.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     * @param use3DDensity Whether to use 3D density functions (caves/overhangs)
     */
    public SplineTerrainGenerator(long seed, TerrainGenerationConfig config, boolean use3DDensity) {
        this.seed = seed;
        this.config = config;
        this.use3DDensity = use3DDensity;

        // Initialize the three spline routers (cached for performance)
        this.offsetRouter = new OffsetSplineRouter(seed);
        this.jaggednessRouter = new JaggednessSplineRouter(seed);
        this.factorRouter = new FactorSplineRouter(seed);
        this.densityFunction = new TerrainDensityFunction(seed);

        // Initialize noise router for TERRA v.11 regional flatness masking
        this.noiseRouter = new NoiseRouter(seed, config);
    }

    @Override
    public int generateHeight(int x, int z, MultiNoiseParameters params) {
        if (use3DDensity) {
            // Full 3D density function (caves, overhangs) - slower but more features
            return densityFunction.densityToHeight(x, z, params);
        } else {
            // Simplified 2D heightmap (faster, no overhangs)
            return generateHeight2D(x, z, params);
        }
    }

    /**
     * Simplified 2D height generation (no 3D density)
     * Much faster than full 3D sampling
     *
     * Uses multiplicative composition inspired by Minecraft 1.18+:
     * 1. Base offset from spline + multi-scale noise
     * 2. Erosion factor modifies height variation (low erosion = mountains, high = flat)
     * 3. Peaks & Valleys amplifies extremes
     * 4. Jaggedness adds fine detail to mountainous areas
     */
    private int generateHeight2D(int x, int z, MultiNoiseParameters params) {
        // Get base terrain offset with multi-scale noise variation
        float baseOffset = offsetRouter.getOffset(params, x, z);

        // TERRA v.09: Aggressive erosion factor curve (Terralith-inspired)
        // Split behavior: linear amplification for mountains, quadratic dampening for plains
        float erosionFactor;
        if (params.erosion < 0.0f) {
            // Mountains: Linear amplification (unchanged from previous versions)
            // erosion=-1.0 → 1.4x, erosion=-0.5 → 1.2x, erosion=0.0 → 1.0x
            erosionFactor = 1.0f - (params.erosion * 0.4f);
        } else {
            // Plains: Quadratic dampening for LEGACY-level flatness
            // erosion=0.3 → 0.928x (7% flatter)
            // erosion=0.5 → 0.8x (20% flatter)
            // erosion=0.7 → 0.608x (39% flatter)
            // erosion=1.0 → 0.2x (80% flatter) ← LEGACY-level flat plains
            erosionFactor = 1.0f - (params.erosion * params.erosion * 0.8f);
        }

        // Calculate PV amplification (amplifies height extremes)
        // Peaks (PV=1.0) get higher, valleys (PV=-1.0) get lower
        float pvAmplification = params.peaksValleys * 8.0f;

        // Apply erosion to height variation from sea level (64)
        float seaLevel = 64.0f;
        float heightFromSeaLevel = baseOffset - seaLevel;
        float modifiedHeight = seaLevel + (heightFromSeaLevel * erosionFactor) + pvAmplification;

        // TERRA v.10: Erosion-gated weirdness (floating islands only in mountains)
        // This prevents floating plains and keeps floating features in mountainous areas only
        // Works in conjunction with increased weirdness thresholds in OffsetSplineRouter (0.8 instead of 0.6)
        float weirdnessEffect = 0.0f;
        if (params.erosion < -0.3f && params.weirdness > 0.7f) {
            // Only apply weirdness boost in mountainous areas (erosion < -0.3)
            float weirdStrength = Math.max(0.0f, -params.erosion);  // 0.0 to 1.0
            // Scale from 0 at weirdness=0.7 to full effect at weirdness=1.0
            weirdnessEffect = (params.weirdness - 0.7f) / 0.3f * 12.0f * weirdStrength;
        }
        modifiedHeight += weirdnessEffect;

        // TERRA v.09: Minecraft-style jaggedness gating (Terralith-inspired)
        // Jaggedness only applies in specific conditions like Minecraft:
        // - High continentalness (mountainous inland areas)
        // - Low erosion (uneroded, sharp terrain)
        // - Not deep valleys (PV > -0.3)
        // This ensures jaggedness is truly ZERO in plains, oceans, and valleys
        float jaggednessStrength = 0.0f;
        if (params.continentalness > 0.4f && params.erosion < 0.0f && params.peaksValleys > -0.3f) {
            // Scale by erosion: more negative = more jagged
            jaggednessStrength = Math.max(0.0f, -params.erosion); // 0.0 to 1.0
        }
        float jaggedness = jaggednessRouter.getJaggedness(params, x, z) * jaggednessStrength;

        // TERRA v.11: Regional flatness masking (post-processing)
        // Sample regional flatness noise to determine if this is a plains region
        float regionalFlatness = noiseRouter.getRegionalFlatness(x, z);

        if (regionalFlatness > 0.3f) {
            // This is a "plains region" - apply aggressive height flattening
            // Calculate flattening strength: 0% at flatness=0.3, 100% at flatness=1.0
            float flatteningStrength = Math.min(1.0f, (regionalFlatness - 0.3f) / 0.7f);

            // Calculate deviation from sea level (Y=64)
            float heightDeviation = modifiedHeight - 64.0f;

            // Apply aggressive flattening: 0.3x to 0.15x of deviation
            // flatteningStrength=0 → 0.3x, flatteningStrength=1 → 0.15x
            float flatteningFactor = 0.3f - (flatteningStrength * 0.15f);

            // Apply flattening to deviation only (preserve sea level baseline)
            modifiedHeight = 64.0f + (heightDeviation * flatteningFactor);
        }
        // Else: mountain region (flatness <= 0.3) - no changes, preserve full Terra v.10 height

        return Math.round(modifiedHeight + jaggedness);
    }

    @Override
    public String getName() {
        return "Spline Terrain Generator";
    }

    @Override
    public String getDescription() {
        return "Multi-parameter spline-based terrain (Experimental)";
    }

    @Override
    public long getSeed() {
        return seed;
    }

    @Override
    public TerrainGeneratorType getType() {
        return TerrainGeneratorType.SPLINE;
    }

    @Override
    public HeightCalculationDebugInfo getHeightCalculationDebugInfo(int x, int z, MultiNoiseParameters params) {
        // TODO: Implement spline interpolation details collection for F3 visualization
        // This is a placeholder for Phase 1 - actual spline point collection will be implemented later
        double finalHeight = generateHeight(x, z, params);

        // For now, return empty interpolation points list
        // In future phases, this will collect actual spline points and weights from TerrainSplineRouter
        return new HeightCalculationDebugInfo.SplineDebugInfo(
            new ArrayList<>(),  // Empty interpolation points list (placeholder)
            finalHeight
        );
    }
}
