package com.stonebreak.world.generation.biomes;

/**
 * Defines the different biome types in the game.
 * Phase 4: Expanded from 4 to 10 biomes with diverse climate zones.
 */
public enum BiomeType {
    // Original 4 biomes
    PLAINS,
    DESERT,
    RED_SAND_DESERT,
    SNOWY_PLAINS,

    // Phase 4: New biomes
    TUNDRA,          // Cold + Dry - Flat frozen wasteland
    TAIGA,           // Cold + Moderate - Forested hills
    STONY_PEAKS,     // Cool-Cold + Very Dry - Rocky mountains
    GRAVEL_BEACH,    // Temperate + Moderate - Coastal shoreline
    ICE_FIELDS,      // Very Cold + Very Wet - Glaciers
    BADLANDS,        // Very Hot + Very Dry - Eroded plateaus

    // Ocean biomes
    OCEAN,           // Standard ocean - moderate depth
    DEEP_OCEAN,      // Deep ocean - maximum depth
    FROZEN_OCEAN;    // Frozen ocean - ice surface
}