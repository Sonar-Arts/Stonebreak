package com.stonebreak.world.generation.config;

/**
 * Centralized configuration for terrain generation parameters.
 *
 * Consolidates all magic numbers and tunable parameters into a single
 * immutable configuration class following the Builder pattern.
 *
 * Benefits:
 * - Single source of truth for terrain parameters
 * - Easy to tune and experiment with different world generation settings
 * - Supports multiple world generation profiles (default, amplified, flat, etc.)
 * - Immutable for thread safety
 *
 * Follows SOLID principles:
 * - SRP: Only holds configuration data
 * - OCP: Extensible via builder pattern
 * - DIP: Configuration injected into generators
 */
public class TerrainGenerationConfig {

    // ========== Biome Blending Configuration ==========

    /**
     * Radius of the biome sampling grid for blending.
     * - 2 creates a 5x5 grid (25 samples)
     * - 1 creates a 3x3 grid (9 samples)
     */
    public final int biomeBlendSampleRadius;

    /**
     * Spacing between biome samples in world blocks.
     * Larger values create broader transitions but may miss small biomes.
     */
    public final int biomeBlendSampleSpacing;

    /**
     * Maximum distance for blending influence in grid units.
     * Samples farther than this have zero weight.
     */
    public final float biomeBlendDistance;

    /**
     * Minimum weight threshold for including a biome in blend results.
     * Biomes with weight below this are excluded to reduce noise.
     */
    public final float biomeBlendMinWeightThreshold;

    // ========== Climate Configuration ==========

    /**
     * Temperature decreases by this amount per block of altitude above sea level.
     * Formula: temperature -= (height - SEA_LEVEL) / altitudeChillFactor
     * Factor of 200 means 200 blocks = 1.0 temperature decrease (full cold → hot range)
     */
    public final float altitudeChillFactor;

    /**
     * Scale factor for moisture noise sampling.
     * Larger values create broader moisture zones.
     */
    public final float moistureNoiseScale;

    /**
     * Scale factor for temperature noise sampling.
     * Larger values create broader temperature zones.
     */
    public final float temperatureNoiseScale;

    // ========== Height Map Configuration ==========

    /**
     * Scale factor for continentalness noise sampling.
     * Larger values create broader landmass distribution.
     */
    public final float continentalnessNoiseScale;

    // ========== Erosion Configuration ==========

    /**
     * Scale factor for erosion/detail noise sampling.
     * Smaller values create finer detail, higher frequency variation.
     */
    public final float erosionNoiseScale;

    /**
     * Default erosion strength multiplier.
     * Typical range: 0.03-0.07 (3-7% height variation)
     */
    public final float erosionStrengthFactor;

    // ========== Private Constructor ==========

    private TerrainGenerationConfig(Builder builder) {
        this.biomeBlendSampleRadius = builder.biomeBlendSampleRadius;
        this.biomeBlendSampleSpacing = builder.biomeBlendSampleSpacing;
        this.biomeBlendDistance = builder.biomeBlendDistance;
        this.biomeBlendMinWeightThreshold = builder.biomeBlendMinWeightThreshold;
        this.altitudeChillFactor = builder.altitudeChillFactor;
        this.moistureNoiseScale = builder.moistureNoiseScale;
        this.temperatureNoiseScale = builder.temperatureNoiseScale;
        this.continentalnessNoiseScale = builder.continentalnessNoiseScale;
        this.erosionNoiseScale = builder.erosionNoiseScale;
        this.erosionStrengthFactor = builder.erosionStrengthFactor;
    }

    // ========== Default Configuration Factory ==========

    /**
     * Creates a default terrain generation configuration.
     * These values match the current hardcoded constants.
     *
     * @return Default configuration
     */
    public static TerrainGenerationConfig defaultConfig() {
        return new Builder().build();
    }

    // ========== Builder Pattern ==========

    public static class Builder {
        // Biome Blending - Defaults
        private int biomeBlendSampleRadius = 2;
        private int biomeBlendSampleSpacing = 8;
        private float biomeBlendDistance = 32.0f;
        private float biomeBlendMinWeightThreshold = 0.01f;

