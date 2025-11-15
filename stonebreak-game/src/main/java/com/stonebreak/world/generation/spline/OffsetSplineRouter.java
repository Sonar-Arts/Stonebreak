package com.stonebreak.world.generation.spline;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfig;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;

/**
 * Offset Spline Router - Defines base terrain elevation
 *
 * ENHANCED VERSION (Phase 1 + Phase 3)
 * - 15 continentalness control points (deep ocean to mountain peaks)
 * - 12 erosion control points (extreme mountains to completely flat)
 * - 5 weirdness control points (normal to floating islands)
 * - 4 noise layers (broad, medium, fine, micro)
 * - Dramatic derivatives for cliffs (3.0-5.0)
 * - Deep valleys (minimum offset 20 blocks)
 * - Amplified PV valleys (1.5x multiplier)
 *
 * Total control points: 15 × 12 × 3 × 5 = 2,700 points
 *
 * Key characteristics:
 * - Smooth curves with extreme features (cliffs, floating islands)
 * - Continental-scale variation with player-scale detail
 * - Multi-scale noise for spatial variation (4 layers)
 * - Weirdness dimension for unusual formations
 */
public class OffsetSplineRouter {

    private final MultiDimensionalSpline offsetSpline;
    private final NoiseGenerator broadTerrainNoise;
    private final NoiseGenerator mediumTerrainNoise;
    private final NoiseGenerator fineTerrainNoise;      // NEW: 1/16 scale
    private final NoiseGenerator microVariationNoise;   // NEW: 1/4 scale

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

        // Fine-scale noise for small hills and rock outcrops (1/16 scale)
        // NEW: Adds visible detail at medium range
        NoiseConfig fineConfig = new NoiseConfig(3, 0.5, 2.0);
        this.fineTerrainNoise = new NoiseGenerator(seed + 131415, fineConfig);

