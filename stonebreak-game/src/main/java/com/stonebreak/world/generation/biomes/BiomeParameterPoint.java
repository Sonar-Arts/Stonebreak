package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.noise.Range;

/**
 * Defines a biome's acceptable parameter ranges in 6D parameter space.
 *
 * Each biome has specific requirements for where it can generate:
 * - Continentalness: Inland vs coastal placement
 * - Erosion: Flat vs mountainous terrain preference
 * - Peaks & Valleys: Height variation tolerance
 * - Weirdness: Normal vs rare biome variants
 * - Temperature: Cold to hot climate
 * - Humidity: Dry to wet climate
 *
 * When world generation samples parameters at a location, it checks which
 * biome parameter points match. If multiple match, it chooses the closest
 * by 6D Euclidean distance.
 *
 * Example:
 * <pre>
 * // Desert: Hot, dry, prefers flat terrain
 * new BiomeParameterPoint(
 *     BiomeType.DESERT,
 *     new Range(0.0f, 1.0f),   // Any continentalness (inland)
 *     new Range(0.3f, 1.0f),   // High erosion (flat)
 *     new Range(-0.5f, 0.5f),  // Moderate PV
 *     new Range(-1.0f, 0.5f),  // Normal weirdness
 *     new Range(0.6f, 1.0f),   // Hot temperature
 *     new Range(0.0f, 0.3f)    // Dry humidity
 * );
 * </pre>
 *
 * Immutable for thread safety.
 */
public class BiomeParameterPoint {

    public final BiomeType biome;
    public final Range continentalness;
    public final Range erosion;
    public final Range peaksValleys;
    public final Range weirdness;
    public final Range temperature;
    public final Range humidity;

    /**
     * Creates a new biome parameter point defining where a biome can generate.
     *
     * @param biome Biome type
     * @param continentalness Acceptable continentalness range [-1, 1]
     * @param erosion Acceptable erosion range [-1, 1]
     * @param peaksValleys Acceptable PV range [-1, 1]
     * @param weirdness Acceptable weirdness range [-1, 1]
     * @param temperature Acceptable temperature range [0, 1]
     * @param humidity Acceptable humidity range [0, 1]
     */
    public BiomeParameterPoint(
            BiomeType biome,
            Range continentalness,
            Range erosion,
            Range peaksValleys,
            Range weirdness,
            Range temperature,
            Range humidity
    ) {
        this.biome = biome;
        this.continentalness = continentalness;
        this.erosion = erosion;
        this.peaksValleys = peaksValleys;
        this.weirdness = weirdness;
        this.temperature = temperature;
        this.humidity = humidity;
    }

    /**
     * Checks if given parameters match this biome's requirements.
     *
     * All six parameters must fall within their respective ranges.
     *
     * @param params Multi-noise parameters to check
     * @return true if all parameters are within acceptable ranges
     */
    public boolean matches(MultiNoiseParameters params) {
        return continentalness.contains(params.continentalness) &&
               erosion.contains(params.erosion) &&
               peaksValleys.contains(params.peaksValleys) &&
               weirdness.contains(params.weirdness) &&
               temperature.contains(params.temperature) &&
               humidity.contains(params.humidity);
    }

    /**
     * Calculates 6D Euclidean distance from parameters to this biome's center point.
     *
     * Used for selecting the best biome when multiple biomes match, or finding
     * the nearest biome when no exact match exists.
     *
     * Distance calculated to the center of each parameter range.
     *
     * @param params Multi-noise parameters
     * @return Euclidean distance in 6D space
     */
    public double distanceTo(MultiNoiseParameters params) {
        // Calculate distance to center of each range
        double d1 = params.continentalness - continentalness.center();
        double d2 = params.erosion - erosion.center();
        double d3 = params.peaksValleys - peaksValleys.center();
        double d4 = params.weirdness - weirdness.center();
        double d5 = params.temperature - temperature.center();
        double d6 = params.humidity - humidity.center();

        return Math.sqrt(
                d1 * d1 +
                d2 * d2 +
                d3 * d3 +
                d4 * d4 +
                d5 * d5 +
                d6 * d6
        );
    }

    /**
     * Calculates weighted distance considering parameter importance.
     *
     * Some parameters are more important for biome identity than others.
     * Temperature and humidity are weighted higher as they're primary biome characteristics.
     *
     * @param params Multi-noise parameters
     * @return Weighted distance
     */
    public double weightedDistanceTo(MultiNoiseParameters params) {
        double d1 = (params.continentalness - continentalness.center()) * 0.8;  // Moderate weight
        double d2 = (params.erosion - erosion.center()) * 0.7;  // Lower weight
        double d3 = (params.peaksValleys - peaksValleys.center()) * 0.5;  // Lowest weight
        double d4 = (params.weirdness - weirdness.center()) * 0.6;  // Low weight
        double d5 = (params.temperature - temperature.center()) * 1.2;  // High weight
        double d6 = (params.humidity - humidity.center()) * 1.2;  // High weight

        return Math.sqrt(
                d1 * d1 +
                d2 * d2 +
                d3 * d3 +
                d4 * d4 +
                d5 * d5 +
                d6 * d6
        );
    }

    @Override
    public String toString() {
        return String.format(
                "BiomeParameterPoint{%s, C:%s, E:%s, PV:%s, W:%s, T:%s, H:%s}",
                biome, continentalness, erosion, peaksValleys, weirdness, temperature, humidity
        );
    }
}
