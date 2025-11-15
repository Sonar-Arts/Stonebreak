package com.stonebreak.world.generation.spline;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfig;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;

/**
 * Jaggedness Spline Router - Defines high-frequency peak variation
 *
 * ENHANCED VERSION (Phase 1.2)
 * - 10 continentalness control points (ocean to extreme spires)
 * - 5 erosion control points (sharp peaks to smooth plains)
 * - 3 PV control points (valleys to peaks)
 * - Weirdness scaling (1.5x multiplier for needle-like peaks)
 * - Extreme derivatives (4.0-5.0) for dramatic spire transitions
 *
 * Total control points: 10 × 5 × 3 = 150 points
 *
 * Key characteristics:
 * - High values in mountains (jagged peaks up to 20 blocks)
 * - Low values in plains (smooth terrain, 0 jaggedness)
 * - Scales with erosion (less jagged = more eroded)
 * - Weirdness creates needle-like spires at extreme values
 *
 * TODO: Distance-from-summit scaling requires Y coordinate access
 * (would need architectural change in SplineTerrainGenerator)
 */
public class JaggednessSplineRouter {

    private final MultiDimensionalSpline jaggednessSpline;
    private final NoiseGenerator jaggednessNoise;

    public JaggednessSplineRouter(long seed) {
        this.jaggednessSpline = buildJaggednessSpline(seed);
        // High-frequency noise for peaks - use fewer octaves and higher frequency
        NoiseConfig config = new NoiseConfig(3, 0.5, 2.0);
        this.jaggednessNoise = new NoiseGenerator(seed + 456, config);
    }

    /**
     * Get jaggedness value for given parameters and position
     *
     * Returns a height offset that adds sharp peaks
     *
     * ENHANCED: Includes weirdness scaling for extreme spires
     */
    public float getJaggedness(MultiNoiseParameters params, int x, int z) {
        // Get jaggedness strength from spline (0 to ~20 blocks)
        float strength = jaggednessSpline.sample(
            params.continentalness,
            params.erosion,
            params.peaksValleys
        );

        // PHASE 1.2: Weirdness scaling
        // High weirdness (0.6 to 1.0) creates needle-like spires
        float weirdnessMultiplier = 1.0f;
        if (params.weirdness > 0.6f) {
            // Scale from 1.0x to 1.5x as weirdness goes from 0.6 to 1.0
            float weirdStrength = (params.weirdness - 0.6f) / 0.4f; // 0 to 1
            weirdnessMultiplier = 1.0f + (weirdStrength * 0.5f); // 1.0 to 1.5
        }

        // Sample high-frequency noise for peaks
        // Use higher frequency than terrain offset (8x frequency)
        float noise = jaggednessNoise.noise(x / 8.0f, z / 8.0f);

        // Apply strength and weirdness multiplier to noise (noise is -1 to 1)
        return noise * strength * weirdnessMultiplier;
    }

    /**
     * Build the jaggedness spline
     *
     * Structure: continentalness → erosion → PV → jaggedness_strength
     *
     * ENHANCED: 10 continentalness points for fine-grained control
     * - Ocean areas: 0.0 jaggedness (smooth ocean floor)
     * - Coastal: 0.0 jaggedness (smooth beaches)
     * - Beach cliffs: 0.5 jaggedness (small cliff details)
     * - Low hills: 1.0 jaggedness
     * - Rolling hills: 2.0 jaggedness
     * - Moderate peaks: 4.0 jaggedness
     * - Sharp peaks: 8.0 jaggedness
     * - Very sharp peaks: 12.0 jaggedness
     * - Extreme spires: 16.0 jaggedness
     * - Mountain peaks: 20.0 jaggedness
     */
    private MultiDimensionalSpline buildJaggednessSpline(long seed) {
        MultiDimensionalSpline continentalnessSpline = new MultiDimensionalSpline();

        // Ocean (continentalness = -1.00)
        // No jaggedness, derivative 0.0 for clear separation
        continentalnessSpline.addPoint(-1.00f, buildErosionJaggedness(0.0f, 0.0f), 0.0f);

        // Coastal (continentalness = -0.40)
        // No jaggedness, derivative 0.0 for smooth beaches
        continentalnessSpline.addPoint(-0.40f, buildErosionJaggedness(0.0f, 0.0f), 0.0f);

        // Beach Cliffs (continentalness = -0.10)
        // Minimal jaggedness, derivative 2.0 for rising detail
        continentalnessSpline.addPoint(-0.10f, buildErosionJaggedness(0.5f, 1.0f), 2.0f);

        // Low Hills (continentalness = 0.20)
        // Low jaggedness, derivative 1.5 for gentle increase
        continentalnessSpline.addPoint(0.20f, buildErosionJaggedness(1.0f, 2.5f), 1.5f);

        // Rolling Hills (continentalness = 0.40)
        // Moderate jaggedness, derivative 1.0 for steady rise
        continentalnessSpline.addPoint(0.40f, buildErosionJaggedness(2.0f, 5.0f), 1.0f);

        // Moderate Peaks (continentalness = 0.55)
        // Increased jaggedness, derivative 2.0 for sharp rise
        continentalnessSpline.addPoint(0.55f, buildErosionJaggedness(4.0f, 8.0f), 2.0f);

        // Sharp Peaks (continentalness = 0.70)
        // High jaggedness, derivative 3.0 for dramatic peaks
        continentalnessSpline.addPoint(0.70f, buildErosionJaggedness(8.0f, 12.0f), 3.0f);

        // Very Sharp Peaks (continentalness = 0.85)
        // Very high jaggedness, derivative 4.0 for extreme transitions
        continentalnessSpline.addPoint(0.85f, buildErosionJaggedness(12.0f, 16.0f), 4.0f);

        // Extreme Spires (continentalness = 0.95)
        // Extreme jaggedness, derivative 5.0 for needle-like spires
        continentalnessSpline.addPoint(0.95f, buildErosionJaggedness(16.0f, 20.0f), 5.0f);

        // Mountain Peaks (continentalness = 1.00)
        // Maximum jaggedness, derivative 2.0 for peak tops
        continentalnessSpline.addPoint(1.00f, buildErosionJaggedness(20.0f, 22.0f), 2.0f);

        return continentalnessSpline;
    }

