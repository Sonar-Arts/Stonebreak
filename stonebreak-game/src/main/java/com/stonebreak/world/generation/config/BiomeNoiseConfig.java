package com.stonebreak.world.generation.config;

/**
 * Immutable configuration for biome-specific terrain noise weights.
 *
 * Defines how much each noise type (Peaks & Valleys, Ridged, Weirdness, 3D)
 * contributes to terrain generation for a specific biome. Different biomes
 * use different combinations to create distinct terrain characteristics.
 *
 * Examples:
 * - Mountains: High ridged + PV for jagged peaks
 * - Badlands: High weirdness for mesa plateaus
 * - Plains: Low PV for gentle hills
 * - Deserts: Minimal noise for flat terrain
 *
 * Immutable for thread safety and predictable behavior.
 * Follows Single Responsibility Principle - only holds noise weight configuration.
 */
public class BiomeNoiseConfig {

    private final float peaksValleysStrength;
    private final float ridgedStrength;
    private final float weirdnessStrength;
    private final int totalAmplitude;
    private final boolean enable3D;
    private final float overhangFrequency;

    /**
     * Creates a new biome noise configuration with specified weights.
     *
     * @param peaksValleysStrength Strength of Peaks & Valleys noise (0.0-1.0)
     * @param ridgedStrength       Strength of Ridged noise (0.0-1.0)
     * @param weirdnessStrength    Strength of Weirdness noise (0.0-1.0)
     * @param totalAmplitude       Total height variation in blocks (e.g., 40 = ±40 blocks)
     * @param enable3D             Whether to use 3D density terrain for overhangs
     * @param overhangFrequency    Frequency of 3D features (0.0-1.0, only if enable3D is true)
     */
    public BiomeNoiseConfig(
        float peaksValleysStrength,
        float ridgedStrength,
        float weirdnessStrength,
        int totalAmplitude,
        boolean enable3D,
        float overhangFrequency
    ) {
        this.peaksValleysStrength = clamp(peaksValleysStrength, 0.0f, 1.0f);
        this.ridgedStrength = clamp(ridgedStrength, 0.0f, 1.0f);
        this.weirdnessStrength = clamp(weirdnessStrength, 0.0f, 1.0f);
        this.totalAmplitude = Math.max(0, totalAmplitude);
        this.enable3D = enable3D;
        this.overhangFrequency = clamp(overhangFrequency, 0.0f, 1.0f);
    }

    /**
     * Gets the Peaks & Valleys noise strength.
     *
     * Higher values create more dramatic height differences with plateaus at extremes.
     *
     * @return PV strength (0.0-1.0)
     */
    public float getPeaksValleysStrength() {
        return peaksValleysStrength;
    }

    /**
     * Gets the Ridged noise strength.
     *
     * Higher values create sharper mountain ridges and peaks.
     *
     * @return Ridged strength (0.0-1.0)
     */
    public float getRidgedStrength() {
        return ridgedStrength;
    }

    /**
     * Gets the Weirdness noise strength.
     *
     * Higher values create more plateau/mesa formations and terraced hillsides.
     *
     * @return Weirdness strength (0.0-1.0)
     */
    public float getWeirdnessStrength() {
        return weirdnessStrength;
    }

    /**
     * Gets the total height amplitude for all noise types.
     *
     * This is the maximum height variation in blocks that can be added
     * to the base terrain height by the combined noise effects.
     *
     * Example: amplitude=40 means noise can add ±40 blocks to base height
     *
     * @return Total amplitude in blocks
     */
    public int getTotalAmplitude() {
        return totalAmplitude;
    }

    /**
     * Checks if 3D density terrain is enabled for this biome.
     *
     * When true, 3D noise is used to create overhangs, arches, and caves.
     * When false, traditional 2D heightmap generation is used exclusively.
     *
     * @return true if 3D terrain is enabled
     */
    public boolean is3DEnabled() {
        return enable3D;
    }

    /**
     * Gets the frequency of 3D terrain features (overhangs, caves).
     *
     * Only relevant if is3DEnabled() returns true.
     *
     * @return Overhang frequency (0.0-1.0)
     */
    public float getOverhangFrequency() {
        return overhangFrequency;
    }

    /**
     * Clamps a value between min and max.
     */
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }

    @Override
    public String toString() {
        return String.format(
            "BiomeNoiseConfig{PV=%.2f, Ridged=%.2f, Weird=%.2f, Amp=%d, 3D=%s, Freq=%.2f}",
            peaksValleysStrength,
            ridgedStrength,
            weirdnessStrength,
            totalAmplitude,
            enable3D,
            overhangFrequency
        );
    }
}
