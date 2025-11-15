package com.stonebreak.world.generation.spline;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfig;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;

/**
 * Offset Spline Router - Defines base terrain elevation
 *
 * This spline determines the fundamental height of terrain based on
 * continentalness and erosion parameters. It creates the large-scale
 * terrain features (oceans, plains, mountains) with smooth transitions.
 *
 * Key characteristics:
 * - Smooth curves (moderate derivatives)
 * - Continental-scale variation
 * - Multi-scale noise for spatial variation
 * - Independent of jaggedness/peaks
 */
public class OffsetSplineRouter {

    private final MultiDimensionalSpline offsetSpline;
    private final NoiseGenerator broadTerrainNoise;
    private final NoiseGenerator mediumTerrainNoise;

    public OffsetSplineRouter(long seed) {
        this.offsetSpline = buildOffsetSpline(seed);

        // Low-frequency noise for broad terrain waves (1/256 scale)
        // Uses fewer octaves for smooth, continental-scale variation
        NoiseConfig broadConfig = new NoiseConfig(2, 0.5, 2.0);
        this.broadTerrainNoise = new NoiseGenerator(seed + 789, broadConfig);

        // Medium-frequency noise for rolling hills (1/64 scale)
        // Uses moderate octaves for regional terrain detail
        NoiseConfig mediumConfig = new NoiseConfig(3, 0.5, 2.0);
        this.mediumTerrainNoise = new NoiseGenerator(seed + 101112, mediumConfig);
    }

    /**
     * Get base terrain offset (height) for given parameters and position
     *
     * Combines spline-based height with multi-scale noise to ensure
     * terrain varies spatially even when parameters are similar.
     */
    public float getOffset(MultiNoiseParameters params, int x, int z) {
        // Get base height from spline (parameter-based)
        float splineHeight = offsetSpline.sample(
            params.continentalness,
            params.erosion,
            params.peaksValleys
        );

        // Sample broad terrain noise (1/256 scale for continental waves)
        float broadNoise = broadTerrainNoise.noise(x / 256.0f, z / 256.0f);

        // Sample medium terrain noise (1/64 scale for rolling hills)
        float mediumNoise = mediumTerrainNoise.noise(x / 64.0f, z / 64.0f);

        // Calculate variation strength based on continentalness
        // Ocean areas have less variation, inland has more
        float variationStrength = Math.max(0.0f, params.continentalness * 15.0f + 10.0f);

        // Combine: base spline + broad waves + medium detail
        return splineHeight + (broadNoise * variationStrength) + (mediumNoise * variationStrength * 0.5f);
    }

    /**
     * Build the offset spline with control points and derivatives
     *
     * Structure: continentalness → erosion → PV → height
     *
     * Derivatives tuned for smooth transitions:
     * - 0.0 for plateaus (flat ocean floors, flat mountain peaks)
     * - 0.5-1.0 for gentle transitions (coastal areas, plains)
     * - 1.0-1.5 for moderate slopes (inland transitions)
     */
    private MultiDimensionalSpline buildOffsetSpline(long seed) {
        MultiDimensionalSpline continentalnessSpline = new MultiDimensionalSpline();

        // Deep Ocean (continentalness = -1.0 to -0.6)
        // Low derivative for flat ocean floor
        continentalnessSpline.addPoint(-1.0f, buildErosionSpline(30.0f, 40.0f), 0.3f);
        continentalnessSpline.addPoint(-0.6f, buildErosionSpline(40.0f, 50.0f), 0.8f);

        // Ocean/Coast (continentalness = -0.6 to -0.2)
        // Moderate derivative for coastal transitions
        continentalnessSpline.addPoint(-0.2f, buildErosionSpline(50.0f, 63.0f), 1.0f);

        // Near Inland (continentalness = -0.2 to 0.0)
        continentalnessSpline.addPoint(0.0f, buildErosionSpline(63.0f, 70.0f), 0.8f);

        // Inland Low (continentalness = 0.0 to 0.3)
        continentalnessSpline.addPoint(0.3f, buildErosionSpline(70.0f, 90.0f), 1.0f);

        // Inland Mid (continentalness = 0.3 to 0.6)
        continentalnessSpline.addPoint(0.6f, buildErosionSpline(90.0f, 120.0f), 1.2f);

        // Inland High (continentalness = 0.6 to 0.8)
        // Higher derivative for mountain rise, but not too steep
        continentalnessSpline.addPoint(0.8f, buildErosionSpline(120.0f, 150.0f), 1.5f);

        // Extreme Inland (continentalness = 0.8 to 1.0)
        // Zero derivative for plateau top
        continentalnessSpline.addPoint(1.0f, buildErosionSpline(150.0f, 190.0f), 0.0f);

        return continentalnessSpline;
    }