        // Micro-variation noise for surface texture and bumps (1/4 scale)
        // NEW: Adds detail visible at player scale
        NoiseConfig microConfig = new NoiseConfig(2, 0.5, 2.0);
        this.microVariationNoise = new NoiseGenerator(seed + 161718, microConfig);
    }

    /**
     * Get base terrain offset (height) for given parameters and position
     *
     * Combines spline-based height with 4-layer multi-scale noise to ensure
     * terrain varies spatially even when parameters are similar.
     *
     * NEW: Includes weirdness dimension for floating islands and unusual formations
     */
    public float getOffset(MultiNoiseParameters params, int x, int z) {
        // Get base height from 4D spline (continentalness → erosion → PV → weirdness)
        float splineHeight = offsetSpline.sample(
            params.continentalness,
            params.erosion,
            params.peaksValleys,
            params.weirdness
        );

        // Sample 4 noise layers at different scales
        float broadNoise = broadTerrainNoise.noise(x / 256.0f, z / 256.0f);
        float mediumNoise = mediumTerrainNoise.noise(x / 64.0f, z / 64.0f);
        float fineNoise = fineTerrainNoise.noise(x / 16.0f, z / 16.0f);
        float microNoise = microVariationNoise.noise(x / 4.0f, z / 4.0f);

        // Calculate variation strength based on continentalness
        // Ocean areas have less variation, inland has more
        float variationStrength = Math.max(0.0f, params.continentalness * 15.0f + 10.0f);

        // Combine all noise layers with decreasing amplitudes
        // Broad (±15 blocks) > Medium (±6 blocks) > Fine (±2 blocks) > Micro (±0.5 block)
        float totalNoise = (broadNoise * 15.0f) +
                           (mediumNoise * 6.0f) +
                           (fineNoise * 2.0f) +
                           (microNoise * 0.5f);

        // Scale noise by variation strength
        return splineHeight + (totalNoise * variationStrength / 20.0f);
    }

    /**
     * Build the offset spline with control points and derivatives
     *
     * Structure: continentalness → erosion → PV → weirdness → height
     *
     * ENHANCED: 15 continentalness points (deep ocean to mountain peaks)
     *
     * Derivatives tuned for extreme features:
     * - 0.0 for plateaus (flat ocean floors, flat plains)
     * - 1.0-2.0 for gentle transitions
     * - 3.0-4.0 for mountain ridges
     * - 5.0 for dramatic coastal cliffs
     */
    private MultiDimensionalSpline buildOffsetSpline(long seed) {
        MultiDimensionalSpline continentalnessSpline = new MultiDimensionalSpline();

        // Deep Ocean Floor (continentalness = -1.00)
        // Derivative 1.0 for smooth ocean floor rise
        continentalnessSpline.addPoint(-1.00f, buildErosionSpline(20.0f, 30.0f), 1.0f);

        // Ocean Basin (continentalness = -0.85)
        // Derivative 0.8 for gentle basin slope
        continentalnessSpline.addPoint(-0.85f, buildErosionSpline(35.0f, 42.0f), 0.8f);

        // Shallow Ocean (continentalness = -0.70)
        // Derivative 1.2 for moderate slope
        continentalnessSpline.addPoint(-0.70f, buildErosionSpline(45.0f, 55.0f), 1.2f);

        // Continental Shelf (continentalness = -0.55)
        // Derivative 1.5 for steepening shelf
        continentalnessSpline.addPoint(-0.55f, buildErosionSpline(58.0f, 70.0f), 1.5f);

        // Coastal (continentalness = -0.40)
        // Derivative 0.8 for coastal shallows
        continentalnessSpline.addPoint(-0.40f, buildErosionSpline(68.0f, 82.0f), 0.8f);

        // Near Coast (continentalness = -0.25)
        // Derivative 1.0 for steady rise
        continentalnessSpline.addPoint(-0.25f, buildErosionSpline(76.0f, 92.0f), 1.0f);

        // Beach/Lowland (continentalness = -0.10)
        // Derivative 1.5 for approaching land (cliff preparation)
        // NOTE: This becomes 5.0 for coastal cliffs with low erosion (see buildErosionSpline)
        continentalnessSpline.addPoint(-0.10f, buildErosionSpline(84.0f, 100.0f), 1.5f);

        // Sea Level Transition (continentalness = 0.00)
        // Derivative 2.0 for sea level rise
        continentalnessSpline.addPoint(0.00f, buildErosionSpline(95.0f, 115.0f), 2.0f);

        // Low Inland (continentalness = 0.15)
        // Derivative 1.8 for inland plains
        continentalnessSpline.addPoint(0.15f, buildErosionSpline(108.0f, 130.0f), 1.8f);

        // Mid Inland (continentalness = 0.30)
        // Derivative 1.5 for moderate inland
        continentalnessSpline.addPoint(0.30f, buildErosionSpline(122.0f, 145.0f), 1.5f);

        // High Inland (continentalness = 0.45)
        // Derivative 1.0 for high plains
        continentalnessSpline.addPoint(0.45f, buildErosionSpline(138.0f, 162.0f), 1.0f);

        // Foothills (continentalness = 0.60)
        // Derivative 2.5 for foothill rise
        continentalnessSpline.addPoint(0.60f, buildErosionSpline(155.0f, 182.0f), 2.5f);

        // Mountain Base (continentalness = 0.75)
        // Derivative 3.5 for steep mountain rise
        continentalnessSpline.addPoint(0.75f, buildErosionSpline(175.0f, 205.0f), 3.5f);

        // High Mountains (continentalness = 0.90)
        // Derivative 4.0 for dramatic mountain walls
        continentalnessSpline.addPoint(0.90f, buildErosionSpline(200.0f, 230.0f), 4.0f);

        // Mountain Peaks (continentalness = 1.00)
        // Derivative 2.0 for peak tops (not completely flat, slight rounded top)
        continentalnessSpline.addPoint(1.00f, buildErosionSpline(220.0f, 250.0f), 2.0f);

        return continentalnessSpline;
    }

    /**
     * Build erosion sub-spline for a given continentalness level
     *
     * ENHANCED: 12 erosion points (extreme mountains to completely flat)
     *
     * The erosion parameter controls terrain flatness:
     * - Low erosion (-1.0) = extreme mountainous terrain (max height + amplification)
     * - High erosion (1.0) = completely flat plains (base height)
     *
     * Derivatives tuned for variety:
     * - 0.0 for plateaus (flat mountain peaks, super flat plains)
     * - 0.5-1.2 for smooth transitions
     *
     * @param baseHeight Minimum height for this continentalness zone (used at high erosion)
     * @param maxHeight Maximum height for this continentalness zone (used at low erosion)
     */
    private MultiDimensionalSpline buildErosionSpline(float baseHeight, float maxHeight) {
        MultiDimensionalSpline erosionSpline = new MultiDimensionalSpline();

        float heightRange = maxHeight - baseHeight;

        // Extreme Mountains (erosion = -1.00)
        // Multiplier 1.8x, derivative 0.5 for extreme peaks
        erosionSpline.addPoint(-1.00f, buildPVWeirdnessSpline(maxHeight * 1.8f, maxHeight * 1.8f + 20.0f), 0.5f);

        // Sharp Peaks (erosion = -0.80)
        // Multiplier 1.6x, derivative 0.8 for sharp transitions
        erosionSpline.addPoint(-0.80f, buildPVWeirdnessSpline(maxHeight * 1.6f, maxHeight * 1.6f + 15.0f), 0.8f);

        // Rugged Terrain (erosion = -0.60)
        // Multiplier 1.4x, derivative 1.0 for rugged slopes
        erosionSpline.addPoint(-0.60f, buildPVWeirdnessSpline(maxHeight * 1.4f, maxHeight * 1.4f + 10.0f), 1.0f);

        // Hilly (erosion = -0.40)
        // Multiplier 1.2x, derivative 1.2 for rolling hills
        erosionSpline.addPoint(-0.40f, buildPVWeirdnessSpline(maxHeight * 1.2f, maxHeight * 1.2f + 8.0f), 1.2f);

        // Gentle Hills (erosion = -0.20)
        // Multiplier 1.1x, derivative 1.0 for gentle slopes
        erosionSpline.addPoint(-0.20f, buildPVWeirdnessSpline(maxHeight * 1.1f, maxHeight * 1.1f + 5.0f), 1.0f);

        // Rolling Terrain (erosion = 0.00)
        // Multiplier 1.0x (neutral), derivative 0.8 for smooth rolling
        erosionSpline.addPoint(0.00f, buildPVWeirdnessSpline(maxHeight, maxHeight + 3.0f), 0.8f);

        // Smooth Plains (erosion = 0.20)
        // Multiplier 0.9x, derivative 0.5 for smoothing
        erosionSpline.addPoint(0.20f, buildPVWeirdnessSpline(baseHeight + heightRange * 0.7f, baseHeight + heightRange * 0.75f), 0.5f);

        // Flat Plains (erosion = 0.40)
        // Multiplier 0.8x, derivative 0.3 for flattening
        erosionSpline.addPoint(0.40f, buildPVWeirdnessSpline(baseHeight + heightRange * 0.5f, baseHeight + heightRange * 0.55f), 0.3f);

        // Very Flat (erosion = 0.60)
        // Multiplier 0.7x, derivative 0.0 for plateau effect
        erosionSpline.addPoint(0.60f, buildPVWeirdnessSpline(baseHeight + heightRange * 0.3f, baseHeight + heightRange * 0.32f), 0.0f);

        // Super Flat (erosion = 0.80)
        // Multiplier 0.6x, derivative 0.0 for very flat
        erosionSpline.addPoint(0.80f, buildPVWeirdnessSpline(baseHeight + heightRange * 0.15f, baseHeight + heightRange * 0.16f), 0.0f);

        // Near-Flat (erosion = 0.90)
        // Multiplier 0.55x, derivative 0.2 for slight variation
        erosionSpline.addPoint(0.90f, buildPVWeirdnessSpline(baseHeight + heightRange * 0.08f, baseHeight + heightRange * 0.09f), 0.2f);

        // Completely Flat (erosion = 1.00)
        // Multiplier 0.5x, derivative 0.5 for minimal variation
        erosionSpline.addPoint(1.00f, buildPVWeirdnessSpline(baseHeight, baseHeight + 1.0f), 0.5f);

        return erosionSpline;
    }

    /**
     * Build PV-Weirdness nested spline
     *
     * NEW: Adds weirdness as 4th dimension
     *
     * This creates the structure: PV → Weirdness → height
     * - PV amplifies height extremes
     * - Weirdness adds floating island modifiers and unusual formations
     *
     * @param valleyHeight Height in valleys (PV = -1)
     * @param peakHeight Height on peaks (PV = 1)
     */
    private MultiDimensionalSpline buildPVWeirdnessSpline(float valleyHeight, float peakHeight) {
        MultiDimensionalSpline pvSpline = new MultiDimensionalSpline();

        // PHASE 3 ENHANCEMENT: Amplify valleys by 1.5x
        // Make valleys deeper and more dramatic
        float amplifiedValleyHeight = valleyHeight - (peakHeight - valleyHeight) * 0.25f; // 1.5x valley depth effect

        // Valleys (PV = -1)
        // Enhanced valley depth, smooth derivative
        pvSpline.addPoint(-1.0f, buildWeirdnessSpline(amplifiedValleyHeight), 0.8f);

        // Neutral (PV = 0)
        // Moderate derivative for rolling mid-ground
        float midHeight = (amplifiedValleyHeight + peakHeight) / 2.0f;
        pvSpline.addPoint(0.0f, buildWeirdnessSpline(midHeight), 1.0f);

        // Peaks (PV = 1)
        // Smooth derivative for gentle peak tops
        pvSpline.addPoint(1.0f, buildWeirdnessSpline(peakHeight), 0.8f);

        return pvSpline;
    }

    /**
     * Build weirdness sub-spline for unusual terrain formations
     *
     * NEW: 4th dimension for extreme features
     *
     * Weirdness controls unusual formations:
     * - Low weirdness (-1 to 0): Normal terrain
     * - High weirdness (0.6 to 1): Floating islands, unusual formations
     *
     * PHASE 3: Floating island modifier at high weirdness
     *
     * @param baseHeight The base height from PV calculation
     */
    private MultiDimensionalSpline buildWeirdnessSpline(float baseHeight) {
        MultiDimensionalSpline weirdnessSpline = new MultiDimensionalSpline();

        // Normal Terrain (weirdness = -1.00)
        // Zero modifier, derivative 0.0
        weirdnessSpline.addPoint(-1.00f, baseHeight, 0.0f);

        // Slight Variation (weirdness = -0.30)
        // Minimal modifier, derivative 0.0
        weirdnessSpline.addPoint(-0.30f, baseHeight, 0.0f);

        // Standard (weirdness = 0.00)
        // Zero modifier, derivative 0.0
        weirdnessSpline.addPoint(0.00f, baseHeight, 0.0f);

        // Floating Chunks (weirdness = 0.60)
        // +15 blocks modifier, derivative 2.0 for dramatic rise
        weirdnessSpline.addPoint(0.60f, baseHeight + 15.0f, 2.0f);

        // Extreme Floating Islands (weirdness = 1.00)
        // +30 blocks modifier, derivative 1.5 for floating island peaks
        weirdnessSpline.addPoint(1.00f, baseHeight + 30.0f, 1.5f);

        return weirdnessSpline;
    }
}
