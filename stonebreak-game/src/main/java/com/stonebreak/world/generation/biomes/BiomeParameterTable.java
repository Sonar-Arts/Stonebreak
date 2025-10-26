package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.noise.Range;

import java.util.ArrayList;
import java.util.List;

/**
 * Lookup table defining parameter ranges for all biomes in the game.
 *
 * This class implements the multi-noise biome selection system where biomes
 * are chosen based on 6 climate/terrain parameters rather than just temperature
 * and moisture.
 *
 * Each biome defines acceptable ranges for:
 * - Continentalness (inland vs coastal)
 * - Erosion (flat vs mountainous)
 * - Peaks & Valleys (height variation)
 * - Weirdness (normal vs rare variants)
 * - Temperature (cold to hot)
 * - Humidity (dry to wet)
 *
 * Biome Selection Algorithm:
 * 1. Find all biomes whose parameter ranges contain the sample point
 * 2. If exactly one match: return that biome
 * 3. If multiple matches: return closest by weighted distance
 * 4. If no matches: return nearest by distance (fallback)
 *
 * Design Notes:
 * - Parameter ranges are tuned to ensure no "biome holes" (every point has a biome)
 * - Overlapping ranges allow smooth transitions
 * - Rare biomes use narrow ranges or high weirdness values
 * - Temperature and humidity have highest weight for biome identity
 *
 * Thread-safe (immutable after initialization).
 */
public class BiomeParameterTable {

    private final List<BiomeParameterPoint> biomes;

    /**
     * Creates a new biome parameter table with default biome definitions.
     */
    public BiomeParameterTable() {
        this.biomes = new ArrayList<>();
        initializeBiomes();
    }