    /**
     * Build erosion sub-spline for jaggedness
     *
     * Erosion controls how jagged terrain appears:
     * - Low erosion (-1.0) = sharp, jagged mountain peaks (uses maxJaggedness)
     * - High erosion (1.0) = smooth, rounded plains (uses 0.0 jaggedness)
     *
     * The relationship mirrors real-world erosion: uneroded mountains are sharp and
     * jagged, while heavily eroded terrain is smooth and gentle.
     *
     * Derivatives control transition smoothness:
     * - derivative = 0.0 at peaks/plains creates clear separation
     * - derivative = 1.0-1.5 on slopes creates gradual jaggedness changes
     *
     * @param baseJaggedness Minimum jaggedness strength (for eroded terrain)
     * @param maxJaggedness Maximum jaggedness strength (for mountainous terrain)
     */
    private MultiDimensionalSpline buildErosionJaggedness(float baseJaggedness, float maxJaggedness) {
        MultiDimensionalSpline erosionSpline = new MultiDimensionalSpline();

        // Low erosion = very jagged (sharp peaks)
        // Derivative 0.0 for clear peak definition
        erosionSpline.addPoint(-1.0f, buildPVJaggedness(maxJaggedness), 0.0f);

        // Mid-low erosion = jagged
        // Derivative 1.5 for moderate transition
        erosionSpline.addPoint(-0.3f, buildPVJaggedness(baseJaggedness + (maxJaggedness - baseJaggedness) * 0.7f), 1.5f);

        // Mid erosion = moderate
        // Derivative 1.0 for gentle slope
        erosionSpline.addPoint(0.0f, buildPVJaggedness(baseJaggedness + (maxJaggedness - baseJaggedness) * 0.4f), 1.0f);

        // Mid-high erosion = low jaggedness
        // Derivative 0.5 for smoothing transition
        erosionSpline.addPoint(0.5f, buildPVJaggedness(baseJaggedness * 0.5f), 0.5f);

        // High erosion = no jaggedness (smooth plains)
        // Derivative 0.0 for flat plains
        erosionSpline.addPoint(1.0f, buildPVJaggedness(0.0f), 0.0f);

        return erosionSpline;
    }

    /**
     * Build peaks & valleys sub-spline for jaggedness
     *
     * Peaks & Valleys parameter amplifies jaggedness based on terrain position:
     * - PV = -1.0 (valleys) get 0.5x jaggedness (smoother valley floors)
     * - PV = 0.0 (neutral) get 1.0x jaggedness (standard)
     * - PV = 1.0 (peaks) get 1.5x jaggedness (sharper mountain peaks)
     *
     * This creates a natural appearance where mountain peaks are sharpest,
     * valley floors are smoothest, and slopes have intermediate detail.
     *
     * @param baseJaggedness Base jaggedness strength to scale by PV
     */
    private MultiDimensionalSpline buildPVJaggedness(float baseJaggedness) {
        MultiDimensionalSpline pvSpline = new MultiDimensionalSpline();

        // Valleys: less jaggedness (smoother valley floors)
        // Derivative 1.0 for gentle valley transitions
        pvSpline.addPoint(-1.0f, baseJaggedness * 0.5f, 1.0f);

        // Neutral: standard jaggedness
        // Derivative 1.5 for moderate slope variation
        pvSpline.addPoint(0.0f, baseJaggedness, 1.5f);

        // Peaks: more jaggedness (amplify sharp peaks)
        // Derivative 1.0 for peak sharpness
        pvSpline.addPoint(1.0f, baseJaggedness * 1.5f, 1.0f);

        return pvSpline;
    }
}
