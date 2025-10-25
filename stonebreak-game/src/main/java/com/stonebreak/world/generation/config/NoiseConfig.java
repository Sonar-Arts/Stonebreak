package com.stonebreak.world.generation.config;

/**
 * Configuration for noise generation parameters.
 *
 * Encapsulates the key parameters that control fractal noise generation:
 * - Octaves: Number of noise layers to combine (more = more detail, slower)
 * - Persistence: How much each octave contributes (0.0-1.0, lower = smoother)
 * - Lacunarity: Frequency multiplier between octaves (typically 2.0)
 *
 * Follows Single Responsibility Principle - only holds noise configuration data.
 * Immutable for thread safety and predictable behavior.
 */
public class NoiseConfig {
    private final int octaves;
    private final double persistence;
    private final double lacunarity;

    /**
     * Creates a new noise configuration with the specified parameters.
     *
     * @param octaves Number of noise layers to combine (1-16 recommended)
     * @param persistence Amplitude decay factor (0.0-1.0, typically 0.4-0.6)
     * @param lacunarity Frequency multiplier (typically 2.0-2.5)
     */
    public NoiseConfig(int octaves, double persistence, double lacunarity) {
        if (octaves < 1 || octaves > 16) {
            throw new IllegalArgumentException("Octaves must be between 1 and 16, got: " + octaves);
        }
        if (persistence < 0.0 || persistence > 1.0) {
            throw new IllegalArgumentException("Persistence must be between 0.0 and 1.0, got: " + persistence);
        }
        if (lacunarity < 1.0) {
            throw new IllegalArgumentException("Lacunarity must be >= 1.0, got: " + lacunarity);
        }

        this.octaves = octaves;
        this.persistence = persistence;
        this.lacunarity = lacunarity;
    }

    /**
     * Gets the number of noise octaves.
     *
     * @return Number of octaves
     */
    public int getOctaves() {
        return octaves;
    }

    /**
     * Gets the persistence (amplitude decay) value.
     *
     * @return Persistence value
     */
    public double getPersistence() {
        return persistence;
    }

    /**
     * Gets the lacunarity (frequency multiplier) value.
     *
     * @return Lacunarity value
     */
    public double getLacunarity() {
        return lacunarity;
    }

    @Override
    public String toString() {
        return String.format("NoiseConfig{octaves=%d, persistence=%.2f, lacunarity=%.2f}",
                           octaves, persistence, lacunarity);
    }
}
