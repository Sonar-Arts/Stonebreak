package com.stonebreak.world.generation.config;

/**
 * Simplified configuration for two-tiered water body generation.
 *
 * <p><strong>TWO-TIERED SYSTEM:</strong></p>
 * <ol>
 *     <li><strong>Sea Level:</strong> Traditional ocean filling (terrain &lt; y=64)</li>
 *     <li><strong>Basin Detection:</strong> Elevated lakes/ponds (terrain &gt;= y=66) via simple single-ring sampling</li>
 * </ol>
 *
 * <p><strong>Simplification (KISS/YAGNI):</strong> Removed over-engineered fields like graduated ring
 * coverage thresholds, four-stage pre-filtering, and noise parameter screening. Kept only essential
 * parameters needed for simple "find holes and fill them" approach.</p>
 *
 * <p>Follows immutability pattern for thread safety and predictable behavior.</p>
 */
public class WaterGenerationConfig {

    // ========================================
    // SEA LEVEL SYSTEM
    // ========================================

    /**
     * Sea level Y coordinate.
     * Water fills to this level if terrain &lt; seaLevel.
     * Default: 64 (traditional Minecraft sea level)
     */
    public final int seaLevel;

    // ========================================
    // BASIN DETECTION SYSTEM (Simplified)
    // ========================================

    /**
     * Minimum elevation for basin water detection.
     * Basin detection only operates on terrain &gt;= this elevation.
     * Creates gap between sea-level and basin systems (y=64-65 gets no water).
     * Default: 65
     */
    public final int basinMinimumElevation;

    /**
     * Search radius for basin detection (blocks from grid center).
     * Used to find the lowest point in the search area.
     * Default: 64 blocks (reduced from 128 for faster detection)
     */
    public final int basinSearchRadius;

    /**
     * Step size for finding lowest point in search area.
     * Smaller = more accurate but slower.
     * Default: 4 blocks (33x33 sample grid in 128x128 area)
     */
    public final int lowestPointSampleStep;

    /**
     * Radius for single ring sampling (blocks from basin center).
     * Samples terrain at this distance from the lowest point to find rim height.
     * Default: 32 blocks (good balance of basin size detection)
     */
    public final int singleRingRadius;

    /**
     * Number of sample points around the ring (evenly distributed).
     * Default: 16 samples (22.5° apart, good balance of accuracy/performance)
     */
    public final int ringSampleCount;

    /**
     * Minimum basin depth (avgRim - lowestY) required for water generation.
     * Prevents tiny shallow puddles from forming.
     * Default: 2 blocks
     */
    public final int minimumRimDepth;

    // ========================================
    // CLIMATE FILTERS
    // ========================================

    /**
     * Minimum moisture/humidity level required for basin water generation.
     * Range: [0.0, 1.0] where 0 = arid, 1 = humid
     * Default: 0.20 (moderate moisture - allows lakes in more biomes)
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

    // ========================================
    // ELEVATION PROBABILITY
    // ========================================

    /**
     * Exponential decay rate for elevation probability.
     * Higher values make high-elevation basins rarer.
     * Formula: P(y) = e^(-k * (y - basinMinimumElevation))
     * Default: 0.03 (50% at y=90, 15% at y=130)
     */
    public final float elevationDecayRate;

    // ========================================
    // GRID SYSTEM
    // ========================================

    /**
     * Grid resolution for water level calculation (in blocks).
     * Water levels are calculated on a coarse grid and interpolated between grid points.
     * Larger values create smoother, larger water bodies.
     * Default: 256 blocks (16 chunks)
     */
    public final int waterGridResolution;

    /**
     * Maximum number of grid cache entries before LRU eviction.
     * Prevents unbounded cache growth during world generation.
     * Default: 10,000 entries (~40 KB with GridPosition keys)
     */
    public final int maxGridCacheSize;

    // ========================================
    // SAFETY LIMITS
    // ========================================

    /**
     * Maximum water depth (in blocks) for any water body.
     * Prevents extremely deep water from terrain/elevation mismatches.
     * Acts as safety cap on interpolation artifacts.
     * Default: 30 blocks
     */
    public final int maxWaterDepth;

    // ========================================
    // EDGE DETECTION (Water Wall Prevention)
    // ========================================

    /**
     * Maximum terrain height difference to neighboring blocks for water placement.
     * If terrain drops more than this to any neighbor, water is rejected (prevents water walls on cliffs).
     *
     * <p><strong>Recommended Values:</strong></p>
     * <ul>
     *     <li>2 blocks: Very strict, only gentle slopes (may reject some valid lake edges)</li>
     *     <li>3 blocks: Balanced default, allows gentle slopes, rejects steep cliffs</li>
     *     <li>5 blocks: Relaxed, allows steeper slopes (may allow minor water walls)</li>
     * </ul>
     *
     * Default: 3 blocks
     */
    public final int maxTerrainDropForWater;

