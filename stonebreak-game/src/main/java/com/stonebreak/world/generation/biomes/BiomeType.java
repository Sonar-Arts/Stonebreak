package com.stonebreak.world.generation.biomes;

/**
 * Defines the different biome types in the game.
 */
public enum BiomeType {
    PLAINS,
    DESERT,
    RED_SAND_DESERT,
    SNOWY_PLAINS,
    TUNDRA,
    TAIGA,
    STONY_PEAKS,
    BEACH,
    ICE_FIELDS,
    BADLANDS,
    MEADOW,
    /** Open sea floor: every submerged column in Standard, the ocean ids in DaedalusTGM-Exp. Appended last: ordinals of existing biomes stay put. */
    OCEAN
}
