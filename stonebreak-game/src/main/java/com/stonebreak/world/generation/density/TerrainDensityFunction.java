package com.stonebreak.world.generation.density;

import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.spline.OffsetSplineRouter;
import com.stonebreak.world.generation.spline.JaggednessSplineRouter;
import com.stonebreak.world.generation.spline.FactorSplineRouter;

/**
 * Complete 3D terrain density function for Minecraft 1.18+ style terrain generation.
 *
 * <p>This class combines three spline routers (offset, jaggedness, factor) with 3D noise
 * to create terrain with natural caves, overhangs, and smooth transitions. Unlike traditional
 * 2D heightmap generation which can only create columns of blocks, this density function
 * enables full 3D terrain features.</p>
 *
 * <h2>Density Formula</h2>
 * <pre>
 * density(x, y, z) = offset + jaggedness + (3D_noise × factor) - y
 * </pre>
 *
 * <p>Where:</p>
 * <ul>
 *   <li><b>offset</b>: Base terrain elevation from OffsetSplineRouter (e.g., 30-190 blocks)
 *       <br>Determines large-scale terrain features (oceans, plains, mountains)</li>
 *   <li><b>jaggedness</b>: High-frequency peak detail from JaggednessSplineRouter (0-15 blocks)
 *       <br>Adds sharpness to mountain peaks, smoothness to plains</li>
 *   <li><b>3D_noise × factor</b>: 3D Perlin noise scaled by FactorSplineRouter (0-10 blocks)
 *       <br>Creates caves, overhangs, and arches. Factor controls cave density (0=solid, 1=swiss cheese)</li>
 *   <li><b>y</b>: Current height being sampled (0-256 blocks)
 *       <br>Subtracted so density decreases with altitude</li>
 * </ul>
 *
 * <h2>How Terrain Generates</h2>
 * <p><b>Rule</b>: Terrain is solid where <code>density &gt; 0</code>, air where <code>density ≤ 0</code></p>
 *
 * <p><b>Example 1 - Mountain Peak</b></p>
 * <pre>
 * At x=100, z=200, y=150:
 *   offset = 160 (high mountain)
 *   jaggedness = +5 (sharp peak)
 *   3D_noise × factor = -2 (small cave opening)
 *   density = 160 + 5 + (-2) - 150 = 13 &gt; 0 → SOLID BLOCK
 *
 * At x=100, z=200, y=165:
 *   offset = 160
 *   jaggedness = +5
 *   3D_noise × factor = -2
 *   density = 160 + 5 + (-2) - 165 = -2 &lt; 0 → AIR
 * </pre>
 *
 * <p><b>Example 2 - Cave System</b></p>
 * <pre>
 * At x=500, z=500, y=50:
 *   offset = 80 (inland terrain)
 *   jaggedness = 0 (smooth area)
 *   3D_noise × factor = -35 (strong negative noise in high-factor mountain)
 *   density = 80 + 0 + (-35) - 50 = -5 &lt; 0 → AIR (cave)
 *
 * Nearby at x=505, z=500, y=50:
 *   offset = 80
 *   jaggedness = 0
 *   3D_noise × factor = +8 (positive noise)
 *   density = 80 + 0 + (+8) - 50 = 38 &gt; 0 → SOLID (cave wall)
 * </pre>
 *
 * <h2>Component Interactions</h2>
 * <ul>
 *   <li><b>Offset + Jaggedness</b>: Define the "target height" for solid terrain</li>
 *   <li><b>3D Noise × Factor</b>: Perturbs the surface vertically, creating caves and overhangs</li>
 *   <li><b>Subtraction of Y</b>: Ensures terrain ends at some height (not floating to infinity)</li>
 * </ul>
 *
 * <h2>Performance Considerations</h2>
 * <ul>
 *   <li><b>densityToHeight()</b>: Linear search from top to bottom. Simple but O(256) worst case</li>
 *   <li><b>densityToHeightBinarySearch()</b>: Binary search. O(log 256) = ~8 samples. 30x faster</li>
 *   <li><b>2D Mode</b>: Skip 3D density entirely, just use offset + jaggedness for speed</li>
 *   <li><b>Caching</b>: Consider caching spline router results if sampling same position multiple times</li>
 * </ul>
 *
 * <h2>Tuning for Different Effects</h2>
 * <table border="1">
 *   <tr><th>Goal</th><th>Adjust</th><th>Recommended Values</th></tr>
 *   <tr><td>More caves</td><td>Factor</td><td>Increase max factor to 1.0+</td></tr>
 *   <tr><td>Fewer caves</td><td>Factor</td><td>Decrease max factor to 0.5</td></tr>
 *   <tr><td>Sharper peaks</td><td>Jaggedness</td><td>Increase max to 20-25 blocks</td></tr>
 *   <tr><td>Smoother terrain</td><td>Jaggedness</td><td>Decrease max to 5-10 blocks</td></tr>
 *   <tr><td>Higher mountains</td><td>Offset</td><td>Increase high continentalness to 200+</td></tr>
 *   <tr><td>Larger caves</td><td>Noise frequency</td><td>Decrease to 0.02-0.03</td></tr>
 *   <tr><td>Smaller caves</td><td>Noise frequency</td><td>Increase to 0.08-0.10</td></tr>
 * </table>
 *
 * @see OffsetSplineRouter
 * @see JaggednessSplineRouter
 * @see FactorSplineRouter
 * @see NoiseDensityFunction
 * @see com.stonebreak.world.generation.spline.SplineTerrainGenerator
 */
