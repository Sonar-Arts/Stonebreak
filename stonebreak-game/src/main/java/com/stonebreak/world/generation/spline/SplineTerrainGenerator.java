package com.stonebreak.world.generation.spline;

import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.debug.HeightCalculationDebugInfo;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;

import java.util.ArrayList;

/**
 * Spline-based multi-parameter terrain generator.
 * <p>
 * This generator uses unified multi-dimensional spline interpolation inspired by
 * Minecraft 1.18+ density functions. Instead of sequential parameter application,
 * it evaluates a single unified spline: height = f(continentalness, erosion, PV, weirdness).
 * <p>
 * <strong>Key Features:</strong>
 * <ul>
 *   <li>Unified 4D spline interpolation (continentalness, erosion, PV, weirdness)</li>
 *   <li>Smooth transitions between terrain types (oceans, plains, mountains, plateaus)</li>
 *   <li>No terrain hints - all variety encoded in spline points</li>
 *   <li>Weirdness terracing for mesa/plateau formations</li>
 *   <li>PV amplification for dramatic peaks and valleys</li>
 * </ul>
 *
 * @see TerrainSplineRouter
 * @see MultiDimensionalSpline
 * @see com.stonebreak.world.generation.legacy.LegacyTerrainGenerator
 */
public class SplineTerrainGenerator implements TerrainGenerator {

    private final long seed;
    private final TerrainGenerationConfig config;
    private final TerrainSplineRouter splineRouter;

    /**
     * Create a new spline terrain generator.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public SplineTerrainGenerator(long seed, TerrainGenerationConfig config) {
        this.seed = seed;
        this.config = config;
        this.splineRouter = new TerrainSplineRouter(seed, config);
    }

    @Override
    public int generateHeight(int x, int z, MultiNoiseParameters params) {
        return splineRouter.getHeight(params);
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