    /**
     * Build erosion sub-spline for a given continentalness level
     *
     * The erosion parameter controls terrain flatness:
     * - Low erosion (-1.0) = mountainous terrain (uses maxHeight + peaks)
     * - High erosion (1.0) = flat plains (uses baseHeight with minimal variation)
     *
     * Derivatives tuned for smooth transitions:
     * - derivative = 0.0 creates plateaus (flat mountain peaks, flat plains)
     * - derivative = 0.5-1.0 creates smooth rolling transitions
     * - derivative = 1.0-1.2 creates moderate slopes
     *
     * @param baseHeight Minimum height for this continentalness zone (used at high erosion)
     * @param maxHeight Maximum height for this continentalness zone (used at low erosion)
     */
    private MultiDimensionalSpline buildErosionSpline(float baseHeight, float maxHeight) {
        MultiDimensionalSpline erosionSpline = new MultiDimensionalSpline();

        // Calculate intermediate heights for smooth transitions
        float heightRange = maxHeight - baseHeight;

        // Low erosion = mountains (keep high)
        // Zero derivative for flat mountain peak tops
        erosionSpline.addPoint(-1.0f, buildPVSpline(maxHeight, maxHeight + 20.0f), 0.0f);

        // High foothills (NEW: adds intermediate elevation)
        // Gentle derivative for gradual mountain descent
        erosionSpline.addPoint(-0.7f, buildPVSpline(maxHeight - heightRange * 0.15f, maxHeight + 10.0f), 0.8f);

        // Medium foothills (NEW: more intermediate elevation)
        // Moderate derivative for continued gentle slope
        erosionSpline.addPoint(-0.5f, buildPVSpline(maxHeight - heightRange * 0.3f, maxHeight), 1.0f);

        // Mid-low erosion = hills
        // Moderate derivative for smooth hill transitions
        erosionSpline.addPoint(-0.3f, buildPVSpline(baseHeight + 30.0f, maxHeight - heightRange * 0.2f), 1.2f);

        // Mid erosion = rolling terrain
        // Gentle derivative for rolling hills
        erosionSpline.addPoint(0.0f, buildPVSpline(baseHeight + 10.0f, baseHeight + 30.0f), 1.0f);

        // Mid-high erosion = gentle hills
        // Smooth derivative for gentle transitions
        erosionSpline.addPoint(0.5f, buildPVSpline(baseHeight, baseHeight + 15.0f), 0.8f);

        // High erosion = flat plains
        // Zero derivative for very flat plains
        erosionSpline.addPoint(1.0f, buildPVSpline(baseHeight - 5.0f, baseHeight + 5.0f), 0.0f);

        return erosionSpline;
    }

    /**
     * Build peaks & valleys sub-spline
     *
     * The PV (Peaks & Valleys) parameter amplifies height extremes within each terrain zone:
     * - PV = -1.0 (valleys) uses valleyHeight (lower elevation)
     * - PV = 0.0 (neutral) uses average of valley and peak heights
     * - PV = 1.0 (peaks) uses peakHeight (higher elevation)
     *
     * Derivatives = 0.8-1.0 create smooth, natural rolling terrain.
     * Lower derivatives prevent abrupt height changes between valleys and peaks.
     *
     * @param valleyHeight Height in valleys (PV = -1)
     * @param peakHeight Height on peaks (PV = 1)
     */
    private MultiDimensionalSpline buildPVSpline(float valleyHeight, float peakHeight) {
        MultiDimensionalSpline pvSpline = new MultiDimensionalSpline();

        // Valleys (PV = -1)
        // Smooth derivative for gentle valley floors
        pvSpline.addPoint(-1.0f, valleyHeight, 0.8f);

        // Neutral (PV = 0)
        // Moderate derivative for rolling mid-ground
        float midHeight = (valleyHeight + peakHeight) / 2.0f;
        pvSpline.addPoint(0.0f, midHeight, 1.0f);

        // Peaks (PV = 1)
        // Smooth derivative for gentle peak tops
        pvSpline.addPoint(1.0f, peakHeight, 0.8f);

        return pvSpline;
    }
}
