package com.stonebreak.world.generation.climate;

import com.stonebreak.world.generation.biomes.BiomeType;

import java.util.Arrays;
import java.util.List;

/**
 * Defines climate region types for multi-scale biome distribution.
 *
 * Phase 1 Enhancement: Implements large-scale climate regions (10,000+ block scale)
 * that influence which biomes can spawn, creating realistic continental patterns
 * while preserving local variety.
 *
 * Each region defines allowed biomes based on ecological principles:
 * - OCEANIC: Open water, marine climates
 * - COASTAL: Transitional coastal zones
 * - CONTINENTAL_INTERIOR: Inland temperate regions
 * - POLAR: High latitude/altitude cold regions
 * - TROPICAL: Equatorial warm regions
 * - ARID: Dry continental interiors
 *
 * Follows SOLID principles:
 * - Single Responsibility: Only defines climate region classification
 * - Open/Closed: Extensible by adding new regions without modifying existing code
 */
public enum ClimateRegionType {

    /**
     * Open water, marine climates.
     * Continentalness: -1.0 to -0.5 (oceanic)
     * Allowed biomes: Ice Fields, Tundra, Gravel Beach
     */
    OCEANIC(Arrays.asList(
        BiomeType.ICE_FIELDS,
        BiomeType.TUNDRA,
        BiomeType.GRAVEL_BEACH
    )),

    /**
     * Transitional coastal zones between land and ocean.
     * Continentalness: -0.5 to -0.1 (coastal)
     * Allowed biomes: Gravel Beach, Plains, Taiga
     */
    COASTAL(Arrays.asList(
        BiomeType.GRAVEL_BEACH,
        BiomeType.PLAINS,
        BiomeType.TAIGA
    )),

    /**
     * Inland temperate regions.
     * Continentalness: -0.1 to 0.5 (continental interior)
     * Allowed biomes: Plains, Taiga, Snowy Plains
     */
    CONTINENTAL_INTERIOR(Arrays.asList(
        BiomeType.PLAINS,
        BiomeType.TAIGA,
        BiomeType.SNOWY_PLAINS
    )),

    /**
     * High latitude or high altitude cold regions.
     * Temperature: < 0.3 (cold)
     * Allowed biomes: Tundra, Ice Fields, Snowy Plains, Stony Peaks
     */
    POLAR(Arrays.asList(
        BiomeType.TUNDRA,
        BiomeType.ICE_FIELDS,
        BiomeType.SNOWY_PLAINS,
        BiomeType.STONY_PEAKS
    )),

    /**
     * Equatorial warm regions.
     * Temperature: > 0.7 (hot)
     * Allowed biomes: Red Sand Desert, Plains
     */
    TROPICAL(Arrays.asList(
        BiomeType.RED_SAND_DESERT,
        BiomeType.PLAINS
    )),

    /**
     * Dry continental interiors, deserts.
     * Moisture: < 0.3 (dry)
     * Continentalness: > -0.1 (not oceanic)
     * Allowed biomes: Desert, Red Sand Desert, Badlands, Stony Peaks
     */
    ARID(Arrays.asList(
        BiomeType.DESERT,
        BiomeType.RED_SAND_DESERT,
        BiomeType.BADLANDS,
        BiomeType.STONY_PEAKS
    ));

    private final List<BiomeType> allowedBiomes;

    /**
     * Creates a climate region type with the specified allowed biomes.
     *
     * @param allowedBiomes List of biomes that can spawn in this climate region
     */
    ClimateRegionType(List<BiomeType> allowedBiomes) {
        this.allowedBiomes = allowedBiomes;
    }

    /**
     * Gets the list of biomes allowed in this climate region.
     *
     * @return Unmodifiable list of allowed biome types
     */
    public List<BiomeType> getAllowedBiomes() {
        return allowedBiomes;
    }

    /**
     * Determines the climate region type based on continentalness, temperature, and moisture.
     *
     * Classification priority:
     * 1. Temperature-based regions (POLAR, TROPICAL) - override continentalness
     * 2. Moisture + continentalness (ARID) - dry inland regions
     * 3. Continentalness-based regions (OCEANIC, COASTAL, CONTINENTAL_INTERIOR)
     *
     * @param continentalness Continentalness value [-1.0, 1.0] (-1=oceanic, 1=inland)
     * @param temperature Temperature value [0.0, 1.0] (0=cold, 1=hot)
     * @param moisture Moisture value [0.0, 1.0] (0=dry, 1=wet)
     * @return The climate region type for the given parameters
     */
    public static ClimateRegionType determineRegion(float continentalness, float temperature, float moisture) {
        // Priority 1: Temperature-based regions (polar and tropical zones)
        if (temperature < 0.3f) {
            return POLAR;  // Cold regions everywhere
        }
        if (temperature > 0.7f) {
            return TROPICAL;  // Hot regions everywhere
        }

        // Priority 2: Arid regions (dry + not oceanic)
        if (moisture < 0.3f && continentalness > -0.1f) {
            return ARID;  // Dry inland deserts
        }

        // Priority 3: Continentalness-based regions (oceanic → coastal → continental)
        if (continentalness < -0.5f) {
            return OCEANIC;  // Open water
        }
        if (continentalness < -0.1f) {
            return COASTAL;  // Coastal transitional zones
        }

        // Default: Continental interior
        return CONTINENTAL_INTERIOR;  // Inland temperate regions
    }
}
