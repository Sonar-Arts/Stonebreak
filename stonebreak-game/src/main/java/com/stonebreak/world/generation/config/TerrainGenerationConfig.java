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
     * Phase 1: Increased from 200.0 to 1500.0 for larger biomes (7.5x increase).
     */
    public final float moistureNoiseScale;

    /**
     * Scale factor for temperature noise sampling.
     * Larger values create broader temperature zones.
     * Phase 1: Increased from 300.0 to 2000.0 for larger biomes (6.7x increase).
     */
    public final float temperatureNoiseScale;

    /**
     * Scale factor for continentalness climate noise sampling.
     * Very large scale (10,000 blocks) for massive continental patterns.
     * Phase 1: Used by ClimateRegionManager to determine oceanic vs coastal vs inland regions.
     */
    public final float continentalnessClimateNoiseScale;

    /**
     * Scale factor for region weirdness noise sampling.
     * Large scale (8,000 blocks) for regional variety.
     * Phase 1: Used by ClimateRegionManager to add variety to climate regions.
     */
    public final float regionWeirdnessNoiseScale;

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

    // ========== 3D Terrain Configuration ==========

    /**
     * Whether to enable 3D density-based terrain features.
     * When true, uses 3D noise to create overhangs, caves, and arches.
     * When false, uses traditional 2D heightmap only (faster).
     */
    public final boolean enable3DTerrain;

    /**
     * Density threshold for solid/air determination.
     * Values > threshold are solid, values < threshold are air.
     * Typical: 0.0 (balanced), negative values create more air, positive values create more solid.
     */
    public final float densityThreshold;

    /**
     * Scale factor for 3D density noise sampling.
     * Larger values create smoother, broader 3D features.
     * Smaller values create more intricate, detailed features.
     */
    public final float densityScale;

    /**
     * Start of 3D sampling transition zone (relative to surface height).
     * Example: -10 means start sampling 10 blocks below surface.
     */
    public final int densityTransitionZoneMin;

    /**
     * End of 3D sampling transition zone (relative to surface height).
     * Example: +20 means stop sampling 20 blocks above surface.
     */
    public final int densityTransitionZoneMax;

    // ========== Private Constructor ==========

    private TerrainGenerationConfig(Builder builder) {
        this.biomeBlendSampleRadius = builder.biomeBlendSampleRadius;
        this.biomeBlendSampleSpacing = builder.biomeBlendSampleSpacing;
        this.biomeBlendDistance = builder.biomeBlendDistance;
        this.biomeBlendMinWeightThreshold = builder.biomeBlendMinWeightThreshold;
        this.altitudeChillFactor = builder.altitudeChillFactor;
        this.moistureNoiseScale = builder.moistureNoiseScale;
        this.temperatureNoiseScale = builder.temperatureNoiseScale;
        this.continentalnessClimateNoiseScale = builder.continentalnessClimateNoiseScale;
        this.regionWeirdnessNoiseScale = builder.regionWeirdnessNoiseScale;
        this.continentalnessNoiseScale = builder.continentalnessNoiseScale;
        this.erosionNoiseScale = builder.erosionNoiseScale;
        this.erosionStrengthFactor = builder.erosionStrengthFactor;
        this.enable3DTerrain = builder.enable3DTerrain;
        this.densityThreshold = builder.densityThreshold;
        this.densityScale = builder.densityScale;
        this.densityTransitionZoneMin = builder.densityTransitionZoneMin;
        this.densityTransitionZoneMax = builder.densityTransitionZoneMax;
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
        private float moistureNoiseScale = 1500.0f;  // Phase 1: Increased from 200.0 (7.5x)
        private float temperatureNoiseScale = 2000.0f;  // Phase 1: Increased from 300.0 (6.7x)
        private float continentalnessClimateNoiseScale = 10000.0f;  // Phase 1: New field
        private float regionWeirdnessNoiseScale = 8000.0f;  // Phase 1: New field

        // Height Map - Defaults
        private float continentalnessNoiseScale = 800.0f;

        // Erosion - Defaults
        private float erosionNoiseScale = 40.0f;
        private float erosionStrengthFactor = 0.05f;

        // 3D Terrain - Defaults
        private boolean enable3DTerrain = true;  // Enable 3D features by default
        private float densityThreshold = 0.0f;  // Balanced threshold
        private float densityScale = 60.0f;  // Smooth 3D features
        private int densityTransitionZoneMin = -10;  // Start 10 blocks below surface
        private int densityTransitionZoneMax = 20;  // End 20 blocks above surface

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

        public Builder continentalnessClimateNoiseScale(float scale) {
            if (scale <= 0) {
                throw new IllegalArgumentException("Continentalness climate noise scale must be positive, got: " + scale);
            }
            this.continentalnessClimateNoiseScale = scale;
            return this;
        }

        public Builder regionWeirdnessNoiseScale(float scale) {
            if (scale <= 0) {
                throw new IllegalArgumentException("Region weirdness noise scale must be positive, got: " + scale);
            }
            this.regionWeirdnessNoiseScale = scale;
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

        // 3D Terrain Setters

        public Builder enable3DTerrain(boolean enable) {
            this.enable3DTerrain = enable;
            return this;
        }

        public Builder densityThreshold(float threshold) {
            if (threshold < -1.0f || threshold > 1.0f) {
                throw new IllegalArgumentException("Density threshold must be in [-1, 1], got: " + threshold);
            }
            this.densityThreshold = threshold;
            return this;
        }

        public Builder densityScale(float scale) {
            if (scale <= 0) {
                throw new IllegalArgumentException("Density scale must be positive, got: " + scale);
            }
            this.densityScale = scale;
            return this;
        }

        public Builder densityTransitionZoneMin(int min) {
            if (min > 0) {
                throw new IllegalArgumentException("Transition zone min must be <= 0, got: " + min);
            }
            this.densityTransitionZoneMin = min;
            return this;
        }

        public Builder densityTransitionZoneMax(int max) {
            if (max < 0) {
                throw new IllegalArgumentException("Transition zone max must be >= 0, got: " + max);
            }
            this.densityTransitionZoneMax = max;
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
                ", continentalnessClimateNoiseScale=" + continentalnessClimateNoiseScale +
                ", regionWeirdnessNoiseScale=" + regionWeirdnessNoiseScale +
                ", continentalnessNoiseScale=" + continentalnessNoiseScale +
                ", erosionNoiseScale=" + erosionNoiseScale +
                ", erosionStrengthFactor=" + erosionStrengthFactor +
                ", enable3DTerrain=" + enable3DTerrain +
                ", densityThreshold=" + densityThreshold +
                ", densityScale=" + densityScale +
                ", densityTransitionZoneMin=" + densityTransitionZoneMin +
                ", densityTransitionZoneMax=" + densityTransitionZoneMax +
                '}';
    }
}
