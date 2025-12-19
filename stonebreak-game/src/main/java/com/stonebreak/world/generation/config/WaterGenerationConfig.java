package com.stonebreak.world.generation.config;

/**
 * Configuration parameters for dynamic water body generation.
 *
 * This system generates water bodies at multiple elevations based on terrain depressions,
 * climate conditions (moisture/temperature), and regional elevation patterns.
 *
 * Follows immutability pattern for thread safety and predictable behavior.
 */
public class WaterGenerationConfig {

    /**
     * Minimum basin depth (in blocks) required for water generation.
     * Prevents tiny 1-2 block puddles from forming.
     */
    public final int basinDepthThreshold;

    /**
     * Scale (in blocks) for regional elevation noise sampling.
     * Larger values create broader elevation zones (continental scale).
     */
    public final int regionalElevationScale;

    /**
     * Minimum moisture/humidity level required for water generation.
     * Range: [0.0, 1.0] where 0 = arid, 1 = humid
     * Default: 0.3 (moderate moisture requirement)
     */
    public final float minimumMoisture;

    /**
     * Temperature threshold below which water surface freezes to ice.
     * Range: [0.0, 1.0] where 0 = frozen, 1 = hot
     * Default: 0.2 (cold biomes get ice caps)
     */
    public final float freezeTemperature;

    /**
     * Elevation (Y coordinate) that receives preferential treatment for large water bodies.
     * Water bodies near this elevation have reduced basin depth threshold.
     * Default: Y=64 (creates ocean-like water bodies at this level)
     */
    public final int sweetSpotElevation;

    /**
     * Radius (in blocks) around sweet spot elevation where bias applies.
     * Within this range, basin depth threshold is progressively reduced.
     * Default: 15 blocks (Y=49-79 receives varying bias strength)
     */
    public final float sweetSpotRadius;

    /**
     * Offset (in blocks) from regional elevation where water level sits.
     * Negative values fill water slightly below regional elevation.
     * Default: -2 (water sits 2 blocks below expected elevation)
     */
    public final int waterFillOffset;

    /**
     * Maximum water depth (in blocks) for any water body.
     * Prevents extremely deep water from terrain/elevation mismatches.
     * Default: 30 blocks
     */
    public final int maxWaterDepth;

    /**
     * Grid resolution for water level calculation (in blocks).
     * Water levels are calculated on a coarse grid and interpolated between grid points.
     * Larger values create smoother, larger water bodies.
     * Smaller values provide more detailed, basin-accurate water placement.
     * Default: 256 blocks (16 chunks)
     */
    public final int waterGridResolution;

    /**
     * Terrain samples per grid point for basin detection (N for NxN sampling grid).
     * More samples provide more accurate basin depth calculation but slower grid point calculation.
     * Default: 17 (17x17 = 289 samples per grid point)
     */
    public final int gridSampleResolution;

    /**
     * Creates water generation config with default values.
     */
    public WaterGenerationConfig() {
        this.basinDepthThreshold = 3;
        this.regionalElevationScale = 3200;
        this.minimumMoisture = 0.3f;
        this.freezeTemperature = 0.2f;
        this.sweetSpotElevation = 64;
        this.sweetSpotRadius = 15.0f;
        this.waterFillOffset = -2;
        this.maxWaterDepth = 30;
        this.waterGridResolution = 256;
        this.gridSampleResolution = 17;
    }

    /**
     * Creates water generation config with custom values (for testing/tuning).
     *
     * @param basinDepthThreshold Minimum basin depth in blocks
     * @param regionalElevationScale Regional noise scale in blocks
     * @param minimumMoisture Minimum moisture level [0.0-1.0]
     * @param freezeTemperature Ice formation temperature threshold [0.0-1.0]
     * @param sweetSpotElevation Preferred elevation for large water bodies
     * @param sweetSpotRadius Bias radius around sweet spot elevation
     * @param waterFillOffset Offset from regional elevation
     * @param maxWaterDepth Maximum water depth cap
     * @param waterGridResolution Grid resolution for water level calculation in blocks
     * @param gridSampleResolution Terrain samples per grid point (N for NxN grid)
     */
    public WaterGenerationConfig(
            int basinDepthThreshold,
            int regionalElevationScale,
            float minimumMoisture,
            float freezeTemperature,
            int sweetSpotElevation,
            float sweetSpotRadius,
            int waterFillOffset,
            int maxWaterDepth,
            int waterGridResolution,
            int gridSampleResolution
    ) {
        this.basinDepthThreshold = basinDepthThreshold;
        this.regionalElevationScale = regionalElevationScale;
        this.minimumMoisture = minimumMoisture;
        this.freezeTemperature = freezeTemperature;
        this.sweetSpotElevation = sweetSpotElevation;
        this.sweetSpotRadius = sweetSpotRadius;
        this.waterFillOffset = waterFillOffset;
        this.maxWaterDepth = maxWaterDepth;
        this.waterGridResolution = waterGridResolution;
        this.gridSampleResolution = gridSampleResolution;
    }

    @Override
    public String toString() {
        return String.format(
                "WaterGenerationConfig{basinThreshold=%d, scale=%d, minMoisture=%.2f, " +
                "freezeTemp=%.2f, sweetSpot=%d±%.1f, offset=%d, maxDepth=%d, " +
                "gridRes=%d, samples=%dx%d}",
                basinDepthThreshold, regionalElevationScale, minimumMoisture,
                freezeTemperature, sweetSpotElevation, sweetSpotRadius,
                waterFillOffset, maxWaterDepth, waterGridResolution,
                gridSampleResolution, gridSampleResolution
        );
    }
}
