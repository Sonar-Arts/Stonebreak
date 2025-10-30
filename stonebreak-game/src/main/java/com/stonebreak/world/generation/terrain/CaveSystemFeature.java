package com.stonebreak.world.generation.terrain;

import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.config.TerrainNoiseWeights;
import com.stonebreak.world.generation.heightmap.Noise3D;

/**
 * Creates moderate underground cave networks with depth-weighted density.
 *
 * Cave systems are the primary underground terrain feature, creating interconnected
 * caverns and tunnels. Unlike surface features (overhangs, arches), caves use
 * depth-weighted density mixing to:
 * - Prevent surface holes (cave openings should be intentional, not random)
 * - Create progressively more hollow terrain deeper underground
 * - Maintain structural integrity near surface for building/gameplay
 *
 * Key Innovation: Depth-Weighted Mixing
 * Instead of just thresholding noise, we combine heightmap-based "base density"
 * with 3D noise influence that grows stronger underground:
 *
 * finalDensity = baseDensity + (noise * influence)
 *
 * Where:
 * - baseDensity = (surfaceHeight - y) / 8.0 (how deep underground)
 * - influence = 0.1 at surface → 0.9 deep underground (how much noise affects)
 * - noise = 3D simplex noise in range [-1, 1]
 *
 * Result:
 * - At surface: baseDensity ≈ 0, influence = 10% → mostly stays solid
 * - 20 blocks down: baseDensity = 2.5, influence = 40% → cave entrances possible
 * - 60+ blocks down: baseDensity = 7.5+, influence = 90% → full cave systems
 *
 * Characteristics:
 * - **Density**: 15-20% hollowness underground (moderate)
 * - **Location**: Only below surface -10 blocks (no surface holes)
 * - **Scale**: Large (70 blocks) for spacious, explorable caves
 * - **Threshold**: Depth-adjusted (0.15 shallow → -0.05 deep)
 *
 * Based on research from Minecraft 1.18+ terrain generation.
 */
public class CaveSystemFeature implements TerrainFeature {

    private final Noise3D noise3D;
    private final TerrainFeatureConfig config;
    private final TerrainNoiseWeights noiseWeights;

    // Depth-adjusted thresholds for cave generation
    private static final float SHALLOW_THRESHOLD = 0.15f;  // Harder to carve near surface
    private static final float DEEP_THRESHOLD = -0.05f;    // Easier to carve deep underground
    private static final float DEPTH_TRANSITION = 60.0f;   // Blocks over which threshold transitions

    /**
     * Creates a new cave system feature generator.
     *
     * @param seed   World seed for deterministic generation
     * @param config Configuration parameters (scale, threshold, Y-range)
     */
    public CaveSystemFeature(long seed, TerrainFeatureConfig config) {
        // Use separate noise seed offset to ensure independence from other features
        this.noise3D = new Noise3D(seed + 100, NoiseConfigFactory.forCaveSystems());
        this.config = config;
        this.noiseWeights = new TerrainNoiseWeights();
    }

    /**
     * Creates a new cave system feature with default configuration.
     *
     * @param seed World seed
     * @return New cave system feature
     */
    public static CaveSystemFeature withDefaults(long seed) {
        return new CaveSystemFeature(seed, TerrainFeatureConfig.caveSystem());
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

        // Check Y-range (only underground)
        if (!config.isInYRange(y, surfaceHeight)) {
            return false;
        }

        // Ensure we're deep enough (minimum 10 blocks below surface)
        if (!TerrainFeatureDensityUtil.isUnderground(y, surfaceHeight, 10)) {
            return false;
        }

        // Calculate base density from depth below surface
        float baseDensity = TerrainFeatureDensityUtil.calculateBaseDensity(y, surfaceHeight);

        // Calculate noise influence (grows stronger underground)
        float noiseInfluence = TerrainFeatureDensityUtil.calculateNoiseInfluence(y, surfaceHeight);

        // Sample 3D noise
        float noise = noise3D.getDensity(worldX, y, worldZ, config.getNoiseScale());

        // Combine densities using depth-weighted mixing
        float finalDensity = TerrainFeatureDensityUtil.combineDensities(baseDensity, noise, noiseInfluence);

        // Calculate depth-adjusted threshold
        // Shallow caves: harder to carve (higher threshold = need more negative density)
        // Deep caves: easier to carve (lower threshold = less negative density needed)
        float depthBelowSurface = Math.max(0, surfaceHeight - y);
        float depthFactor = Math.min(1.0f, depthBelowSurface / DEPTH_TRANSITION);
        float threshold = SHALLOW_THRESHOLD + (depthFactor * (DEEP_THRESHOLD - SHALLOW_THRESHOLD));

        // Remove block if final density is below threshold
        // This creates caves where density is low (negative or barely positive)
        return finalDensity < threshold;
    }

    @Override
    public int getPriority() {
        return 100; // Evaluated first (deepest, most important)
    }

    @Override
    public String getFeatureName() {
        return "CaveSystem";
    }
}
