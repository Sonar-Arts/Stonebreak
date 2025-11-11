package com.stonebreak.world.generation.spline;

import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;

/**
 * Routes multi-noise parameters through a unified spline system to generate terrain height.
 * <p>
 * This router defines the complete terrain generation behavior using nested multi-dimensional
 * splines that encode all terrain variety: oceans, beaches, plains, hills, mountains, plateaus,
 * and weird terrain formations.
 * <p>
 * <strong>Spline Structure:</strong>
 * <pre>
 * height = spline(continentalness, erosion, PV, weirdness)
 *   ├─ Continentalness (-1 to 1): Ocean depth to mountain height
 *   │   ├─ Erosion (-1 to 1): Mountainous to flat
 *   │   │   ├─ Peaks & Valleys (-1 to 1): Valley depth vs peak height
 *   │   │   │   └─ Weirdness (-1 to 1): Normal vs plateau/mesa
 * </pre>
 * <p>
 * <strong>Terrain Types Encoded:</strong>
 * <ul>
 *   <li>Deep Ocean (continentalness = -1.0): ~40 blocks</li>
 *   <li>Ocean (continentalness = -0.6): ~50 blocks</li>
 *   <li>Coastal Beach (continentalness = -0.3): ~68 blocks</li>
 *   <li>Flat Plains (continentalness = 0.2, high erosion): ~75 blocks</li>
 *   <li>Rolling Hills (continentalness = 0.2, mid erosion): ~90 blocks</li>
 *   <li>Highlands (continentalness = 0.5): ~105 blocks</li>
 *   <li>Mountains (continentalness = 0.6, low erosion): ~135 blocks</li>
 *   <li>High Mountains (continentalness = 0.8, very low erosion, high PV): ~165 blocks</li>
 *   <li>Extreme Peaks (continentalness = 0.9, minimal erosion, extreme PV): ~190 blocks</li>
 *   <li>Plateaus/Mesas (high weirdness): Terraced terrain at 110-130 blocks</li>
 * </ul>
 * <p>
 * <strong>Special Effects:</strong>
 * <ul>
 *   <li><strong>Weirdness Terracing:</strong> When weirdness > 0.7, heights are quantized to 8-block steps creating mesa-like plateaus</li>
 *   <li><strong>PV Amplification:</strong> Extreme PV values (<-0.5 or >0.5) amplify height deviations for dramatic peaks and deep valleys</li>
 * </ul>
 *
 * @see MultiDimensionalSpline
 * @see com.stonebreak.world.generation.noise.NoiseRouter
 */
public class TerrainSplineRouter {

    private final MultiDimensionalSpline masterSpline;
    private final TerrainGenerationConfig config;

    /**
     * Create a new terrain spline router.
     *
     * @param seed World seed (currently unused, for future noise-based spline modulation)
     * @param config Terrain generation configuration
     */
    public TerrainSplineRouter(long seed, TerrainGenerationConfig config) {
        this.config = config;
        this.masterSpline = buildMasterSpline();
    }

    /**
     * Get terrain height for the given multi-noise parameters.
     *
     * @param params Multi-noise parameters (continentalness, erosion, PV, weirdness, etc.)
     * @return Terrain height in blocks
     */
    public int getHeight(MultiNoiseParameters params) {
        // Sample the 4D spline: height = f(continentalness, erosion, PV, weirdness)
        float rawHeight = masterSpline.sample(
            params.continentalness,
            params.erosion,
            params.peaksValleys,
            params.weirdness
        );

        // Apply weirdness terracing for plateaus/mesas
        if (params.weirdness > 0.7f) {
            // Quantize to 8-block steps for mesa-like plateaus
            rawHeight = (float) (Math.floor(rawHeight / 8.0) * 8.0);
        }

        // Clamp to valid world height range
        return Math.max(5, Math.min(250, (int) rawHeight));
    }