    /**
     * Initializes all 10 biome parameter definitions.
     *
     * Parameter tuning philosophy:
     * - Temperature/Humidity are primary biome characteristics (wider ranges)
     * - Erosion differentiates flat vs mountainous variants of same climate
     * - Continentalness separates ocean/coast/inland biomes
     * - Weirdness enables rare biome variants
     * - PV is secondary (most biomes accept wide PV ranges)
     */
    private void initializeBiomes() {
        // ========== DESERT BIOMES (Hot, Dry) ==========

        // DESERT: Flat, hot, dry
        addBiome(
                BiomeType.DESERT,
                new Range(-0.2f, 1.0f),   // Continentalness: coast to inland
                new Range(0.4f, 1.0f),    // Erosion: flat to moderately flat
                new Range(-0.5f, 0.5f),   // PV: any moderate
                new Range(-1.0f, 0.5f),   // Weirdness: normal (not badlands)
                new Range(0.65f, 1.0f),   // Temperature: hot
                new Range(0.0f, 0.25f)    // Humidity: very dry
        );

        // RED_SAND_DESERT: Hilly, hot, dry, slightly weird
        addBiome(
                BiomeType.RED_SAND_DESERT,
                new Range(0.0f, 1.0f),    // Continentalness: inland
                new Range(-0.2f, 0.6f),   // Erosion: hilly to moderate
                new Range(-0.7f, 0.7f),   // PV: varied
                new Range(0.3f, 0.7f),    // Weirdness: somewhat weird (volcanic)
                new Range(0.7f, 1.0f),    // Temperature: very hot
                new Range(0.0f, 0.2f)     // Humidity: extremely dry
        );

        // BADLANDS: Mountainous mesas, hot, dry, high weirdness
        addBiome(
                BiomeType.BADLANDS,
                new Range(0.0f, 1.0f),    // Continentalness: inland
                new Range(-0.6f, 0.2f),   // Erosion: mountainous to hilly
                new Range(-0.8f, 0.8f),   // PV: varied (creates mesas)
                new Range(0.6f, 1.0f),    // Weirdness: high (terracing/plateaus)
                new Range(0.6f, 1.0f),    // Temperature: hot
                new Range(0.0f, 0.3f)     // Humidity: dry
        );

        // ========== TEMPERATE BIOMES (Moderate Temperature) ==========

        // PLAINS: Flat, temperate, moderate moisture
        addBiome(
                BiomeType.PLAINS,
                new Range(-0.1f, 1.0f),   // Continentalness: coast to inland
                new Range(0.5f, 1.0f),    // Erosion: very flat
                new Range(-0.4f, 0.4f),   // PV: gentle
                new Range(-1.0f, 0.5f),   // Weirdness: normal
                new Range(0.35f, 0.7f),   // Temperature: temperate
                new Range(0.25f, 0.7f)    // Humidity: moderate
        );

        // GRAVEL_BEACH: Coastal, any temperature, high erosion
        addBiome(
                BiomeType.GRAVEL_BEACH,
                new Range(-0.5f, 0.1f),   // Continentalness: coastal zone
                new Range(0.4f, 1.0f),    // Erosion: flat (beaches are flat)
                new Range(-0.5f, 0.5f),   // PV: gentle
                new Range(-1.0f, 1.0f),   // Weirdness: any
                new Range(0.2f, 0.9f),    // Temperature: wide range
                new Range(0.2f, 0.9f)     // Humidity: wide range
        );

        // STONY_PEAKS: Very mountainous, cold (altitude), any humidity
        addBiome(
                BiomeType.STONY_PEAKS,
                new Range(-0.2f, 1.0f),   // Continentalness: anywhere
                new Range(-1.0f, -0.4f),  // Erosion: very mountainous
                new Range(0.2f, 1.0f),    // PV: high peaks
                new Range(-1.0f, 1.0f),   // Weirdness: any
                new Range(0.0f, 0.35f),   // Temperature: cold (altitude-adjusted)
                new Range(0.0f, 1.0f)     // Humidity: any
        );

        // ========== COLD/SNOWY BIOMES (Low Temperature) ==========

        // SNOWY_PLAINS: Flat, cold, moderate moisture
        addBiome(
                BiomeType.SNOWY_PLAINS,
                new Range(-0.1f, 1.0f),   // Continentalness: coast to inland
                new Range(0.4f, 1.0f),    // Erosion: flat
                new Range(-0.5f, 0.5f),   // PV: gentle
                new Range(-1.0f, 0.6f),   // Weirdness: normal
                new Range(0.0f, 0.3f),    // Temperature: very cold
                new Range(0.3f, 0.8f)     // Humidity: moderate to wet
        );

        // TAIGA: Hilly forests, cold, wet
        addBiome(
                BiomeType.TAIGA,
                new Range(0.0f, 1.0f),    // Continentalness: inland
                new Range(0.0f, 0.7f),    // Erosion: hilly to moderate
                new Range(-0.6f, 0.6f),   // PV: varied
                new Range(-1.0f, 0.5f),   // Weirdness: normal
                new Range(0.05f, 0.4f),   // Temperature: cold
                new Range(0.5f, 1.0f)     // Humidity: wet (forests need moisture)
        );

        // TUNDRA: Barren, very cold, dry
        addBiome(
                BiomeType.TUNDRA,
                new Range(0.0f, 1.0f),    // Continentalness: inland
                new Range(0.2f, 0.8f),    // Erosion: moderate
                new Range(-0.5f, 0.5f),   // PV: moderate
                new Range(-1.0f, 0.6f),   // Weirdness: normal
                new Range(0.0f, 0.25f),   // Temperature: very cold
                new Range(0.0f, 0.4f)     // Humidity: dry
        );

        // ICE_FIELDS: Flat glaciers, extremely cold, any moisture
        addBiome(
                BiomeType.ICE_FIELDS,
                new Range(-0.1f, 1.0f),   // Continentalness: anywhere
                new Range(0.5f, 1.0f),    // Erosion: very flat (glaciers)
                new Range(-0.4f, 0.4f),   // PV: gentle
                new Range(-1.0f, 0.7f),   // Weirdness: normal
                new Range(0.0f, 0.2f),    // Temperature: extremely cold
                new Range(0.0f, 1.0f)     // Humidity: any
        );

        System.out.println("Initialized " + biomes.size() + " biome parameter points");
    }

