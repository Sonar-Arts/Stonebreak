package com.stonebreak.world.generation.terrain;

import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.config.TerrainNoiseWeights;
import com.stonebreak.world.generation.noise.Noise3D;

/**
 * Creates natural arches and rock formations with tunnels through them.
 *
 * Natural arches are rare surface features that create tunnel-like openings
 * through rock formations. They differ from overhangs by creating complete
 * "holes" through terrain rather than just ledges.
 *
 * Characteristics:
 * - **Visual**: Tunnel-like openings you can walk/see through
 * - **Location**: At surface level, within 8 blocks below to 3 above
 * - **Rarity**: High threshold (0.55) for uncommon but noticeable features
 * - **Scale**: Tighter noise (35 blocks) for arch-sized openings
 *
 * Implementation Strategy:
 * - Sample 3D noise at tight scale for arch-sized features
 * - High threshold ensures arches are rare and dramatic
 * - Slightly deeper Y-range than overhangs (-8 to +3) allows tunnels
 * - Simple threshold check for consistent arch shapes
 *
 * Example Use Case:
 * - Desert rock formations with natural tunnels
 * - Coastal cliff arches
 * - Mountain pass tunnels
 */
public class NaturalArchFeature implements TerrainFeature {

    private final Noise3D noise3D;
    private final TerrainFeatureConfig config;
    private final TerrainNoiseWeights noiseWeights;

    /**
     * Creates a new natural arch feature generator.
     *
     * @param seed   World seed for deterministic generation
     * @param config Configuration parameters (scale, threshold, Y-range)
     */
    public NaturalArchFeature(long seed, TerrainFeatureConfig config) {
        // Use separate noise seed offset to ensure independence from other features
        this.noise3D = new Noise3D(seed + 75, NoiseConfigFactory.forArches());
        this.config = config;
        this.noiseWeights = new TerrainNoiseWeights();
    }

    /**
     * Creates a new natural arch feature with default configuration.
     *
     * @param seed World seed
     * @return New natural arch feature
     */
    public static NaturalArchFeature withDefaults(long seed) {
        return new NaturalArchFeature(seed, TerrainFeatureConfig.naturalArch());
    }

    @Override
    public boolean shouldRemoveBlock(int worldX, int y, int worldZ, int surfaceHeight, BiomeType biome) {
        // Check if feature is enabled
        if (!config.isEnabled()) {
            return false;
        }

        // Check if biome supports 3D terrain
        if (!noiseWeights.is3DEnabled(biome)) {
            return false;
        }

        // Check Y-range (at surface level, slightly deeper than overhangs)
        if (!config.isInYRange(y, surfaceHeight)) {
            return false;
        }

        // Sample 3D noise at tighter scale for arch-sized features
        float noise = noise3D.getDensity(worldX, y, worldZ, config.getNoiseScale());

        // High threshold = rare arches
        // Only strong positive noise creates tunnels
        return noise > config.getThreshold();
    }

    @Override
    public int getPriority() {
        return 200; // Evaluated after caves, before overhangs
    }

    @Override
    public String getFeatureName() {
        return "NaturalArch";
    }
}
