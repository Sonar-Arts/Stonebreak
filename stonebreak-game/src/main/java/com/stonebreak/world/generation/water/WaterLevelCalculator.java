package com.stonebreak.world.generation.water;

import com.stonebreak.world.generation.config.WaterGenerationConfig;

/**
 * Determines water level at any world position based on basin detection and climate filtering.
 *
 * @deprecated Replaced by {@link WaterLevelGrid} for flat, multi-chunk water bodies.
 *             This class created "weird waves" due to per-column calculation causing
 *             uneven water surfaces. Kept for reference only.
 *
 * Algorithm:
 * 1. Get regional elevation (expected terrain height)
 * 2. Calculate basin depth (regional elevation - actual terrain height)
 * 3. Apply Y=64 bias (reduce threshold near sweet spot)
 * 4. Check climate filters (moisture, temperature)
 * 5. Return water level or -1 if no water
 *
 * Basin Detection:
 * - A position is a "basin" if actual terrain is significantly below regional elevation
 * - Threshold typically 3 blocks, reduced near Y=64 for larger water bodies
 *
 * Climate Filtering:
 * - Moisture ≥ 0.3 required (prevents water in deserts)
 * - Temperature 0.1-0.95 required (prevents water in extreme hot/cold)
 *
 * Follows Single Responsibility Principle - only determines water levels.
 */
@Deprecated(since = "Project-Gaia", forRemoval = false)
public class WaterLevelCalculator {

    private final RegionalElevationRouter regionalRouter;
    private final WaterGenerationConfig config;

    /**
     * Creates a new water level calculator.
     *
     * @param regionalRouter Router for regional elevation sampling
     * @param config Water generation configuration
     */
    public WaterLevelCalculator(RegionalElevationRouter regionalRouter, WaterGenerationConfig config) {
        this.regionalRouter = regionalRouter;
        this.config = config;
    }

    /**
     * Calculates water level for a column, or -1 if no water should generate.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param terrainHeight Actual terrain height at this position
     * @param temperature Temperature at this position [0.0-1.0]
     * @param humidity Humidity/moisture at this position [0.0-1.0]
     * @return Water level (Y coordinate), or -1 if no water
     */
    public int calculateWaterLevel(int worldX, int worldZ, int terrainHeight,
                                   float temperature, float humidity) {
        // Get regional elevation (expected height)
        int regionalElevation = regionalRouter.getRegionalElevation(worldX, worldZ);

        // Calculate how deep below regional elevation the terrain is
        int basinDepth = regionalElevation - terrainHeight;

        // Apply Y=64 bias: Reduce threshold within ±15 blocks of Y=64
        // This makes it easier for water to form near Y=64 (ocean level)
        int threshold = config.basinDepthThreshold;
        float distanceFrom64 = Math.abs(regionalElevation - config.sweetSpotElevation);
        if (distanceFrom64 < config.sweetSpotRadius) {
            float biasStrength = 1.0f - (distanceFrom64 / config.sweetSpotRadius);
            // Reduce threshold by up to 2 blocks at Y=64, linearly to 0 at radius edge
            threshold = Math.max(1, threshold - (int)(biasStrength * 2));
        }

        // Check basin depth threshold
        if (basinDepth < threshold) {
            return -1; // Not deep enough for water
        }

        // Climate filters
        if (humidity < config.minimumMoisture) {
            return -1; // Too dry (desert-like)
        }

        if (temperature <= 0.1f || temperature >= 0.95f) {
            return -1; // Too extreme (very hot or very cold)
        }

        // Calculate water level: regional elevation + offset (typically -2)
        int waterLevel = regionalElevation + config.waterFillOffset;

        // Cap water depth to prevent extremely deep water
        int maxWaterLevel = terrainHeight + config.maxWaterDepth;
        waterLevel = Math.min(waterLevel, maxWaterLevel);

        // Ensure water level is above terrain (sanity check)
        if (waterLevel <= terrainHeight) {
            return -1;
        }

        return waterLevel;
    }
}
