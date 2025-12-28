package com.stonebreak.world.generation.water.basin;

import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.generation.utils.TerrainCalculations;

/**
 * Detects basin rims (lowest spillover points) for lake/pond water generation.
 *
 * <p><strong>Algorithm:</strong></p>
 * <ol>
 *     <li>Sample terrain in NxN grid around center point (17x17 default)</li>
 *     <li>Find samples higher than center (potential rim points)</li>
 *     <li>Return minimum rim height (lowest overflow point)</li>
 *     <li>Basin depth = rim height - center height</li>
 * </ol>
 *
 * <p><strong>Rim Detection Ensures:</strong> Water fills to lowest overflow point,
 * preventing unrealistic water spillover. If no rim is found (open valley or flat area),
 * returns null to indicate "not a basin".</p>
 *
 * <p><strong>Edge Cases:</strong></p>
 * <ul>
 *     <li>No rim found (all samples lower than center): Returns null (open depression)</li>
 *     <li>Flat area (all samples equal to center): Returns null (not a basin)</li>
 *     <li>Multi-chunk basins: Grid system naturally handles (grid points span chunks)</li>
 * </ul>
 *
 * <p><strong>Design:</strong> Follows Single Responsibility Principle - only detects basin rims.
 * Part of the two-tiered water generation system.</p>
 */
public class RimDetector {

    private final NoiseRouter noiseRouter;
    private final TerrainGenerator terrainGenerator;
    private final int sampleResolution; // N for NxN grid
    private final int sampleSpacing;    // Distance between samples

    /**
     * Creates rim detector.
     *
     * @param noiseRouter Noise router for parameter sampling
     * @param terrainGenerator Terrain generator for height calculation
     * @param gridResolution Grid resolution in blocks (256 typical)
     * @param sampleResolution Sample grid size (17 typical = 17x17 = 289 samples)
     */
    public RimDetector(NoiseRouter noiseRouter, TerrainGenerator terrainGenerator,
                      int gridResolution, int sampleResolution) {
        this.noiseRouter = noiseRouter;
        this.terrainGenerator = terrainGenerator;
        this.sampleResolution = sampleResolution;
        this.sampleSpacing = gridResolution / sampleResolution;
    }

    /**
     * Detects basin rim and depth at a grid point.
     *
     * <p>Samples terrain in NxN grid, finds minimum rim height (lowest point
     * higher than center that would cause spillover).</p>
     *
     * <p><strong>Returns null if:</strong></p>
     * <ul>
     *     <li>No samples are higher than center (open depression)</li>
     *     <li>All samples equal center (flat area)</li>
     * </ul>
     *
     * @param centerX Center X world coordinate
     * @param centerZ Center Z world coordinate
     * @return Basin rim info (rim height, center height, depth), or null if no basin
     */
    public BasinRimInfo detectRim(int centerX, int centerZ) {
        // Calculate center terrain height
        int centerHeight = calculateTerrainHeight(centerX, centerZ);

        // Sample surrounding terrain
        int halfSamples = sampleResolution / 2;
        int minRimHeight = Integer.MAX_VALUE;
        boolean foundRim = false;

        for (int dx = -halfSamples; dx <= halfSamples; dx++) {
            for (int dz = -halfSamples; dz <= halfSamples; dz++) {
                // Skip center point
                if (dx == 0 && dz == 0) {
                    continue;
                }

                int sampleX = centerX + dx * sampleSpacing;
                int sampleZ = centerZ + dz * sampleSpacing;

                // Sample terrain height
                int sampleHeight = calculateTerrainHeight(sampleX, sampleZ);

                // Check if this is a rim point (higher than center)
                if (sampleHeight > centerHeight) {
                    foundRim = true;
                    minRimHeight = Math.min(minRimHeight, sampleHeight);
                }
            }
        }

        // No rim found - open depression or flat area
        if (!foundRim) {
            return null;
        }

        // Calculate basin depth
        int depth = minRimHeight - centerHeight;

        return new BasinRimInfo(minRimHeight, centerHeight, depth);
    }

    /**
     * Calculates terrain height at a world position.
     *
     * <p>Samples noise parameters at sea level (y=64) for consistency,
     * then generates terrain height using the terrain generator.</p>
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Terrain height (Y coordinate)
     */
    private int calculateTerrainHeight(int worldX, int worldZ) {
        return TerrainCalculations.calculateTerrainHeight(worldX, worldZ, noiseRouter, terrainGenerator);
    }

    /**
     * Basin rim information record.
     *
     * <p>Immutable data class containing rim detection results.</p>
     *
     * @param rimHeight Lowest rim height (spillover point)
     * @param centerHeight Basin center height
     * @param depth Basin depth (rimHeight - centerHeight)
     */
    public record BasinRimInfo(int rimHeight, int centerHeight, int depth) {

        /**
         * Checks if basin meets minimum depth requirement.
         *
         * @param minimumDepth Minimum required depth in blocks
         * @return true if basin is deep enough
         */
        public boolean meetsMinimumDepth(int minimumDepth) {
            return depth >= minimumDepth;
        }
    }
}
