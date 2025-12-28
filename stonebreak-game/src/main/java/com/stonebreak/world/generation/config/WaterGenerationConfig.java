package com.stonebreak.world.generation.config;

/**
 * Configuration parameters for two-tiered water body generation.
 *
 * <p><strong>TWO-TIERED SYSTEM:</strong></p>
 * <ol>
 *     <li><strong>Sea Level:</strong> Traditional ocean filling (terrain &lt; y=64)</li>
 *     <li><strong>Basin Detection:</strong> Elevated lakes/ponds (terrain &gt;= y=66) via rim detection</li>
 * </ol>
 *
 * <p>Follows immutability pattern for thread safety and predictable behavior.</p>
 */
public class WaterGenerationConfig {

    // ========================================
    // LEGACY FIELDS (for backward compatibility)
    // ========================================

    /**
     * Minimum basin depth (in blocks) required for water generation.
     * @deprecated Replaced by {@link #basinMinimumDepth} in two-tiered system
     */
    @Deprecated(since = "Two-Tiered System", forRemoval = false)
    public final int basinDepthThreshold;

    /**
     * Scale (in blocks) for regional elevation noise sampling.
     * @deprecated No longer used in two-tiered system (rim detection replaces regional elevation)
     */
    @Deprecated(since = "Two-Tiered System", forRemoval = false)
    public final int regionalElevationScale;

    /**
     * Elevation (Y coordinate) that receives preferential treatment for large water bodies.
     * @deprecated No longer used in two-tiered system (sea level is hard-coded at y=64)
     */
    @Deprecated(since = "Two-Tiered System", forRemoval = false)
    public final int sweetSpotElevation;

    /**
     * Radius (in blocks) around sweet spot elevation where bias applies.
     * @deprecated No longer used in two-tiered system
     */
    @Deprecated(since = "Two-Tiered System", forRemoval = false)
    public final float sweetSpotRadius;

    /**
     * Offset (in blocks) from regional elevation where water level sits.
     * @deprecated No longer used in two-tiered system (rim height is water level)
     */
    @Deprecated(since = "Two-Tiered System", forRemoval = false)
    public final int waterFillOffset;

    // ========================================
    // ACTIVE FIELDS (used in both systems)
    // ========================================

    /**
     * Minimum moisture/humidity level required for water generation.
     * Range: [0.0, 1.0] where 0 = arid, 1 = humid
     * Default: 0.3 (moderate moisture requirement)
     *
     * <p><strong>NOTE:</strong> Only applied to basin water. Sea-level water ignores climate.</p>
     */
    public final float minimumMoisture;

    /**
     * Temperature threshold below which water surface freezes to ice.
     * Range: [0.0, 1.0] where 0 = frozen, 1 = hot
     * Default: 0.2 (cold biomes get ice caps)
     */
    public final float freezeTemperature;

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

    // ========================================
    // TWO-TIERED SYSTEM FIELDS (NEW)
    // ========================================

    /**
     * Sea level Y coordinate.
     * Water fills to this level if terrain &lt; seaLevel.
     * Default: 64 (traditional Minecraft sea level)
     */
    public final int seaLevel;

    /**
     * Minimum elevation for basin water detection.
     * Basin detection only operates on terrain &gt;= this elevation.
     * Creates gap between sea-level and basin systems (y=64-65 gets no water).
     * Default: 66
     */
    public final int basinMinimumElevation;

    /**
     * Minimum basin depth (rim height - center height) required for water.
     * Prevents tiny shallow puddles from forming.
     * Default: 3 blocks
     */
    public final int basinMinimumDepth;

    /**
     * Exponential decay rate for elevation probability.
     * Higher values make high-elevation basins rarer.
     * Formula: P(y) = e^(-k * (y - basinMinimumElevation))
     * Default: 0.03 (50% at y=90, 15% at y=130)
     */
    public final float elevationDecayRate;

    /**
     * Creates water generation config with default values.
     */
    public WaterGenerationConfig() {
        // Legacy fields
        this.basinDepthThreshold = 3;
        this.regionalElevationScale = 3200;
        this.sweetSpotElevation = 64;
        this.sweetSpotRadius = 15.0f;
        this.waterFillOffset = -2;

        // Active fields
        this.minimumMoisture = 0.3f;
        this.freezeTemperature = 0.2f;
        this.maxWaterDepth = 30;
        this.waterGridResolution = 256;
        this.gridSampleResolution = 17;

        // Two-tiered system fields
        this.seaLevel = 63;
        this.basinMinimumElevation = 66;
        this.basinMinimumDepth = 3;
        this.elevationDecayRate = 0.03f;
    }

    /**
     * Creates water generation config with custom values (for testing/tuning).
     *
     * @param basinDepthThreshold DEPRECATED - use basinMinimumDepth
     * @param regionalElevationScale DEPRECATED
     * @param minimumMoisture Minimum moisture level [0.0-1.0]
     * @param freezeTemperature Ice formation temperature threshold [0.0-1.0]
     * @param sweetSpotElevation DEPRECATED
     * @param sweetSpotRadius DEPRECATED
     * @param waterFillOffset DEPRECATED
     * @param maxWaterDepth Maximum water depth cap
     * @param waterGridResolution Grid resolution for water level calculation in blocks
     * @param gridSampleResolution Terrain samples per grid point (N for NxN grid)
     * @param seaLevel Sea level Y coordinate
     * @param basinMinimumElevation Minimum elevation for basin detection
     * @param basinMinimumDepth Minimum basin depth in blocks
     * @param elevationDecayRate Exponential decay rate
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
            int gridSampleResolution,
            int seaLevel,
            int basinMinimumElevation,
            int basinMinimumDepth,
            float elevationDecayRate
    ) {
        // Legacy fields
        this.basinDepthThreshold = basinDepthThreshold;
        this.regionalElevationScale = regionalElevationScale;
        this.sweetSpotElevation = sweetSpotElevation;
        this.sweetSpotRadius = sweetSpotRadius;
        this.waterFillOffset = waterFillOffset;

        // Active fields
        this.minimumMoisture = minimumMoisture;
        this.freezeTemperature = freezeTemperature;
        this.maxWaterDepth = maxWaterDepth;
        this.waterGridResolution = waterGridResolution;
        this.gridSampleResolution = gridSampleResolution;

        // Two-tiered system fields
        this.seaLevel = seaLevel;
        this.basinMinimumElevation = basinMinimumElevation;
        this.basinMinimumDepth = basinMinimumDepth;
        this.elevationDecayRate = elevationDecayRate;
    }

    @Override
    public String toString() {
        return String.format(
                "WaterGenerationConfig{seaLevel=%d, basinMinElev=%d, basinMinDepth=%d, " +
                "elevDecayRate=%.3f, minMoisture=%.2f, freezeTemp=%.2f, maxDepth=%d, " +
                "gridRes=%d, samples=%dx%d}",
                seaLevel, basinMinimumElevation, basinMinimumDepth,
                elevationDecayRate, minimumMoisture, freezeTemperature, maxWaterDepth,
                waterGridResolution, gridSampleResolution, gridSampleResolution
        );
    }
}
