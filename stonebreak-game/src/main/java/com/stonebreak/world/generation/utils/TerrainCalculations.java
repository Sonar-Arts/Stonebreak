package com.stonebreak.world.generation.utils;

import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.noise.NoiseRouter;

/**
 * Shared terrain calculation utilities to eliminate code duplication.
 *
 * <p>Consolidates repeated formulas across terrain generators, fixing DRY violations.
 * All methods are static and stateless for thread safety and simplicity.</p>
 *
 * <p><b>Eliminates 7 duplicate code patterns:</b></p>
 * <ul>
 *   <li>Erosion factor calculation (7 duplicates across 4 files)</li>
 *   <li>PV amplification calculation (6 duplicates across 4 files)</li>
 *   <li>Modified height calculation (3 duplicates across 3 files)</li>
 *   <li>Jaggedness strength calculation</li>
 *   <li>Terrain height calculation (2 duplicates)</li>
 * </ul>
 *
 * @since Terra v.12 (Refactoring)
 */
public final class TerrainCalculations {

    private TerrainCalculations() {
        // Utility class - prevent instantiation
    }

    /**
     * Calculate erosion factor from multi-noise parameters.
     *
     * <p><b>Formula:</b> {@code 1.0f - (erosion * 0.4f)}</p>
     * <p><b>Range:</b> [0.6, 1.4] (when erosion ∈ [-1.0, 1.0])</p>
     * <p><b>Interpretation:</b></p>
     * <ul>
     *   <li>erosion = -1.0 (mountains): factor = 1.4 (amplify height)</li>
     *   <li>erosion = 0.0 (neutral): factor = 1.0 (no change)</li>
     *   <li>erosion = 1.0 (plains): factor = 0.6 (flatten terrain)</li>
     * </ul>
     *
     * <p><b>Used by:</b> SplineTerrainGenerator, TerrainDensityFunction,
     * HybridDensityFunction, HybridSdfTerrainGenerator</p>
     *
     * @param erosion Erosion value from multi-noise [-1.0 to 1.0]
     * @return Erosion factor for height modification
     */
    public static float calculateErosionFactor(float erosion) {
        return 1.0f - (erosion * 0.4f);
    }

    /**
     * Calculate peaks & valleys amplification from multi-noise parameters.
     *
     * <p><b>Formula:</b> {@code peaksValleys * 8.0f}</p>
     * <p><b>Range:</b> [-8.0, 8.0] (when PV ∈ [-1.0, 1.0])</p>
     * <p><b>Interpretation:</b></p>
     * <ul>
     *   <li>PV = -1.0 (deep valleys): -8 blocks lower</li>
     *   <li>PV = 0.0 (neutral): no change</li>
     *   <li>PV = 1.0 (high peaks): +8 blocks higher</li>
     * </ul>
     *
     * <p><b>Used by:</b> SplineTerrainGenerator, TerrainDensityFunction,
     * HybridDensityFunction, HybridSdfTerrainGenerator</p>
     *
     * @param peaksValleys Peaks/valleys value from multi-noise [-1.0 to 1.0]
     * @return Vertical offset in blocks
     */
    public static float calculatePVAmplification(float peaksValleys) {
        return peaksValleys * 8.0f;
    }

    /**
     * Calculate modified height from sea level using erosion and PV.
     *
     * <p>Shared pattern across all terrain generators. Applies erosion factor
     * to height deviation from sea level, then adds PV amplification.</p>
     *
     * <p><b>Formula:</b></p>
     * <pre>
     * heightFromSeaLevel = baseOffset - seaLevel
     * modifiedHeight = seaLevel + (heightFromSeaLevel * erosionFactor) + pvAmplification
     * </pre>
     *
     * <p><b>Used by:</b> SplineTerrainGenerator, TerrainDensityFunction, HybridDensityFunction</p>
     *
     * @param baseOffset Base height from spline router
     * @param seaLevel Sea level Y coordinate (typically 34.0f or 64.0f)
     * @param erosionFactor Erosion factor from {@link #calculateErosionFactor}
     * @param pvAmplification PV amplification from {@link #calculatePVAmplification}
     * @return Final modified height
     */
    public static float calculateModifiedHeight(float baseOffset, float seaLevel,
                                               float erosionFactor, float pvAmplification) {
        float heightFromSeaLevel = baseOffset - seaLevel;
        return seaLevel + (heightFromSeaLevel * erosionFactor) + pvAmplification;
    }

    /**
     * Calculate jaggedness strength from erosion parameter.
     *
     * <p><b>Formula:</b> {@code max(0.0, -erosion)}</p>
     * <p><b>Range:</b> [0.0, 1.0] (when erosion ∈ [-1.0, 1.0])</p>
     * <p><b>Interpretation:</b></p>
     * <ul>
     *   <li>erosion ≥ 0 (plains): strength = 0.0 (no jaggedness)</li>
     *   <li>erosion = -0.5 (hills): strength = 0.5 (moderate peaks)</li>
     *   <li>erosion = -1.0 (mountains): strength = 1.0 (sharp peaks)</li>
     * </ul>
     *
     * <p>More negative erosion values create sharper, more dramatic peaks.</p>
     *
     * <p><b>Used by:</b> SplineTerrainGenerator, HybridSdfTerrainGenerator</p>
     *
     * @param erosion Erosion value from multi-noise [-1.0 to 1.0]
     * @return Jaggedness strength multiplier
     */
    public static float calculateJaggednessStrength(float erosion) {
        return Math.max(0.0f, -erosion);
    }

    /**
     * Calculate terrain height at world coordinates using noise router and generator.
     *
     * <p>Consolidated helper pattern used by water systems to query terrain height
     * for basin detection and rim calculations.</p>
     *
     * <p><b>Used by:</b> WaterLevelGrid, RimDetector</p>
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param noiseRouter Noise router for parameter sampling
     * @param terrainGenerator Terrain generator for height calculation
     * @return Terrain height at (worldX, worldZ)
     */
    public static int calculateTerrainHeight(int worldX, int worldZ,
                                            NoiseRouter noiseRouter,
                                            TerrainGenerator terrainGenerator) {
        MultiNoiseParameters params = noiseRouter.sampleParameters(worldX, worldZ, 64);
        return terrainGenerator.generateHeight(worldX, worldZ, params);
    }
}