    /**
     * Whether to enable edge detection during water placement.
     *
     * <p>If true, water placement checks neighboring terrain heights and rejects
     * columns on cliff edges (prevents floating water walls).</p>
     *
     * <p>If false, water fills all columns from terrain to water level (old behavior,
     * may create water walls on steep terrain).</p>
     *
     * Default: true (enable edge detection)
     */
    public final boolean enableEdgeDetection;

    // ========================================
    // DEPRECATED LEGACY FIELDS (for backwards compatibility with legacy water system)
    // ========================================

    /**
     * Creates water generation config with simplified default values.
     */
    public WaterGenerationConfig() {
        // Sea level system
        this.seaLevel = 64;

        // Basin detection system (simplified)
        this.basinMinimumElevation = 65;
        this.basinSearchRadius = 64; // Reduced from 128
        this.lowestPointSampleStep = 4;
        this.singleRingRadius = 32;
        this.ringSampleCount = 16;
        this.minimumRimDepth = 2;

        // Climate filters
        this.minimumMoisture = 0.20f;
        this.freezeTemperature = 0.2f;

        // Elevation probability
        this.elevationDecayRate = 0.03f;

        // Grid system
        this.waterGridResolution = 256;
        this.maxGridCacheSize = 10_000;

        // Safety limits
        this.maxWaterDepth = 30;

        // Edge detection (water wall prevention)
        this.maxTerrainDropForWater = 3;
        this.enableEdgeDetection = true;

        // DEBUG: Log configuration
        System.out.println("=== Simplified WaterGenerationConfig Created ===");
        System.out.println(this);
        System.out.println("===============================================");
    }

    /**
     * Creates water generation config with custom values (for testing/tuning).
     *
     * @param seaLevel Sea level Y coordinate
     * @param basinMinimumElevation Minimum elevation for basin detection
     * @param basinSearchRadius Search radius for basin detection
     * @param lowestPointSampleStep Step size for lowest point search
     * @param singleRingRadius Radius for single ring sampling
     * @param ringSampleCount Number of samples around the ring
     * @param minimumRimDepth Minimum basin depth required
     * @param minimumMoisture Minimum moisture level [0.0-1.0]
     * @param freezeTemperature Ice formation temperature threshold [0.0-1.0]
     * @param elevationDecayRate Exponential decay rate for elevation probability
     * @param waterGridResolution Grid resolution in blocks
     * @param maxGridCacheSize Maximum grid cache size
     * @param maxWaterDepth Maximum water depth cap
     * @param maxTerrainDropForWater Maximum terrain height difference for water placement
     * @param enableEdgeDetection Whether to enable edge detection
     */
    public WaterGenerationConfig(
            int seaLevel,
            int basinMinimumElevation,
            int basinSearchRadius,
            int lowestPointSampleStep,
            int singleRingRadius,
            int ringSampleCount,
            int minimumRimDepth,
            float minimumMoisture,
            float freezeTemperature,
            float elevationDecayRate,
            int waterGridResolution,
            int maxGridCacheSize,
            int maxWaterDepth,
            int maxTerrainDropForWater,
            boolean enableEdgeDetection
    ) {
        this.seaLevel = seaLevel;
        this.basinMinimumElevation = basinMinimumElevation;
        this.basinSearchRadius = basinSearchRadius;
        this.lowestPointSampleStep = lowestPointSampleStep;
        this.singleRingRadius = singleRingRadius;
        this.ringSampleCount = ringSampleCount;
        this.minimumRimDepth = minimumRimDepth;
        this.minimumMoisture = minimumMoisture;
        this.freezeTemperature = freezeTemperature;
        this.elevationDecayRate = elevationDecayRate;
        this.waterGridResolution = waterGridResolution;
        this.maxGridCacheSize = maxGridCacheSize;
        this.maxWaterDepth = maxWaterDepth;
        this.maxTerrainDropForWater = maxTerrainDropForWater;
        this.enableEdgeDetection = enableEdgeDetection;
    }

    @Override
    public String toString() {
        return String.format(
                "WaterGenerationConfig{seaLevel=%d, basinMinElev=%d, searchRadius=%d, " +
                "sampleStep=%d, ringRadius=%d, ringSamples=%d, minRimDepth=%d, " +
                "minMoisture=%.2f, freezeTemp=%.2f, elevDecay=%.3f, gridRes=%d, " +
                "maxCache=%d, maxDepth=%d, maxTerrainDrop=%d, edgeDetection=%b}",
                seaLevel, basinMinimumElevation, basinSearchRadius,
                lowestPointSampleStep, singleRingRadius, ringSampleCount, minimumRimDepth,
                minimumMoisture, freezeTemperature, elevationDecayRate, waterGridResolution,
                maxGridCacheSize, maxWaterDepth, maxTerrainDropForWater, enableEdgeDetection
        );
    }
}
