package com.stonebreak.world.generation.spline;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfig;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;

/**
 * Jaggedness Spline Router - Defines high-frequency peak variation
 *
 * This spline adds detail and sharpness to mountain peaks. It samples
 * high-frequency noise and scales it based on terrain parameters.
 *
 * Key characteristics:
 * - High values in mountains (jagged peaks)
 * - Low values in plains (smooth terrain)
 * - Scales with erosion (less jagged = more eroded)
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
     */
    public float getJaggedness(MultiNoiseParameters params, int x, int z) {
        // Get jaggedness strength from spline (0 to ~15 blocks)
        float strength = jaggednessSpline.sample(
            params.continentalness,
            params.erosion,
            params.peaksValleys
        );

        // Sample high-frequency noise for peaks
        // Use higher frequency than terrain offset (8x frequency)
        float noise = jaggednessNoise.noise(x / 8.0f, z / 8.0f);

        // Apply strength to noise (noise is -1 to 1)
        return noise * strength;
    }

    /**
     * Build the jaggedness spline
     *
     * Structure: continentalness → erosion → PV → jaggedness_strength
     */
    private MultiDimensionalSpline buildJaggednessSpline(long seed) {
        MultiDimensionalSpline continentalnessSpline = new MultiDimensionalSpline();

        // Ocean areas: no jaggedness
        continentalnessSpline.addPoint(-1.0f, buildErosionJaggedness(0.0f, 0.0f), 0.0f);
        continentalnessSpline.addPoint(-0.5f, buildErosionJaggedness(0.0f, 0.5f), 0.2f);

        // Coastal/near inland: minimal jaggedness
        continentalnessSpline.addPoint(0.0f, buildErosionJaggedness(0.5f, 2.0f), 0.5f);

        // Inland: moderate jaggedness
        continentalnessSpline.addPoint(0.4f, buildErosionJaggedness(2.0f, 6.0f), 1.0f);

        // High inland: high jaggedness
        continentalnessSpline.addPoint(0.7f, buildErosionJaggedness(6.0f, 12.0f), 2.0f);

        // Extreme inland (mountains): maximum jaggedness
        continentalnessSpline.addPoint(1.0f, buildErosionJaggedness(10.0f, 15.0f), 0.0f);  // Plateau

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
        erosionSpline.addPoint(-1.0f, buildPVJaggedness(maxJaggedness), 0.0f);

        // Mid-low erosion = jagged
        erosionSpline.addPoint(-0.3f, buildPVJaggedness(baseJaggedness + (maxJaggedness - baseJaggedness) * 0.7f), 1.5f);

        // Mid erosion = moderate
        erosionSpline.addPoint(0.0f, buildPVJaggedness(baseJaggedness + (maxJaggedness - baseJaggedness) * 0.4f), 1.0f);

        // Mid-high erosion = low jaggedness
        erosionSpline.addPoint(0.5f, buildPVJaggedness(baseJaggedness * 0.5f), 0.5f);

        // High erosion = no jaggedness (smooth plains)
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

        // Valleys: less jaggedness
        pvSpline.addPoint(-1.0f, baseJaggedness * 0.5f, 1.0f);

        // Neutral
        pvSpline.addPoint(0.0f, baseJaggedness, 1.5f);

        // Peaks: more jaggedness (amplify sharp peaks)
        pvSpline.addPoint(1.0f, baseJaggedness * 1.5f, 1.0f);

        return pvSpline;
    }
}