    /**
     * Helper method to add a biome parameter point.
     */
    private void addBiome(
            BiomeType biome,
            Range continentalness,
            Range erosion,
            Range peaksValleys,
            Range weirdness,
            Range temperature,
            Range humidity
    ) {
        biomes.add(new BiomeParameterPoint(
                biome, continentalness, erosion, peaksValleys, weirdness, temperature, humidity
        ));
    }

    /**
     * Finds all biomes that match the given parameters.
     *
     * A biome matches if all its parameter ranges contain the corresponding parameter values.
     *
     * @param params Multi-noise parameters to check
     * @return List of matching biome parameter points (may be empty)
     */
    public List<BiomeParameterPoint> findMatches(MultiNoiseParameters params) {
        List<BiomeParameterPoint> matches = new ArrayList<>();

        for (BiomeParameterPoint point : biomes) {
            if (point.matches(params)) {
                matches.add(point);
            }
        }

        return matches;
    }

    /**
     * Finds the nearest biome by 6D Euclidean distance.
     *
     * Used as fallback when no exact match exists (should be rare with proper tuning).
     *
     * @param params Multi-noise parameters
     * @return Nearest biome parameter point
     */
    public BiomeParameterPoint findNearest(MultiNoiseParameters params) {
        if (biomes.isEmpty()) {
            throw new IllegalStateException("Biome parameter table is empty!");
        }

        BiomeParameterPoint nearest = biomes.get(0);
        double minDistance = nearest.distanceTo(params);

        for (int i = 1; i < biomes.size(); i++) {
            BiomeParameterPoint point = biomes.get(i);
            double distance = point.distanceTo(params);

            if (distance < minDistance) {
                minDistance = distance;
                nearest = point;
            }
        }

        return nearest;
    }

    /**
     * Finds the nearest biome by weighted distance.
     *
     * Weighted distance prioritizes temperature and humidity for biome identity.
     *
     * @param params Multi-noise parameters
     * @return Nearest biome parameter point (weighted)
     */
    public BiomeParameterPoint findNearestWeighted(MultiNoiseParameters params) {
        if (biomes.isEmpty()) {
            throw new IllegalStateException("Biome parameter table is empty!");
        }

        BiomeParameterPoint nearest = biomes.get(0);
        double minDistance = nearest.weightedDistanceTo(params);

        for (int i = 1; i < biomes.size(); i++) {
            BiomeParameterPoint point = biomes.get(i);
            double distance = point.weightedDistanceTo(params);

            if (distance < minDistance) {
                minDistance = distance;
                nearest = point;
            }
        }

        return nearest;
    }

    /**
     * Selects the best matching biome for the given parameters.
     *
     * Algorithm:
     * 1. Find all exact matches (all ranges contain parameters)
     * 2. If 0 matches: return nearest by weighted distance (fallback)
     * 3. If 1 match: return that biome
     * 4. If multiple matches: return closest by weighted distance
     *
     * @param params Multi-noise parameters
     * @return Best matching biome type
     */
    public BiomeType selectBiome(MultiNoiseParameters params) {
        List<BiomeParameterPoint> matches = findMatches(params);

        if (matches.isEmpty()) {
            // No exact match - find nearest (should be rare)
            return findNearestWeighted(params).biome;
        }

        if (matches.size() == 1) {
            // Exact single match
            return matches.get(0).biome;
        }

        // Multiple matches - choose closest by weighted distance
        BiomeParameterPoint best = matches.get(0);
        double minDistance = best.weightedDistanceTo(params);

        for (int i = 1; i < matches.size(); i++) {
            BiomeParameterPoint point = matches.get(i);
            double distance = point.weightedDistanceTo(params);

            if (distance < minDistance) {
                minDistance = distance;
                best = point;
            }
        }

        return best.biome;
    }

    /**
     * Gets all biome parameter points (for debugging/visualization).
     *
     * @return Unmodifiable list of all biome parameter points
     */
    public List<BiomeParameterPoint> getAllBiomes() {
        return List.copyOf(biomes);
    }

    /**
     * Gets the number of biomes in the table.
     *
     * @return Biome count
     */
    public int size() {
        return biomes.size();
    }
}
