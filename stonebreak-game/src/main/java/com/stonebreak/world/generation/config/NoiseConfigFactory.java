package com.stonebreak.world.generation.config;

/**
 * Factory for creating preset noise configurations.
 *
 * Provides carefully tuned noise parameters for different terrain generation purposes:
 * - Continentalness: Large-scale landmass distribution (low frequency, high amplitude)
 * - Erosion: Terrain roughness and flatness (medium frequency)
 * - Peaks & Valleys: Local height variation (high frequency, low amplitude)
 * - Detail: Fine-grained surface detail (very high frequency, very low amplitude)
 * - Temperature/Moisture: Biome climate parameters (medium-low frequency)
 *
 * Follows Factory Pattern for centralized configuration management.
 * All configs are immutable and thread-safe.
 */
public class NoiseConfigFactory {

    /**
     * Continentalness noise: Large-scale landmass distribution.
     * Low frequency (800-block scale), high detail for smooth continent shapes.
     *
     * Characteristics:
     * - 8 octaves for smooth, natural continent boundaries
     * - 0.45 persistence for balanced detail across scales
     * - 2.0 lacunarity (standard doubling of frequency)
     *
     * @return Noise config for continentalness generation
     */
    public static NoiseConfig continentalness() {
        return new NoiseConfig(8, 0.45, 2.0);
    }

    /**
     * Erosion noise: Controls terrain flatness vs roughness.
     * Medium frequency (300-block scale) to create varied terrain patterns.
     *
     * Characteristics:
     * - 6 octaves for moderate detail
     * - 0.5 persistence for balanced erosion effect
     * - 2.2 lacunarity for slightly more variation between octaves
     *
     * @return Noise config for erosion generation
     */
    public static NoiseConfig erosion() {
        return new NoiseConfig(6, 0.5, 2.2);
    }

    /**
     * Peaks & Valleys noise: Local height variation for mountains and valleys.
     * Higher frequency (150-block scale) for detailed terrain features.
     *
     * Characteristics:
     * - 5 octaves for sharp peaks without excessive detail
     * - 0.55 persistence for prominent peak formations
     * - 2.3 lacunarity for dramatic frequency increase
     *
     * @return Noise config for peaks and valleys generation
     */
    public static NoiseConfig peaksValleys() {
        return new NoiseConfig(5, 0.55, 2.3);
    }

    /**
     * Detail noise: Fine-grained surface variation and erosion simulation.
     * Very high frequency (40-block scale), low amplitude for subtle effects.
     *
     * Characteristics:
     * - 4 octaves for subtle variation without overwhelming base terrain
     * - 0.35 persistence for gentle, weathered appearance
     * - 2.0 lacunarity (standard)
     *
     * @return Noise config for detail/erosion effects
     */
    public static NoiseConfig detail() {
        return new NoiseConfig(4, 0.35, 2.0);
    }

    /**
     * Temperature noise: Climate temperature distribution.
     * Medium-low frequency (300-block scale) for gradual climate zones.
     *
     * Characteristics:
     * - 6 octaves for smooth temperature transitions
     * - 0.4 persistence for gentle climate variation
     * - 2.0 lacunarity (standard)
     *
     * @return Noise config for temperature generation
     */
    public static NoiseConfig temperature() {
        return new NoiseConfig(6, 0.4, 2.0);
    }

    /**
     * Moisture noise: Climate moisture/precipitation distribution.
     * Medium frequency (200-block scale) for varied biome distribution.
     *
     * Characteristics:
     * - 6 octaves for detailed moisture patterns
     * - 0.45 persistence for moderate variation
     * - 2.1 lacunarity for slightly more texture
     *
     * @return Noise config for moisture generation
     */
    public static NoiseConfig moisture() {
        return new NoiseConfig(6, 0.45, 2.1);
    }

    /**
     * Biome height modifier noise: Biome-specific terrain detail.
     * Medium-high frequency (varies by biome) for biome character.
     *
     * Characteristics:
     * - 5 octaves for distinctive biome terrain
     * - 0.5 persistence for balanced biome features
     * - 2.0 lacunarity (standard)
     *
     * @return Noise config for biome-specific height modification
     */
    public static NoiseConfig biomeDetail() {
        return new NoiseConfig(5, 0.5, 2.0);
    }

    /**
     * Cave noise: 3D cave network generation (Phase 3).
     * High frequency with ridged characteristics for cave tunnels.
     *
     * Characteristics:
     * - 4 octaves for cave tunnel detail
     * - 0.6 persistence for prominent cave features
     * - 2.5 lacunarity for varied cave sizes
     *
     * @return Noise config for cave generation
     */
    public static NoiseConfig caves() {
        return new NoiseConfig(4, 0.6, 2.5);
    }

