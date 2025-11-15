package com.stonebreak.world.generation.density;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfig;

/**
 * 3D noise-based density function
 *
 * Samples 3D noise to create caves, overhangs, and
 * natural terrain variation in the vertical dimension.
 */
public class NoiseDensityFunction implements DensityFunction {

    private final NoiseGenerator noise3D;
    private final float frequency;
    private final float amplitude;

    /**
     * Create 3D noise density function
     *
     * @param seed Random seed
     * @param frequency Noise frequency (higher = smaller features)
     * @param amplitude Noise amplitude (higher = stronger effect)
     */
    public NoiseDensityFunction(long seed, float frequency, float amplitude) {
        // Use low octave count for 3D noise (performance)
        NoiseConfig config = new NoiseConfig(3, 0.5, 2.0);
        this.noise3D = new NoiseGenerator(seed, config);
        this.frequency = frequency;
        this.amplitude = amplitude;
    }

    @Override
    public float sample(int x, int y, int z) {
        // Sample 3D noise at scaled coordinates
        float nx = x * frequency;
        float ny = y * frequency;
        float nz = z * frequency;

        // Get noise value (-1 to 1)
        float noise = noise3D.noise3D(nx, ny, nz);

        // Scale by amplitude
        return noise * amplitude;
    }
}
