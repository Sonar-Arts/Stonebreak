package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfig;

/**
 * Provides position-based variation for biome features using noise generators.
 *
 * This router enables procedural variation in biome characteristics without creating
 * separate BiomeInstance objects. Features vary by world position while maintaining
 * determinism (same seed + position = same result).
 *
 * Architecture:
 * - Two independent noise generators at different scales
 * - Density variation: 600-block scale for forest clustering (sparse vs dense regions)
 * - Type variation: 400-block scale for tree type dominance (oak vs elm regions)
 * - Thread-safe: immutable after construction, noise generators are thread-safe
 *
 * Design Principles:
 * - Single Responsibility: Only handles biome feature variation
 * - Open/Closed: Extensible for new variation types
 * - KISS: Simple linear noise mapping
 * - YAGNI: Hardcoded variation ranges (no config until proven needed)
 *
 * Example Usage:
 * <pre>{@code
 * BiomeVariationRouter router = new BiomeVariationRouter(seed);
 * float densityMultiplier = router.getDensityMultiplier(worldX, worldZ);  // [0.4, 1.6]
 * float variedDensity = baseDensity * densityMultiplier;
 * }</pre>
 *
 * @see VegetationGenerator
 * @see BiomeManager
 */
public class BiomeVariationRouter {

    private final NoiseGenerator densityVariationNoise;
    private final NoiseGenerator typeVariationNoise;

    /**
     * Creates a new biome variation router with the given seed.
     *
     * Initializes two independent noise generators:
     * - Density variation: 600-block scale, 2 octaves (large-scale forest clustering)
     * - Type variation: 400-block scale, 2 octaves (regional tree type dominance)
     *
     * Noise scales chosen to create observable but smooth variation:
     * - 600 blocks ≈ 5-10 minutes of walking (player speed ~6 blocks/sec)
     * - 400 blocks ≈ 3-7 minutes of walking
     * - Independent scales prevent exact correlation between density and type
     *
     * @param seed World seed for deterministic generation
     */
    public BiomeVariationRouter(long seed) {
        // Density variation noise: 600-block scale for large forest clusters
        // 2 octaves for smooth variation without micro-variations
        // Seed offset: +500 to ensure independence from other noise systems
        NoiseConfig densityConfig = new NoiseConfig(2, 0.5, 2.0);
        this.densityVariationNoise = new NoiseGenerator(seed + 500, densityConfig);

        // Type variation noise: 400-block scale for tree type regions
        // Smaller than density scale to create independent patterns
        // Seed offset: +501 to ensure independence from density noise
        NoiseConfig typeConfig = new NoiseConfig(2, 0.5, 2.0);
        this.typeVariationNoise = new NoiseGenerator(seed + 501, typeConfig);
    }

    /**
     * Returns density multiplier for the given world position.
     *
     * Creates position-based variation in feature density (e.g., tree density).
     * The same biome will have dense forests in some regions and sparse forests in others.
     *
     * Calculation:
     * 1. Sample noise at 600-block scale → noise in range [-1, 1]
     * 2. Map to multiplier: 1.0 + (noise * 0.6) → [0.4, 1.6]
     *
     * Examples:
     * - noise = -1.0 → multiplier = 0.4 (60% reduction, very sparse)
     * - noise = 0.0 → multiplier = 1.0 (base density, no change)
     * - noise = +1.0 → multiplier = 1.6 (60% increase, very dense)
     *
     * Applied to PLAINS biome (base 1% tree density):
     * - Sparse regions: 0.4% tree density
     * - Normal regions: 1.0% tree density
     * - Dense regions: 1.6% tree density
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Density multiplier in range [0.4, 1.6] (±60% variation)
     */
    public float getDensityMultiplier(int worldX, int worldZ) {
        // Sample noise at 600-block scale (large-scale clustering)
        float noise = densityVariationNoise.noise(worldX / 600.0f, worldZ / 600.0f);

        // Map [-1, 1] to [0.4, 1.6]: moderate variation (±60%)
        return 1.0f + (noise * 0.6f);
    }

    /**
     * Returns tree type ratio shift for the given world position.
     *
     * Creates position-based variation in tree type distribution.
     * For PLAINS biome (base 60% oak, 40% elm):
     * - Some regions become oak-dominant (90% oak, 10% elm)
     * - Some regions become elm-dominant (30% oak, 70% elm)
     * - Most regions have mixed forests (40-80% oak)
     *
     * Calculation:
     * 1. Sample noise at 400-block scale → noise in range [-1, 1]
     * 2. Map to shift: noise * 0.3 → [-0.3, +0.3]
     *
     * Examples:
     * - noise = -1.0 → shift = -0.3 (elm-dominant: 60% - 30% = 30% oak)
     * - noise = 0.0 → shift = 0.0 (base ratio: 60% oak)
     * - noise = +1.0 → shift = +0.3 (oak-dominant: 60% + 30% = 90% oak)
     *
     * Note: Caller should clamp final probability to [0.1, 0.9] to prevent monoculture forests.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Type ratio shift in range [-0.3, +0.3] (±30% probability shift)
     */
    public float getTypeRatioShift(int worldX, int worldZ) {
        // Sample noise at 400-block scale (regional type variation)
        float noise = typeVariationNoise.noise(worldX / 400.0f, worldZ / 400.0f);

        // Map [-1, 1] to [-0.3, +0.3]: ±30% probability shift
        return noise * 0.3f;
    }
}
