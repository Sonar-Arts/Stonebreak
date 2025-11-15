package com.stonebreak.world.generation.spline;

import com.stonebreak.world.generation.noise.MultiNoiseParameters;

/**
 * Factor Spline Router - Defines 3D noise strength for caves/overhangs
 *
 * ENHANCED VERSION (Phase 1.3)
 * - Swiss-cheese mountain logic (2.5x factor at extreme weirdness + mountains)
 * - Enhanced weirdness amplification for dramatic cave systems
 * - Tuned derivatives for smooth cave density transitions
 *
 * This spline controls how much 3D noise influences terrain density.
 * Higher factor = more 3D variation (caves, overhangs, arches).
 * Lower factor = solid terrain.
 *
 * Key characteristics:
 * - Low in oceans (solid water floor)
 * - Low in plains (mostly solid)
 * - High in mountains (natural caves/overhangs)
 * - EXTREME in weird mountains (Swiss-cheese effect)
 *
 * NOTE: Elevation-based cave density (more caves at Y=40-120) will be
 * implemented in TerrainDensityFunction (Phase 2) where Y coordinates are available.
 */
public class FactorSplineRouter {

    private final MultiDimensionalSpline factorSpline;

    public FactorSplineRouter(long seed) {
        this.factorSpline = buildFactorSpline(seed);
    }

    /**
     * Get 3D noise factor for given parameters
     *
     * Returns multiplier for 3D density noise (0.0 to 1.0+)
     *
     * ENHANCED: Includes Swiss-cheese mountain logic via weirdness amplification
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
        // Derivative 0.0 for clear ocean/land separation
        continentalnessSpline.addPoint(-1.0f, buildErosionFactor(0.0f, 0.1f), 0.0f);

        // Shallow ocean/coastal: low factor
        // Derivative 0.3 for gentle increase
        continentalnessSpline.addPoint(-0.5f, buildErosionFactor(0.1f, 0.2f), 0.3f);

        // Coastal/near inland: moderate factor
        // Derivative 0.5 for steady increase
        continentalnessSpline.addPoint(0.0f, buildErosionFactor(0.2f, 0.4f), 0.5f);

        // Inland: higher factor
        // Derivative 0.8 for increasing cave density
        continentalnessSpline.addPoint(0.4f, buildErosionFactor(0.4f, 0.6f), 0.8f);

        // High inland: high factor (more caves)
        // Derivative 1.0 for significant cave systems
        continentalnessSpline.addPoint(0.7f, buildErosionFactor(0.6f, 0.8f), 1.0f);

        // Extreme inland (mountains): maximum factor (lots of caves/overhangs)
        // Derivative 0.0 for plateau at max cave density
        // PHASE 1.3: This enables Swiss-cheese effect at high weirdness
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
        // Derivative 0.0 for distinct mountain cave density
        erosionSpline.addPoint(-1.0f, buildWeirdnessFactor(maxFactor), 0.0f);

        // Mid erosion: interpolated factor
        // Derivative 1.0 for smooth transition
        erosionSpline.addPoint(0.0f, buildWeirdnessFactor((baseFactor + maxFactor) / 2.0f), 1.0f);

        // High erosion = plains = low factor
        // Derivative 0.0 for solid plains
        erosionSpline.addPoint(1.0f, buildWeirdnessFactor(baseFactor), 0.0f);

        return erosionSpline;
    }

    /**
     * Build weirdness sub-spline for factor
     *
     * ENHANCED (Phase 1.3): Swiss-cheese mountain logic
     *
     * Weirdness amplifies cave density and creates unusual terrain formations:
     * - Low weirdness (-1.0) = 0.8x factor (fewer caves, standard terrain)
     * - Neutral weirdness (0.0) = 1.0x factor (normal cave density)
     * - High weirdness (0.85) = 1.5x factor (extensive cave networks)
     * - Extreme weirdness (1.0) = 2.5x factor (Swiss-cheese mountains)
     *
     * High weirdness areas feature:
     * - More frequent cave openings
     * - Natural arches and bridges
     * - Overhanging cliffs
     * - Swiss-cheese terrain (if continentalness > 0.6 AND weirdness > 0.85)
     *
     * PHASE 1.3: At extreme weirdness + high continentalness, factor can exceed 1.0
     * to create dramatic Swiss-cheese mountains with extensive void networks.
     *
     * @param baseFactor Base factor to amplify by weirdness
     */
    private MultiDimensionalSpline buildWeirdnessFactor(float baseFactor) {
        MultiDimensionalSpline weirdnessSpline = new MultiDimensionalSpline();

        // Low weirdness: reduced factor (standard solid terrain)
        // Derivative 0.5 for gentle cave reduction
        weirdnessSpline.addPoint(-1.0f, baseFactor * 0.8f, 0.5f);

        // Neutral weirdness: standard factor
        // Derivative 1.0 for moderate cave variation
        weirdnessSpline.addPoint(0.0f, baseFactor, 1.0f);

        // High weirdness: amplified factor (extensive caves)
        // Derivative 2.0 for dramatic increase
        weirdnessSpline.addPoint(0.85f, baseFactor * 1.5f, 2.0f);

        // PHASE 1.3: Extreme weirdness = Swiss-cheese effect
        // Factor can exceed 1.0 for mountainous terrain (continentalness > 0.6)
        // When baseFactor is high (0.7-1.0 in mountains), this creates 1.75-2.5 factor
        // Derivative 1.5 for dramatic but smooth transition to extreme voidness
        weirdnessSpline.addPoint(1.0f, baseFactor * 2.5f, 1.5f);

        return weirdnessSpline;
    }
}