    /**
     * Build the master 4D spline encoding all terrain variety.
     * <p>
     * Structure: continentalness → erosion → PV → weirdness → height
     *
     * @return The master spline
     */
    private MultiDimensionalSpline buildMasterSpline() {
        MultiDimensionalSpline spline = new MultiDimensionalSpline();

        // ====================
        // OCEANIC TERRAIN (Continentalness: -1.0 to -0.2)
        // ====================

        // Deep Ocean: -1.0 continentalness
        spline.addPoint(-1.0f, buildOceanSpline(40.0f));

        // Ocean: -0.6 continentalness
        spline.addPoint(-0.6f, buildOceanSpline(50.0f));

        // Coastal/Beach: -0.3 continentalness (slightly varied by erosion)
        spline.addPoint(-0.3f, buildCoastalSpline());

        // ====================
        // LOWLAND TERRAIN (Continentalness: 0.0 to 0.4)
        // ====================

        // Low inland: 0.0 continentalness (transition from coast to plains)
        spline.addPoint(0.0f, buildLowlandSpline(70.0f, 80.0f));

        // Plains/Hills: 0.2 continentalness
        spline.addPoint(0.2f, buildLowlandSpline(75.0f, 95.0f));

        // Highlands: 0.4 continentalness
        spline.addPoint(0.4f, buildHighlandSpline());

        // ====================
        // MOUNTAINOUS TERRAIN (Continentalness: 0.5 to 1.0)
        // ====================

        // Low Mountains: 0.5 continentalness
        spline.addPoint(0.5f, buildMountainSpline(85.0f, 120.0f));

        // Mountains: 0.7 continentalness
        spline.addPoint(0.7f, buildMountainSpline(90.0f, 145.0f));

        // High Mountains: 0.85 continentalness
        spline.addPoint(0.85f, buildHighMountainSpline());

        // Extreme Peaks: 1.0 continentalness
        spline.addPoint(1.0f, buildExtremePeaksSpline());

        return spline;
    }

    /**
     * Build ocean spline (minimal variation with erosion/PV/weirdness).
     *
     * @param baseDepth Base ocean depth
     * @return Erosion spline for ocean terrain
     */
    private MultiDimensionalSpline buildOceanSpline(float baseDepth) {
        MultiDimensionalSpline erosionSpline = new MultiDimensionalSpline();

        // Ocean depth varies slightly with erosion (ocean trenches vs shallow seas)
        erosionSpline.addPoint(-1.0f, buildPVSpline(baseDepth - 5.0f, baseDepth - 3.0f));
        erosionSpline.addPoint(0.0f, buildPVSpline(baseDepth, baseDepth));
        erosionSpline.addPoint(1.0f, buildPVSpline(baseDepth + 3.0f, baseDepth + 2.0f));

        return erosionSpline;
    }

    /**
     * Build coastal spline (beaches and coastal areas).
     *
     * @return Erosion spline for coastal terrain
     */
    private MultiDimensionalSpline buildCoastalSpline() {
        MultiDimensionalSpline erosionSpline = new MultiDimensionalSpline();

        // Coastal cliffs at low erosion, flat beaches at high erosion
        erosionSpline.addPoint(-1.0f, buildPVSpline(72.0f, 78.0f)); // Coastal cliffs
        erosionSpline.addPoint(0.0f, buildPVSpline(68.0f, 72.0f));  // Mixed coast
        erosionSpline.addPoint(1.0f, buildPVSpline(66.0f, 68.0f));  // Flat beaches

        return erosionSpline;
    }

    /**
     * Build lowland spline (plains and gentle hills).
     *
     * @param flatHeight Height for flat plains (high erosion)
     * @param hillyHeight Height for rolling hills (low erosion)
     * @return Erosion spline for lowland terrain
     */
    private MultiDimensionalSpline buildLowlandSpline(float flatHeight, float hillyHeight) {
        MultiDimensionalSpline erosionSpline = new MultiDimensionalSpline();

        // Low erosion = rolling hills, high erosion = flat plains
        erosionSpline.addPoint(-1.0f, buildPVSpline(hillyHeight - 5.0f, hillyHeight + 15.0f)); // Rolling hills
        erosionSpline.addPoint(0.0f, buildPVSpline((flatHeight + hillyHeight) / 2.0f, (flatHeight + hillyHeight) / 2.0f + 10.0f));
        erosionSpline.addPoint(1.0f, buildPVSpline(flatHeight, flatHeight + 5.0f)); // Flat plains

        return erosionSpline;
    }

    /**
     * Build highland spline (elevated terrain transitioning to mountains).
     *
     * @return Erosion spline for highland terrain
     */
    private MultiDimensionalSpline buildHighlandSpline() {
        MultiDimensionalSpline erosionSpline = new MultiDimensionalSpline();

        // Significant height variation with erosion
        erosionSpline.addPoint(-1.0f, buildPVSpline(100.0f, 130.0f)); // Highlands with peaks
        erosionSpline.addPoint(0.0f, buildPVSpline(90.0f, 110.0f));   // Moderate highlands
        erosionSpline.addPoint(1.0f, buildPVSpline(80.0f, 95.0f));    // Flat highlands

        return erosionSpline;
    }

