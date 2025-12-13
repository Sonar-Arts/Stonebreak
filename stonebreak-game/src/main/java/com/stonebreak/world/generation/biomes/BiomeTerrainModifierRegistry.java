package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.biomes.modifiers.BadlandsMesaModifier;
import com.stonebreak.world.generation.biomes.modifiers.BiomeTerrainModifier;
import com.stonebreak.world.generation.biomes.modifiers.DesertDunesModifier;
import com.stonebreak.world.generation.biomes.modifiers.StonyPeaksModifier;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry for managing biome-specific terrain modifiers.
 *
 * Phase 2 Enhancement: Biome-Specific Height Modifiers
 *
 * This registry coordinates the application of biome modifiers after terrain generation,
 * creating a two-pass terrain generation system:
 *
 * Pass 1: TerrainGenerator (SPLINE/HYBRID_SDF) → Base terrain shape
 * Pass 2: BiomeTerrainModifierRegistry → Fine-tuned biome features
 *
 * Architecture:
 * - Uses EnumMap for fast biome → modifier lookup
 * - Modifiers are optional (not all biomes need them)
 * - Thread-safe (immutable after construction)
 *
 * Current modifiers:
 * - BADLANDS: Canyon carving, hoodoo/spire generation
 * - STONY_PEAKS: Vertical amplification, rocky outcrops
 * - DESERT: Rolling dune patterns
 * - RED_SAND_DESERT: Rolling dune patterns (reuses Desert modifier)
 *
 * Thread-safe (modifiers and map are immutable after construction).
 */
public class BiomeTerrainModifierRegistry {

    private final Map<BiomeType, BiomeTerrainModifier> modifiers;

    /**
     * Creates a new modifier registry with the given world seed.
     *
     * Registers modifiers for biomes that need terrain-specific features.
     * Biomes without modifiers use only the terrain hint system.
     *
     * @param seed World seed for deterministic generation
     */
    public BiomeTerrainModifierRegistry(long seed) {
        modifiers = new EnumMap<>(BiomeType.class);

        // Register modifiers for biomes that need fine-tuned features
        modifiers.put(BiomeType.BADLANDS, new BadlandsMesaModifier(seed));
        modifiers.put(BiomeType.STONY_PEAKS, new StonyPeaksModifier(seed));
        modifiers.put(BiomeType.DESERT, new DesertDunesModifier(seed));
        modifiers.put(BiomeType.RED_SAND_DESERT, new DesertDunesModifier(seed + 100)); // Slightly different seed

        // Note: Other biomes (PLAINS, TAIGA, etc.) don't have modifiers
        // They rely solely on terrain hints for their shape
    }

    /**
     * Applies the appropriate terrain modifier for the given biome.
     *
     * Two-pass generation:
     * 1. baseHeight comes from TerrainGenerator (with multi-noise parameters applied)
     * 2. This method applies biome-specific modifiers (if any)
     *
     * If no modifier exists for the biome, returns baseHeight unchanged.
     * If modifier exists but shouldApplyModifier returns false, returns baseHeight unchanged.
     *
     * @param biome The biome type at this location
     * @param baseHeight The height after terrain generation
     * @param params The multi-noise parameters at this location
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Modified height (or baseHeight if no modifier applies)
     */
    public int applyModifier(BiomeType biome, int baseHeight,
                             MultiNoiseParameters params, int x, int z) {
        BiomeTerrainModifier modifier = modifiers.get(biome);

        if (modifier != null && modifier.shouldApplyModifier(params, baseHeight)) {
            return modifier.modifyHeight(baseHeight, params, x, z);
        }

        return baseHeight;
    }

    /**
     * Checks if a modifier exists for the given biome.
     *
     * @param biome Biome type to check
     * @return True if a modifier is registered for this biome
     */
    public boolean hasModifier(BiomeType biome) {
        return modifiers.containsKey(biome);
    }

    /**
     * Gets the modifier for a specific biome (for debugging/testing).
     *
     * @param biome Biome type
     * @return The modifier, or null if none exists
     */
    public BiomeTerrainModifier getModifier(BiomeType biome) {
        return modifiers.get(biome);
    }

    /**
     * Gets the number of registered modifiers.
     *
     * @return Count of biomes with modifiers
     */
    public int getModifierCount() {
        return modifiers.size();
    }
}
