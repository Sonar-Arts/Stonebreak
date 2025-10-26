package com.stonebreak.world.generation.config;

import com.stonebreak.world.generation.biomes.BiomeType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Provides biome-specific terrain noise weight configurations.
 *
 * Each biome has unique noise weights that create distinct terrain characteristics:
 * - PLAINS: Gentle rolling hills with minimal variation
 * - DESERT: Flat with subtle sand dunes
 * - RED_SAND_DESERT: Volcanic rolling hills with ridges
 * - SNOWY_PLAINS: Moderate snow-covered hills
 * - TUNDRA: Rocky, uneven frozen terrain
 * - TAIGA: Forested hills with valleys
 * - STONY_PEAKS: Dramatic jagged mountains with sharp ridges
 * - GRAVEL_BEACH: Flat coastal transition zones
 * - ICE_FIELDS: Glacial pressure ridges and ice spikes
 * - BADLANDS: Mesa plateaus with terraced hillsides
 *
 * Follows Single Responsibility Principle - only manages noise weight mappings.
 * Immutable for thread safety.
 */
public class TerrainNoiseWeights {

    private final Map<BiomeType, BiomeNoiseConfig> configs;

    /**
     * Creates terrain noise weights with default configurations for all biomes.
     */
    public TerrainNoiseWeights() {
        this.configs = createDefaultConfigs();
    }

    /**
     * Gets the noise configuration for a specific biome type.
     *
     * @param biome The biome type
     * @return Noise configuration for the biome
     */
    public BiomeNoiseConfig getConfig(BiomeType biome) {
        return configs.getOrDefault(biome, getDefaultConfig());
    }

