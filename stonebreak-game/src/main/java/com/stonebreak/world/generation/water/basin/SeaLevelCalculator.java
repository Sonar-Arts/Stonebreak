package com.stonebreak.world.generation.water.basin;

/**
 * Calculates sea-level water for traditional ocean generation.
 *
 * <p>Simple, fast calculator that fills water to sea level (y=64 default) if terrain
 * is below sea level. No climate filtering, no grid calculation - just pure ocean filling.</p>
 *
 * <p><strong>Performance:</strong> O(1) per column (no noise sampling, no grid lookups)</p>
 *
 * <p><strong>Design:</strong> Follows Single Responsibility Principle - only handles sea-level water.
 * Part of the two-tiered water generation system (sea level + basin detection).</p>
 *
 * @see com.stonebreak.world.generation.water.basin.BasinWaterLevelGrid
 */
public class SeaLevelCalculator {

    private final int seaLevel;

    /**
     * Creates sea level calculator.
     *
     * @param seaLevel Sea level Y coordinate (typically 64)
     */
    public SeaLevelCalculator(int seaLevel) {
        this.seaLevel = seaLevel;
    }

    /**
     * Calculates water level for sea-level water.
     *
     * <p>Returns seaLevel if terrain is below sea level, otherwise -1 (no water).</p>
     *
     * <p><strong>Algorithm:</strong></p>
     * <pre>
     * if (terrainHeight &lt; seaLevel):
     *     return seaLevel  // Fill to sea level
     * else:
     *     return -1        // No sea-level water
     * </pre>
     *
     * @param terrainHeight Terrain height at this column
     * @return Water level (seaLevel), or -1 if no water
     */
    public int calculateSeaLevel(int terrainHeight) {
        if (terrainHeight < seaLevel) {
            return seaLevel;
        }
        return -1; // No sea-level water
    }

    /**
     * Gets the configured sea level value.
     *
     * @return Sea level Y coordinate
     */
    public int getSeaLevel() {
        return seaLevel;
    }
}