        // Climate - Defaults
        private float altitudeChillFactor = 200.0f;
        private float moistureNoiseScale = 200.0f;
        private float temperatureNoiseScale = 300.0f;

        // Height Map - Defaults
        private float continentalnessNoiseScale = 800.0f;

        // Erosion - Defaults
        private float erosionNoiseScale = 40.0f;
        private float erosionStrengthFactor = 0.05f;

        // Biome Blending Setters

        public Builder biomeBlendSampleRadius(int radius) {
            if (radius < 1 || radius > 5) {
                throw new IllegalArgumentException("Sample radius must be between 1 and 5, got: " + radius);
            }
            this.biomeBlendSampleRadius = radius;
            return this;
        }

        public Builder biomeBlendSampleSpacing(int spacing) {
            if (spacing < 1) {
                throw new IllegalArgumentException("Sample spacing must be positive, got: " + spacing);
            }
            this.biomeBlendSampleSpacing = spacing;
            return this;
        }

        public Builder biomeBlendDistance(float distance) {
            if (distance <= 0) {
                throw new IllegalArgumentException("Blend distance must be positive, got: " + distance);
            }
            this.biomeBlendDistance = distance;
            return this;
        }

        public Builder biomeBlendMinWeightThreshold(float threshold) {
            if (threshold < 0 || threshold > 1) {
                throw new IllegalArgumentException("Min weight threshold must be in [0, 1], got: " + threshold);
            }
            this.biomeBlendMinWeightThreshold = threshold;
            return this;
        }

        // Climate Setters

        public Builder altitudeChillFactor(float factor) {
            if (factor <= 0) {
                throw new IllegalArgumentException("Altitude chill factor must be positive, got: " + factor);
            }
            this.altitudeChillFactor = factor;
            return this;
        }

        public Builder moistureNoiseScale(float scale) {
            if (scale <= 0) {
                throw new IllegalArgumentException("Moisture noise scale must be positive, got: " + scale);
            }
            this.moistureNoiseScale = scale;
            return this;
        }

        public Builder temperatureNoiseScale(float scale) {
            if (scale <= 0) {
                throw new IllegalArgumentException("Temperature noise scale must be positive, got: " + scale);
            }
            this.temperatureNoiseScale = scale;
            return this;
        }

        // Height Map Setters

        public Builder continentalnessNoiseScale(float scale) {
            if (scale <= 0) {
                throw new IllegalArgumentException("Continentalness noise scale must be positive, got: " + scale);
            }
            this.continentalnessNoiseScale = scale;
            return this;
        }

        // Erosion Setters

        public Builder erosionNoiseScale(float scale) {
            if (scale <= 0) {
                throw new IllegalArgumentException("Erosion noise scale must be positive, got: " + scale);
            }
            this.erosionNoiseScale = scale;
            return this;
        }

        public Builder erosionStrengthFactor(float factor) {
            if (factor < 0 || factor > 1) {
                throw new IllegalArgumentException("Erosion strength factor must be in [0, 1], got: " + factor);
            }
            this.erosionStrengthFactor = factor;
            return this;
        }

        /**
         * Builds the immutable configuration object.
         *
         * @return Terrain generation configuration
         */
        public TerrainGenerationConfig build() {
            return new TerrainGenerationConfig(this);
        }
    }

    @Override
    public String toString() {
        return "TerrainGenerationConfig{" +
                "biomeBlendSampleRadius=" + biomeBlendSampleRadius +
                ", biomeBlendSampleSpacing=" + biomeBlendSampleSpacing +
                ", biomeBlendDistance=" + biomeBlendDistance +
                ", biomeBlendMinWeightThreshold=" + biomeBlendMinWeightThreshold +
                ", altitudeChillFactor=" + altitudeChillFactor +
                ", moistureNoiseScale=" + moistureNoiseScale +
                ", temperatureNoiseScale=" + temperatureNoiseScale +
                ", continentalnessNoiseScale=" + continentalnessNoiseScale +
                ", erosionNoiseScale=" + erosionNoiseScale +
                ", erosionStrengthFactor=" + erosionStrengthFactor +
                '}';
    }
}
