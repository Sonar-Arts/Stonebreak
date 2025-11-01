package com.stonebreak.world.generation.noise.interpolation;

import com.stonebreak.world.generation.noise.MultiNoiseParameters;

/**
 * Interface for parameter interpolation strategies.
 *
 * <p>Parameter interpolation reduces noise sampling overhead by sampling on a coarser grid
 * and interpolating between grid points. This provides smooth parameter transitions while
 * significantly reducing computational cost (e.g., 16-block grid = 256x fewer noise calls).</p>
 *
 * <p>Implementations should:</p>
 * <ul>
 *   <li>Sample noise on a regular grid</li>
 *   <li>Cache grid samples to avoid redundant noise calls</li>
 *   <li>Interpolate between grid samples for intermediate positions</li>
 *   <li>Clear cache between chunks to prevent memory accumulation</li>
 * </ul>
 *
 * @see BilinearParameterInterpolator
 */
public interface ParameterInterpolator {

    /**
     * Sample parameters with interpolation for smooth transitions.
     *
     * <p>This method samples noise on a coarse grid and interpolates between grid points
     * to provide smooth parameter values at any world position. Grid samples are cached
     * to improve performance.</p>
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param height Height for altitude-adjusted temperature
     * @return Interpolated multi-noise parameters
     */
    MultiNoiseParameters sampleInterpolated(int worldX, int worldZ, int height);

    /**
     * Clear cached grid samples.
     *
     * <p>Should be called after each chunk is generated to prevent memory accumulation.
     * The cache is typically small (5-10 samples per chunk) but should be cleared
     * regularly to avoid unbounded growth.</p>
     */
    void clearCache();
}
