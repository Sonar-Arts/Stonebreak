package com.stonebreak.world.generation.terrain;

import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.config.TerrainNoiseWeights;
import com.stonebreak.world.generation.heightmap.Noise3D;

/**
 * Creates dramatic cliff faces with subtle overhangs.
 *
 * Surface overhangs are rare, visually striking features that occur at cliff edges
 * and steep terrain. They use 3D noise with a high threshold to ensure they're
 * uncommon and intentional, not accidental surface erosion.
 *
 * Characteristics:
 * - **Rarity**: High threshold (0.6) means only extreme noise values create overhangs
 * - **Location**: Within 5 blocks below to 3 blocks above surface
 * - **Visual Impact**: Creates dramatic cliffs and ledges without removing too many blocks
 * - **Biome Awareness**: Respects biome 3D enablement settings
 *
 * Implementation Strategy:
 * - Sample 3D noise at cliff scale (45 blocks)
 * - Only remove blocks if noise exceeds high threshold
 * - Limited Y-range prevents excessive carving
 * - Simple threshold check (not depth-weighted) for consistent surface features
 *
 * Example:
 * - Cliff face at y=68, surfaceHeight=70
 * - Noise value = 0.65 (high)
 * - 0.65 > 0.6 threshold → Remove block (creates overhang)
 * - Most blocks have noise < 0.6 → Stay solid (minimal erosion)
 */
public class SurfaceOverhangFeature implements TerrainFeature {

    private final Noise3D noise3D;
    private final TerrainFeatureConfig config;
    private final TerrainNoiseWeights noiseWeights;

    /**
     * Creates a new surface overhang feature generator.
     *
     * @param seed   World seed for deterministic generation
     * @param config Configuration parameters (scale, threshold, Y-range)
     */
    public SurfaceOverhangFeature(long seed, TerrainFeatureConfig config) {
        // Use separate noise seed offset to ensure independence from other features
        this.noise3D = new Noise3D(seed + 50, NoiseConfigFactory.forOverhangs());
        this.config = config;
        this.noiseWeights = new TerrainNoiseWeights();
    }

    /**
     * Creates a new surface overhang feature with default configuration.
     *
     * @param seed World seed
     * @return New surface overhang feature
     */
    public static SurfaceOverhangFeature withDefaults(long seed) {
        return new SurfaceOverhangFeature(seed, TerrainFeatureConfig.surfaceOverhang());
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

        // Check Y-range (only near surface)
        if (!config.isInYRange(y, surfaceHeight)) {
            return false;
        }

        // Sample 3D noise
        float noise = noise3D.getDensity(worldX, y, worldZ, config.getNoiseScale());

        // High threshold = rare overhangs
        // Only very strong positive noise creates overhangs
        return noise > config.getThreshold();
    }

    @Override
    public int getPriority() {
        return 300; // Evaluated last (after caves and arches)
    }

    @Override
    public String getFeatureName() {
        return "SurfaceOverhang";
    }
}