public class TerrainDensityFunction implements DensityFunction {

    private final OffsetSplineRouter offsetRouter;
    private final JaggednessSplineRouter jaggednessRouter;
    private final FactorSplineRouter factorRouter;
    private final NoiseDensityFunction noise3D;

    public TerrainDensityFunction(long seed) {
        this.offsetRouter = new OffsetSplineRouter(seed);
        this.jaggednessRouter = new JaggednessSplineRouter(seed);
        this.factorRouter = new FactorSplineRouter(seed);
        this.noise3D = new NoiseDensityFunction(seed + 999, 0.05f, 10.0f);
    }

    @Override
    public float sample(int x, int y, int z, MultiNoiseParameters params) {
        // Get base terrain offset with multi-scale noise (target height)
        float baseOffset = offsetRouter.getOffset(params, x, z);

        // Apply erosion factor (same as 2D generator)
        // Low erosion (-1.0) = 1.4x variation (mountainous)
        // High erosion (1.0) = 0.6x variation (flat plains)
        float erosionFactor = 1.0f - (params.erosion * 0.4f);

        // Apply PV amplification (same as 2D generator)
        float pvAmplification = params.peaksValleys * 8.0f;

        // Calculate modified offset with erosion and PV
        float seaLevel = 64.0f;
        float heightFromSeaLevel = baseOffset - seaLevel;
        float targetHeight = seaLevel + (heightFromSeaLevel * erosionFactor) + pvAmplification;

        // Get jaggedness (peaks variation) - scaled by erosion
        float jaggednessStrength = Math.max(0.0f, -params.erosion);
        float jaggedness = jaggednessRouter.getJaggedness(params, x, z) * jaggednessStrength;

        // Get 3D noise factor
        float factor = factorRouter.getFactor(params);

        // Sample 3D noise
        float noise = noise3D.sample(x, y, z);

        // Combine all components
        // Subtract y so that density decreases with height
        // Terrain generates where density > 0
        float density = targetHeight + jaggedness + (noise * factor) - y;

        return density;
    }

    @Override
    public float sample(int x, int y, int z) {
        throw new UnsupportedOperationException("Use sample(x, y, z, params) instead");
    }

    /**
     * Convert density to height (find threshold crossing)
     *
     * Searches vertically from top to bottom to find where density crosses 0
     */
    public int densityToHeight(int x, int z, MultiNoiseParameters params) {
        int minY = 0;
        int maxY = 256;

        // Search from top down to find highest solid block
        for (int y = maxY; y >= minY; y--) {
            if (sample(x, y, z, params) > 0) {
                return y;
            }
        }

        return minY;  // Bedrock/ocean floor
    }

    /**
     * Optimized binary search version for density-to-height conversion
     * Use this for better performance when generating chunks
     */
    public int densityToHeightBinarySearch(int x, int z, MultiNoiseParameters params) {
        int minY = 0;
        int maxY = 256;

        // Binary search for the transition point
        int low = minY;
        int high = maxY;

        while (high - low > 1) {
            int mid = (low + high) / 2;
            float density = sample(x, mid, z, params);

            if (density > 0) {
                low = mid;  // Solid, search higher
            } else {
                high = mid;  // Air, search lower
            }
        }

        return low;
    }
}
