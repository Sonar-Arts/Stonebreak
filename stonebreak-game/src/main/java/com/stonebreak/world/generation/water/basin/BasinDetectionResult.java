package com.stonebreak.world.generation.water.basin;

/**
 * Result of basin detection containing water level and basin size metadata.
 *
 * <p>Used for adaptive basin validation that scales with detected basin size
 * and valley rejection based on aspect ratios.</p>
 *
 * <p><strong>Key Features:</strong></p>
 * <ul>
 *     <li><strong>Adaptive Validation</strong>: Calculates validation radius based on detection radius</li>
 *     <li><strong>Valley Rejection</strong>: Identifies canyons/valleys vs true lake basins</li>
 *     <li><strong>Metadata Preservation</strong>: Stores detection parameters for debugging</li>
 * </ul>
 *
 * @param waterLevel Water level Y coordinate (-1 if no basin detected)
 * @param detectionRadius Ring radius that succeeded (32-128 blocks)
 * @param lowestPointY Basin floor height (Y coordinate)
 * @param avgRimHeight Average rim height from filtered samples
 * @param filteredSampleCount How many rim samples passed outlier filter
 */
public record BasinDetectionResult(
    int waterLevel,
    int detectionRadius,
    int lowestPointY,
    int avgRimHeight,
    int filteredSampleCount
) {
    /**
     * Creates a "no basin found" result.
     *
     * @return Result indicating no valid basin was detected
     */
    public static BasinDetectionResult noBasin() {
        return new BasinDetectionResult(-1, 0, 0, 0, 0);
    }

    /**
     * Checks if a valid basin was detected.
     *
     * @return true if basin detected (waterLevel > 0), false otherwise
     */
    public boolean hasBasin() {
        return waterLevel > 0;
    }

    /**
     * Calculates basin depth (rim - floor).
     *
     * @return Basin depth in blocks
     */
    public int getBasinDepth() {
        return avgRimHeight - lowestPointY;
    }

    /**
     * Calculates adaptive validation radius scaled from detection radius.
     *
     * <p><strong>Scaling Formula:</strong></p>
     * <pre>
     * validationRadius = max(8, detectionRadius * (0.25 + regularityFactor * 0.15))
     * where regularityFactor = filteredSampleCount / 16.0 (basin regularity)
     * </pre>
     *
     * <p><strong>Rationale:</strong></p>
     * <ul>
     *     <li>More irregular basins (fewer filtered samples) need smaller validation radius</li>
     *     <li>Regular basins (all samples passed) can use larger radius (up to 40%)</li>
     *     <li>Scaling range: 25-40% of detection radius</li>
     * </ul>
     *
     * <p><strong>Examples:</strong></p>
     * <pre>
     * Regular 32-block basin  →  8-13 block validation (25-40% of 32)
     * Regular 64-block basin  → 16-26 block validation (25-40% of 64)
     * Regular 128-block basin → 32-51 block validation (25-40% of 128)
     * Irregular 64-block basin → 16-19 block validation (lower % due to irregularity)
     * </pre>
     *
     * @return Adaptive validation radius in blocks (minimum 8)
     */
    public int getAdaptiveValidationRadius() {
        // More irregular basins (fewer filtered samples) need smaller validation radius
        // Regular basins (all samples passed) can use larger radius
        float regularityFactor = filteredSampleCount / 16.0f; // 16 = ring sample count
        float scaleFactor = 0.25f + (regularityFactor * 0.15f); // 25-40%
        return Math.max(8, (int)(detectionRadius * scaleFactor));
    }

    /**
     * Checks if this basin is actually a valley (too deep/wide for a lake).
     *
     * <p><strong>Valley Detection Algorithm:</strong></p>
     * <p>Uses aspect ratio: reject if <code>depth &gt; width / 3</code></p>
     *
     * <p><strong>Rationale:</strong></p>
     * <ul>
     *     <li>Lakes have moderate depth relative to width (wider than deep)</li>
     *     <li>Canyons/valleys are deep relative to width (vertical proportions)</li>
     *     <li>Threshold: depth exceeds 1/3 of width → canyon-like, reject</li>
     * </ul>
     *
     * <p><strong>Examples:</strong></p>
     * <pre>
     * ✓ ACCEPTED:  64-block wide,  18 blocks deep (18 <  64/3 = 21.3) → Lake
     * ✗ REJECTED:  64-block wide,  25 blocks deep (25 >  64/3 = 21.3) → Valley
     *
     * ✓ ACCEPTED: 128-block wide,  35 blocks deep (35 < 128/3 = 42.7) → Large lake
     * ✗ REJECTED: 128-block wide,  50 blocks deep (50 > 128/3 = 42.7) → Valley
     *
     * ✓ ACCEPTED: 256-block wide,  70 blocks deep (70 < 256/3 = 85.3) → Massive crater lake
     * ✗ REJECTED: 256-block wide,  95 blocks deep (95 > 256/3 = 85.3) → Canyon/Valley
     * </pre>
     *
     * @return true if this is a valley (canyon), false if it's a valid lake basin
     */
    public boolean isValley() {
        if (!hasBasin()) return false;

        int depth = getBasinDepth();
        int width = detectionRadius * 2; // Diameter

        // Canyon-like proportions: depth exceeds 1/3 of width
        // Example: 60-block wide basin can be 20 blocks deep max
        return depth > (width / 3);
    }
}