    /**
     * Build mountain spline.
     *
     * @param lowHeight Height at high erosion (flatter mountains)
     * @param highHeight Height at low erosion (sharp mountains)
     * @return Erosion spline for mountain terrain
     */
    private MultiDimensionalSpline buildMountainSpline(float lowHeight, float highHeight) {
        MultiDimensionalSpline erosionSpline = new MultiDimensionalSpline();

        // Dramatic height variation with erosion
        erosionSpline.addPoint(-1.0f, buildPVSpline(highHeight, highHeight + 30.0f)); // Sharp mountains
        erosionSpline.addPoint(0.0f, buildPVSpline((lowHeight + highHeight) / 2.0f, (lowHeight + highHeight) / 2.0f + 20.0f));
        erosionSpline.addPoint(1.0f, buildPVSpline(lowHeight, lowHeight + 10.0f));    // Worn mountains

        return erosionSpline;
    }

    /**
     * Build high mountain spline (very tall peaks).
     *
     * @return Erosion spline for high mountain terrain
     */
    private MultiDimensionalSpline buildHighMountainSpline() {
        MultiDimensionalSpline erosionSpline = new MultiDimensionalSpline();

        // Extreme heights at low erosion
        erosionSpline.addPoint(-1.0f, buildPVSpline(150.0f, 180.0f)); // Extreme sharp peaks
        erosionSpline.addPoint(0.0f, buildPVSpline(120.0f, 150.0f));  // High mountains
        erosionSpline.addPoint(1.0f, buildPVSpline(95.0f, 120.0f));   // Elevated plateaus

        return erosionSpline;
    }

    /**
     * Build extreme peaks spline (highest terrain in the world).
     *
     * @return Erosion spline for extreme peak terrain
     */
    private MultiDimensionalSpline buildExtremePeaksSpline() {
        MultiDimensionalSpline erosionSpline = new MultiDimensionalSpline();

        // Maximum heights with dramatic PV amplification
        erosionSpline.addPoint(-1.0f, buildPVSpline(170.0f, 200.0f)); // Extreme peaks
        erosionSpline.addPoint(0.0f, buildPVSpline(140.0f, 170.0f));  // Very high mountains
        erosionSpline.addPoint(1.0f, buildPVSpline(110.0f, 140.0f));  // High plateaus

        return erosionSpline;
    }

    /**
     * Build PV (Peaks & Valleys) spline that amplifies height extremes.
     * <p>
     * Negative PV creates valleys (lower than base).
     * Positive PV creates peaks (higher than base).
     * Extreme PV values (|PV| > 0.5) get amplified for dramatic terrain.
     *
     * @param baseHeight Base height at PV = 0
     * @param peakHeight Peak height at PV = 1
     * @return PV spline
     */
    private MultiDimensionalSpline buildPVSpline(float baseHeight, float peakHeight) {
        MultiDimensionalSpline pvSpline = new MultiDimensionalSpline();

        float valleyHeight = baseHeight - (peakHeight - baseHeight) * 0.5f; // Valleys less deep than peaks are tall

        // Deep valleys at PV = -1
        pvSpline.addPoint(-1.0f, buildWeirdnessSpline(valleyHeight));

        // Near-base at PV = -0.2 (slight valley)
        pvSpline.addPoint(-0.2f, buildWeirdnessSpline(baseHeight - (peakHeight - baseHeight) * 0.1f));

        // Base height at PV = 0
        pvSpline.addPoint(0.0f, buildWeirdnessSpline(baseHeight));

        // Slight elevation at PV = 0.2
        pvSpline.addPoint(0.2f, buildWeirdnessSpline(baseHeight + (peakHeight - baseHeight) * 0.3f));

        // High peaks at PV = 1
        pvSpline.addPoint(1.0f, buildWeirdnessSpline(peakHeight));

        return pvSpline;
    }

    /**
     * Build weirdness spline for plateau/mesa effects.
     * <p>
     * Normal terrain at low weirdness, plateaus at high weirdness.
     * Terracing is applied in getHeight() for weirdness > 0.7.
     *
     * @param normalHeight Height for normal terrain (low weirdness)
     * @return Weirdness spline
     */
    private MultiDimensionalSpline buildWeirdnessSpline(float normalHeight) {
        MultiDimensionalSpline weirdnessSpline = new MultiDimensionalSpline();

        // Weird valleys at weirdness = -1 (lower than normal)
        weirdnessSpline.addPoint(-1.0f, normalHeight - 15.0f);

        // Normal terrain at weirdness = 0
        weirdnessSpline.addPoint(0.0f, normalHeight);

        // Slight elevation at weirdness = 0.5
        weirdnessSpline.addPoint(0.5f, normalHeight + 5.0f);

        // Plateaus at weirdness = 1 (elevated + terraced in getHeight())
        weirdnessSpline.addPoint(1.0f, normalHeight + 20.0f);

        return weirdnessSpline;
    }
}