    /**
     * Creates default noise configurations for all biome types.
     *
     * Configuration format: (PV strength, Ridged strength, Weirdness strength, Amplitude, Enable3D, Overhang frequency)
     */
    private Map<BiomeType, BiomeNoiseConfig> createDefaultConfigs() {
        Map<BiomeType, BiomeNoiseConfig> map = new EnumMap<>(BiomeType.class);

        // PLAINS - Gentle rolling hills, depressions
        // SMOOTHING: Reduced strengths by 50%, amplitude by 40%
        map.put(BiomeType.PLAINS, new BiomeNoiseConfig(
            0.1f,   // peaksValleysStrength - gentle hills (was 0.2)
            0.0f,   // ridgedStrength - no sharp ridges
            0.05f,  // weirdnessStrength - occasional depressions (was 0.1)
            5,      // totalAmplitude - ±5 blocks (was 8)
            true,   // enable3D - occasional small overhangs
            0.1f    // overhangFrequency - rare (10%)
        ));

        // DESERT - Flat with subtle dunes
        // SMOOTHING: Reduced strengths by 50%, amplitude by 40%
        map.put(BiomeType.DESERT, new BiomeNoiseConfig(
            0.08f,  // peaksValleysStrength - very subtle (was 0.15)
            0.0f,   // ridgedStrength - no ridges
            0.03f,  // weirdnessStrength - minimal (was 0.05)
            3,      // totalAmplitude - ±3 blocks (was 5)
            false,  // enable3D - no 3D features (keep flat)
            0.0f    // overhangFrequency - none
        ));

        // RED_SAND_DESERT - Volcanic rolling hills, ridges
        // SMOOTHING: Reduced strengths by 50%, amplitude by 40%
        map.put(BiomeType.RED_SAND_DESERT, new BiomeNoiseConfig(
            0.25f,  // peaksValleysStrength - rolling volcanic bumps (was 0.5)
            0.15f,  // ridgedStrength - volcanic ridges (was 0.3)
            0.2f,   // weirdnessStrength - lava plateaus (was 0.4)
            12,     // totalAmplitude - ±12 blocks (was 20)
            true,   // enable3D - volcanic overhangs
            0.3f    // overhangFrequency - moderate (30%)
        ));

        // SNOWY_PLAINS - Moderate snow-covered hills
        // SMOOTHING: Reduced strengths by 50%, amplitude by 40%
        map.put(BiomeType.SNOWY_PLAINS, new BiomeNoiseConfig(
            0.15f,  // peaksValleysStrength - moderate variation (was 0.3)
            0.0f,   // ridgedStrength - no ridges
            0.05f,  // weirdnessStrength - slight variation (was 0.1)
            7,      // totalAmplitude - ±7 blocks (was 12)
            true,   // enable3D - occasional snow overhangs
            0.1f    // overhangFrequency - rare (10%)
        ));

        // TUNDRA - Rocky, uneven frozen terrain
        // SMOOTHING: Reduced strengths by 50%, amplitude by 40%
        map.put(BiomeType.TUNDRA, new BiomeNoiseConfig(
            0.2f,   // peaksValleysStrength - uneven terrain (was 0.4)
            0.1f,   // ridgedStrength - rocky outcrops (was 0.2)
            0.1f,   // weirdnessStrength - permafrost irregularities (was 0.2)
            9,      // totalAmplitude - ±9 blocks (was 15)
            true,   // enable3D - frozen rock overhangs
            0.2f    // overhangFrequency - occasional (20%)
        ));

        // TAIGA - Forested hills with valleys
        // SMOOTHING: Reduced strengths by 50%, amplitude by 40%
        map.put(BiomeType.TAIGA, new BiomeNoiseConfig(
            0.2f,   // peaksValleysStrength - forested hills (was 0.4)
            0.1f,   // ridgedStrength - occasional rocky ridges (was 0.2)
            0.05f,  // weirdnessStrength - minimal (was 0.1)
            9,      // totalAmplitude - ±9 blocks (was 15)
            true,   // enable3D - rocky forest cliffs
            0.2f    // overhangFrequency - occasional (20%)
        ));

        // STONY_PEAKS - Dramatic jagged mountains
        // SMOOTHING: Reduced strengths by 50%, amplitude by 40%
        map.put(BiomeType.STONY_PEAKS, new BiomeNoiseConfig(
            0.4f,   // peaksValleysStrength - extreme peaks/valleys (was 0.8)
            0.5f,   // ridgedStrength - sharp ridges (was 1.0)
            0.1f,   // weirdnessStrength - some plateaus (was 0.2)
            24,     // totalAmplitude - ±24 blocks (was 40)
            true,   // enable3D - mountain overhangs
            0.5f    // overhangFrequency - high (50%)
        ));

        // GRAVEL_BEACH - Flat coastal transition
        // SMOOTHING: Reduced strengths by 50%, amplitude by 40%
        map.put(BiomeType.GRAVEL_BEACH, new BiomeNoiseConfig(
            0.05f,  // peaksValleysStrength - very gentle (was 0.1)
            0.0f,   // ridgedStrength - no ridges
            0.0f,   // weirdnessStrength - none
            2,      // totalAmplitude - ±2 blocks (was 3)
            false,  // enable3D - no 3D features (keep beach flat)
            0.0f    // overhangFrequency - none
        ));

        // ICE_FIELDS - Glacial pressure ridges, ice spikes
        // SMOOTHING: Reduced strengths by 50%, amplitude by 40%
        map.put(BiomeType.ICE_FIELDS, new BiomeNoiseConfig(
            0.25f,  // peaksValleysStrength - ice peaks (was 0.5)
            0.3f,   // ridgedStrength - pressure ridges (was 0.6)
            0.15f,  // weirdnessStrength - ice plateaus (was 0.3)
            15,     // totalAmplitude - ±15 blocks (was 25)
            true,   // enable3D - ice overhangs and caves
            0.4f    // overhangFrequency - moderate-high (40%)
        ));

        // BADLANDS - Mesa plateaus, terraced hillsides
        // SMOOTHING: Reduced strengths by 50%, amplitude by 40%
        map.put(BiomeType.BADLANDS, new BiomeNoiseConfig(
            0.15f,  // peaksValleysStrength - gentle base (was 0.3)
            0.0f,   // ridgedStrength - no ridges (mesas are flat-topped)
            0.5f,   // weirdnessStrength - terracing (was 1.0)
            18,     // totalAmplitude - ±18 blocks (was 30)
            true,   // enable3D - mesa overhangs
            0.4f    // overhangFrequency - moderate-high (40%)
        ));

        return map;
    }

    /**
     * Gets the default configuration used when a biome has no specific config.
     *
     * Returns a balanced configuration with moderate values.
     * SMOOTHING: Reduced by 50% for strengths, 40% for amplitude.
     *
     * @return Default noise configuration
     */
    private BiomeNoiseConfig getDefaultConfig() {
        return new BiomeNoiseConfig(
            0.15f,  // peaksValleysStrength - moderate (was 0.3)
            0.1f,   // ridgedStrength - some ridges (was 0.2)
            0.1f,   // weirdnessStrength - some variation (was 0.2)
            7,      // totalAmplitude - ±7 blocks (was 12)
            true,   // enable3D - enabled
            0.2f    // overhangFrequency - occasional
        );
    }

    /**
     * Checks if a biome has 3D terrain enabled.
     *
     * Convenience method for quick 3D checks.
     *
     * @param biome The biome type
     * @return true if 3D terrain is enabled for this biome
     */
    public boolean is3DEnabled(BiomeType biome) {
        return getConfig(biome).is3DEnabled();
    }
}
