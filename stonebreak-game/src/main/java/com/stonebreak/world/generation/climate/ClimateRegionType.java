package com.stonebreak.world.generation.climate;

import com.stonebreak.world.generation.biomes.BiomeType;

import java.util.List;

/**
 * Large-scale climate regions that constrain which biomes may appear at a given
 * temperature/moisture/continentalness combination. Produces realistic continental
 * patterns while preserving local Whittaker-based variety.
 */
public enum ClimateRegionType {
    OCEANIC(List.of(BiomeType.ICE_FIELDS, BiomeType.TUNDRA, BiomeType.GRAVEL_BEACH)),
    COASTAL(List.of(BiomeType.GRAVEL_BEACH, BiomeType.PLAINS, BiomeType.TAIGA)),
    CONTINENTAL_INTERIOR(List.of(BiomeType.PLAINS, BiomeType.TAIGA, BiomeType.SNOWY_PLAINS, BiomeType.MEADOW)),
    POLAR(List.of(BiomeType.TUNDRA, BiomeType.ICE_FIELDS, BiomeType.SNOWY_PLAINS, BiomeType.STONY_PEAKS)),
    TROPICAL(List.of(BiomeType.RED_SAND_DESERT, BiomeType.PLAINS)),
    ARID(List.of(BiomeType.DESERT, BiomeType.RED_SAND_DESERT, BiomeType.BADLANDS, BiomeType.STONY_PEAKS));

    private final List<BiomeType> allowedBiomes;

    ClimateRegionType(List<BiomeType> allowedBiomes) {
        this.allowedBiomes = allowedBiomes;
    }

    public List<BiomeType> getAllowedBiomes() {
        return allowedBiomes;
    }

    /**
     * Classifies a position into a region. Temperature/moisture extremes dominate;
     * continentalness decides the default inland/coastal/oceanic split.
     */
    public static ClimateRegionType determineRegion(float continentalness, float temperature, float moisture) {
        if (temperature < 0.3f) return POLAR;
        if (temperature > 0.7f) return TROPICAL;
        if (moisture < 0.3f && continentalness > -0.1f) return ARID;
        if (continentalness < -0.5f) return OCEANIC;
        if (continentalness < -0.1f) return COASTAL;
        return CONTINENTAL_INTERIOR;
    }
}
