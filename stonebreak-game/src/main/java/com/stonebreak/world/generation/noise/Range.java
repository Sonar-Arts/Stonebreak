package com.stonebreak.world.generation.noise;

/**
 * Represents a numeric range with minimum and maximum values.
 * Used for defining acceptable parameter ranges for biome selection.
 *
 * Immutable record for thread safety and simplicity.
 *
 * Example usage:
 * <pre>
 * Range temperatureRange = new Range(0.6f, 1.0f);  // Hot biomes
 * boolean isHot = temperatureRange.contains(0.8f);  // true
 * </pre>
 */
public record Range(float min, float max) {

    /**
     * Creates a new range with validation.
     *
     * @param min Minimum value (inclusive)
     * @param max Maximum value (inclusive)
     * @throws IllegalArgumentException if min > max
     */
    public Range {
        if (min > max) {
            throw new IllegalArgumentException(
                    String.format("Range min (%.2f) must be <= max (%.2f)", min, max)
            );
        }
    }

    /**
     * Checks if a value falls within this range (inclusive).
     *
     * @param value Value to check
     * @return true if min <= value <= max
     */
    public boolean contains(float value) {
        return value >= min && value <= max;
    }

    /**
     * Gets the center point of this range.
     *
     * @return (min + max) / 2
     */
    public float center() {
        return (min + max) / 2.0f;
    }

    /**
     * Gets the width/span of this range.
     *
     * @return max - min
     */
    public float span() {
        return max - min;
    }

    /**
     * Calculates distance from a value to this range.
     * Returns 0 if value is within range.
     *
     * @param value Value to check
     * @return Distance to nearest edge, or 0 if contained
     */
    public float distanceTo(float value) {
        if (contains(value)) {
            return 0.0f;
        } else if (value < min) {
            return min - value;
        } else {
            return value - max;
        }
    }

    @Override
    public String toString() {
        return String.format("[%.2f, %.2f]", min, max);
    }
}
