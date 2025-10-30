package com.stonebreak.world.generation.features.terrain;

/**
 * Configuration parameters for a terrain feature.
 *
 * Immutable value object containing all tunable parameters for feature generation.
 * Each feature type (caves, overhangs, arches) has its own configuration instance.
 *
 * Design Pattern: Builder pattern for clear, readable construction
 *
 * Based on research from Minecraft 1.18+ and other voxel games:
 * - Surface features: High threshold (0.5-0.7), tight scale (30-50), limited Y-range
 * - Cave systems: Low threshold (-0.1 to 0.2), large scale (60-90), underground only
 * - Rare features: Very high threshold (0.75+), additional constraints
 */
public class TerrainFeatureConfig {

    private final float noiseScale;
    private final float threshold;
    private final int yRangeMin;       // Relative to surface (e.g., -10 = 10 blocks below)
    private final int yRangeMax;       // Relative to surface (e.g., +5 = 5 blocks above)
    private final boolean enabled;

    private TerrainFeatureConfig(Builder builder) {
        this.noiseScale = builder.noiseScale;
        this.threshold = builder.threshold;
        this.yRangeMin = builder.yRangeMin;
        this.yRangeMax = builder.yRangeMax;
        this.enabled = builder.enabled;
    }

    /**
     * Gets the noise scale (larger = smoother features).
     *
     * Typical values:
     * - 30-40: Tight, detailed features (small arches)
     * - 40-50: Medium features (overhangs)
     * - 60-80: Large features (cave systems)
     * - 100+: Very large, smooth features (floating islands)
     *
     * @return Noise scale
     */
    public float getNoiseScale() {
        return noiseScale;
    }

    /**
     * Gets the density threshold for block removal.
     *
     * Blocks are removed if: (finalDensity < threshold) OR (noise > threshold)
     * depending on feature implementation.
     *
     * Typical values:
     * - -0.1 to 0.0: Easy to carve (deep caves)
     * - 0.1 to 0.3: Moderate carving (cave entrances)
     * - 0.5 to 0.7: Hard to carve (rare surface overhangs)
     * - 0.75+: Very rare (floating islands, special features)
     *
     * @return Threshold value
     */
    public float getThreshold() {
        return threshold;
    }

    /**
     * Gets the minimum Y-range relative to surface.
     *
     * Features only generate between [surface + yRangeMin, surface + yRangeMax].
     *
     * Examples:
     * - yRangeMin = -60: Starts 60 blocks below surface (deep caves)
     * - yRangeMin = -10: Starts 10 blocks below surface (shallow caves)
     * - yRangeMin = -5: Starts 5 blocks below surface (surface features)
     *
     * @return Minimum Y-range offset from surface
     */
    public int getYRangeMin() {
        return yRangeMin;
    }

    /**
     * Gets the maximum Y-range relative to surface.
     *
     * Examples:
     * - yRangeMax = -10: Ends 10 blocks below surface (underground only)
     * - yRangeMax = 0: Ends at surface (no overhangs)
     * - yRangeMax = +3: Ends 3 blocks above surface (allows overhangs)
     * - yRangeMax = +20: Ends 20 blocks above surface (allows tall overhangs)
     *
     * @return Maximum Y-range offset from surface
     */
    public int getYRangeMax() {
        return yRangeMax;
    }

    /**
     * Checks if this feature is enabled.
     *
     * @return true if feature should be generated
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Checks if a Y-position is within this feature's range.
     *
     * @param y             Block Y coordinate
     * @param surfaceHeight Surface height at this (x, z)
     * @return true if Y is within feature range
     */
    public boolean isInYRange(int y, int surfaceHeight) {
        return y >= surfaceHeight + yRangeMin && y <= surfaceHeight + yRangeMax;
    }

    /**
     * Creates a new builder for constructing configurations.
     *
     * @return New builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a preset configuration for surface overhangs (cliffs, ledges).
     *
     * Characteristics:
     * - High threshold (0.6) = rare, dramatic features
     * - Medium scale (45) = cliff-sized features
     * - Limited Y-range (-5 to +3) = only at surface
     *
     * @return Surface overhang configuration
     */
    public static TerrainFeatureConfig surfaceOverhang() {
        return builder()
                .noiseScale(45.0f)
                .threshold(0.6f)
                .yRange(-5, 3)
                .enabled(true)
                .build();
    }

    /**
     * Creates a preset configuration for natural arches (tunnels through rock).
     *
     * Characteristics:
     * - High threshold (0.55) = rare features
     * - Tighter scale (35) = arch-sized holes
     * - Surface-level Y-range (-8 to +3) = slightly deeper than overhangs
     *
     * @return Natural arch configuration
     */
    public static TerrainFeatureConfig naturalArch() {
        return builder()
                .noiseScale(35.0f)
                .threshold(0.55f)
                .yRange(-8, 3)
                .enabled(true)
                .build();
    }

    /**
     * Creates a preset configuration for moderate cave systems.
     *
     * Characteristics:
     * - Moderate threshold (0.15 shallow, depth-adjusted deeper) = balanced density
     * - Large scale (70) = spacious caves
     * - Underground only (starts -10, no upper limit for deep caves)
     *
     * @return Cave system configuration
     */
    public static TerrainFeatureConfig caveSystem() {
        return builder()
                .noiseScale(70.0f)
                .threshold(0.15f)
                .yRange(-200, -10)  // -10 = only underground, -200 = down to bedrock
                .enabled(true)
                .build();
    }

    /**
     * Creates a disabled configuration.
     *
     * @return Disabled configuration
     */
    public static TerrainFeatureConfig disabled() {
        return builder()
                .enabled(false)
                .build();
    }

    /**
     * Builder for TerrainFeatureConfig.
     */
    public static class Builder {
        private float noiseScale = 60.0f;
        private float threshold = 0.0f;
        private int yRangeMin = -10;
        private int yRangeMax = 20;
        private boolean enabled = true;

        /**
         * Sets the noise scale.
         *
         * @param noiseScale Scale value (larger = smoother)
         * @return This builder
         */
        public Builder noiseScale(float noiseScale) {
            this.noiseScale = noiseScale;
            return this;
        }

        /**
         * Sets the threshold.
         *
         * @param threshold Threshold value
         * @return This builder
         */
        public Builder threshold(float threshold) {
            this.threshold = threshold;
            return this;
        }

        /**
         * Sets the Y-range relative to surface.
         *
         * @param yMin Minimum offset from surface (negative = below)
         * @param yMax Maximum offset from surface (positive = above)
         * @return This builder
         */
        public Builder yRange(int yMin, int yMax) {
            this.yRangeMin = yMin;
            this.yRangeMax = yMax;
            return this;
        }

        /**
         * Sets whether this feature is enabled.
         *
         * @param enabled true to enable, false to disable
         * @return This builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return Immutable configuration instance
         */
        public TerrainFeatureConfig build() {
            return new TerrainFeatureConfig(this);
        }
    }
}
