package com.stonebreak.world.generation.spline;

import com.stonebreak.world.generation.noise.MultiNoiseParameters;

/**
 * Factor Spline Router - Defines 3D noise strength for caves/overhangs
 *
 * This spline controls how much 3D noise influences terrain density.
 * Higher factor = more 3D variation (caves, overhangs, arches).
 * Lower factor = solid terrain.
 *
 * Key characteristics:
 * - Low in oceans (solid water floor)
 * - Low in plains (mostly solid)
 * - High in mountains (natural caves/overhangs)
 */
public class FactorSplineRouter {

    private final MultiDimensionalSpline factorSpline;

    public FactorSplineRouter(long seed) {
        this.factorSpline = buildFactorSpline(seed);
    }

    /**
     * Get 3D noise factor for given parameters
     *
     * Returns multiplier for 3D density noise (0.0 to 1.0)
     */
    public float getFactor(MultiNoiseParameters params) {
        return factorSpline.sample(
            params.continentalness,
            params.erosion,
            params.weirdness
        );
    }

    /**
     * Build the factor spline
     *
     * Structure: continentalness → erosion → weirdness → factor
     */
    private MultiDimensionalSpline buildFactorSpline(long seed) {
        MultiDimensionalSpline continentalnessSpline = new MultiDimensionalSpline();

        // Ocean areas: very low factor (solid ocean floor)
        continentalnessSpline.addPoint(-1.0f, buildErosionFactor(0.0f, 0.1f), 0.0f);
        continentalnessSpline.addPoint(-0.5f, buildErosionFactor(0.1f, 0.2f), 0.3f);

        // Coastal/near inland: low factor
        continentalnessSpline.addPoint(0.0f, buildErosionFactor(0.2f, 0.4f), 0.5f);

        // Inland: moderate factor
        continentalnessSpline.addPoint(0.4f, buildErosionFactor(0.4f, 0.6f), 0.8f);

        // High inland: high factor (more caves)
        continentalnessSpline.addPoint(0.7f, buildErosionFactor(0.6f, 0.8f), 1.0f);

        // Extreme inland (mountains): maximum factor (lots of caves/overhangs)
        continentalnessSpline.addPoint(1.0f, buildErosionFactor(0.7f, 1.0f), 0.0f);

        return continentalnessSpline;
    }

    /**
     * Build erosion sub-spline for factor
     *
     * Erosion determines cave density:
     * - Low erosion (-1.0) = mountains = high factor (more caves and overhangs)
     * - High erosion (1.0) = plains = low factor (mostly solid terrain)
     *
     * Mountains naturally have more caves due to:
     * 1. Greater vertical extent (more space for cave systems)
     * 2. Steeper terrain allowing natural overhangs
     * 3. More dramatic 3D noise features
     *
     * Plains have fewer caves to maintain solid, walkable surfaces.
     *
     * @param baseFactor Minimum factor (for plains/flat terrain)
     * @param maxFactor Maximum factor (for mountains)
     */
    private MultiDimensionalSpline buildErosionFactor(float baseFactor, float maxFactor) {
        MultiDimensionalSpline erosionSpline = new MultiDimensionalSpline();

        // Low erosion = mountains = high factor
        erosionSpline.addPoint(-1.0f, buildWeirdnessFactor(maxFactor), 0.0f);

        // Mid erosion
        erosionSpline.addPoint(0.0f, buildWeirdnessFactor((baseFactor + maxFactor) / 2.0f), 1.0f);

        // High erosion = plains = low factor
        erosionSpline.addPoint(1.0f, buildWeirdnessFactor(baseFactor), 0.0f);

        return erosionSpline;
    }

    /**
     * Build weirdness sub-spline for factor
     *
     * Weirdness amplifies cave density and creates unusual terrain formations:
     * - Low weirdness (-1.0) = 0.8x factor (fewer caves, standard terrain)
     * - Neutral weirdness (0.0) = 1.0x factor (normal cave density)
     * - High weirdness (1.0) = 1.3x factor (more caves, arches, floating islands)
     *
     * High weirdness areas feature:
     * - More frequent cave openings
     * - Natural arches and bridges
     * - Overhanging cliffs
     * - Swiss-cheese terrain (if factor is already high)
     *
     * Factor is clamped to 1.0 max to prevent excessive floating terrain.
     *
     * @param baseFactor Base factor to amplify by weirdness
     */
    private MultiDimensionalSpline buildWeirdnessFactor(float baseFactor) {
        MultiDimensionalSpline weirdnessSpline = new MultiDimensionalSpline();

        // Low weirdness: standard factor
        weirdnessSpline.addPoint(-1.0f, baseFactor * 0.8f, 0.5f);

        // Neutral weirdness
        weirdnessSpline.addPoint(0.0f, baseFactor, 1.0f);

        // High weirdness: amplify factor (more caves/overhangs)
        weirdnessSpline.addPoint(1.0f, Math.min(1.0f, baseFactor * 1.3f), 0.5f);

        return weirdnessSpline;
    }
}