    /**
     * Dense caves: Larger cavern systems (Phase 3).
     * Lower frequency for bigger chambers.
     *
     * Characteristics:
     * - 3 octaves for large chamber shapes
     * - 0.5 persistence for smooth cavern walls
     * - 2.0 lacunarity (standard)
     *
     * @return Noise config for large cave chambers
     */
    public static NoiseConfig denseCaves() {
        return new NoiseConfig(3, 0.5, 2.0);
    }

    /**
     * Ridged noise: Sharp mountain ridges and peak formations.
     * Medium frequency for defined ridge lines.
     *
     * Characteristics:
     * - 5 octaves for clean, defined ridges
     * - 0.45 persistence for moderate detail
     * - 2.5 lacunarity for dramatic frequency jumps
     *
     * Used by RidgedNoiseGenerator for sharp mountain peaks.
     *
     * @return Noise config for ridged terrain generation
     */
    public static NoiseConfig ridged() {
        return new NoiseConfig(5, 0.45, 2.5);
    }

    /**
     * Terrain Peaks & Valleys noise: Amplifies terrain extremes.
     * Medium frequency for overall terrain drama.
     *
     * Characteristics:
     * - 5 octaves for balanced amplification
     * - 0.55 persistence for prominent features
     * - 2.3 lacunarity for varied scales
     *
     * Used by PeaksValleysNoiseGenerator to make high areas higher
     * and low areas lower, creating dramatic height differences.
     *
     * @return Noise config for peaks & valleys terrain shaping
     */
    public static NoiseConfig terrainPeaksValleys() {
        return new NoiseConfig(5, 0.55, 2.3);
    }

    /**
     * Terrain weirdness noise: Plateaus, mesas, and terraced formations.
     * Medium frequency for regional unusual terrain features.
     *
     * Characteristics:
     * - 6 octaves for detailed terrace patterns
     * - 0.5 persistence for balanced features
     * - 2.2 lacunarity for moderate variation
     *
     * Used by WeirdnessNoiseGenerator for flat-topped mesas,
     * terraced hillsides, and plateau formations.
     *
     * @return Noise config for weirdness/plateau generation
     */
    public static NoiseConfig terrainWeirdness() {
        return new NoiseConfig(6, 0.5, 2.2);
    }

    /**
     * 3D density noise: Volumetric terrain for overhangs and caves.
     * Medium frequency for natural-looking 3D features.
     *
     * Characteristics:
     * - 4 octaves for moderate 3D detail (expensive in 3D)
     * - 0.5 persistence for balanced density variation
     * - 2.0 lacunarity (standard)
     *
     * Used by Noise3D for density-based terrain generation,
     * enabling overhangs, natural arches, and cave entrances.
     *
     * @return Noise config for 3D density terrain
     */
    public static NoiseConfig density3D() {
        return new NoiseConfig(4, 0.5, 2.0);
    }

    /**
     * Continentalness climate noise: Large-scale regional climate distribution.
     * Very low frequency (10,000-block scale) for massive continental patterns.
     *
     * Phase 1 Enhancement: Used by ClimateRegionManager to determine
     * oceanic vs coastal vs continental interior regions.
     *
     * Characteristics:
     * - 6 octaves for smooth continental boundaries
     * - 0.5 persistence for balanced detail across scales
     * - 2.0 lacunarity (standard doubling of frequency)
     *
     * @return Noise config for continentalness climate generation
     */
    public static NoiseConfig createContinentalnessClimateNoise() {
        return new NoiseConfig(6, 0.5, 2.0);
    }

    /**
     * Region weirdness noise: Adds variety and exceptions to climate regions.
     * Very low frequency (8,000-block scale) for regional variety.
     *
     * Phase 1 Enhancement: Used by ClimateRegionManager to add
     * occasional "weird" biome placements for variety.
     *
     * Characteristics:
     * - 5 octaves for moderate regional variation
     * - 0.45 persistence for gentle variety
     * - 2.2 lacunarity for slightly more texture
     *
     * @return Noise config for region weirdness generation
     */
    public static NoiseConfig createRegionWeirdnessNoise() {
        return new NoiseConfig(5, 0.45, 2.2);
    }

    // Prevent instantiation - this is a utility class
    private NoiseConfigFactory() {
        throw new AssertionError("NoiseConfigFactory should not be instantiated");
    }
}
