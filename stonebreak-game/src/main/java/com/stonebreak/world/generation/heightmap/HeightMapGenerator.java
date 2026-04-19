package com.stonebreak.world.generation.heightmap;

import com.stonebreak.util.SplineInterpolator;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.biomes.BiomeBlendResult;
import com.stonebreak.world.generation.biomes.BiomeBlender;
import com.stonebreak.world.generation.biomes.BiomeHeightModifier;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Maps continentalness noise to terrain height via a spline, then optionally
 * applies biome-specific height modulation with smooth inter-biome blending.
 *
 * Final height = base continentalness height + (weighted) biome height delta.
 */
public class HeightMapGenerator {
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final float CONTINENTALNESS_SCALE = 1f / 800f;

    /** Above this dominance fraction we skip blending entirely (cheap fast path). */
    private static final float STRONG_DOMINANCE_THRESHOLD = 0.8f;

    private static final BiomeType[] BIOMES = BiomeType.values();

    private final NoiseGenerator continentalnessNoise;
    private final SplineInterpolator terrainSpline;
    private final BiomeHeightModifier biomeHeightModifier;
    private final BiomeBlender biomeBlender;

    public HeightMapGenerator(long seed) {
        this.continentalnessNoise = new NoiseGenerator(seed + 2);
        this.biomeHeightModifier = new BiomeHeightModifier(seed);
        this.biomeBlender = new BiomeBlender();
        this.terrainSpline = new SplineInterpolator();
        terrainSpline.addPoint(-1.0, 70);
        terrainSpline.addPoint(-0.8, 20);
        terrainSpline.addPoint(-0.4, 60);
        terrainSpline.addPoint(-0.2, 70);
        terrainSpline.addPoint(0.1, 75);
        terrainSpline.addPoint(0.3, 120);
        terrainSpline.addPoint(0.7, 140);
        terrainSpline.addPoint(1.0, 200);
    }

    /** Base terrain height from continentalness only, ignoring biome. */
    public int generateHeight(int x, int z) {
        int height = (int) terrainSpline.interpolate(getContinentalness(x, z));
        return clampToWorld(height);
    }

    public float getContinentalness(int x, int z) {
        return continentalnessNoise.noise(x * CONTINENTALNESS_SCALE, z * CONTINENTALNESS_SCALE);
    }

    /**
     * Single-cell biome-aware height lookup. Equivalent to the per-cell body of
     * {@link #populateChunkHeights(int, int, BiomeManager, int[])}; use this for
     * off-chunk placements where the chunk height grid is not available.
     */
    public int generateHeight(int x, int z, BiomeManager biomeManager) {
        int baseHeight = generateHeight(x, z);
        BiomeBlendResult blend = biomeBlender.getBlendedBiome(biomeManager, x, z);
        return generateBlendedHeight(baseHeight, blend, x, z);
    }

    /** Base height plus a single biome's modifier. */
    public int applyBiomeModifier(int baseHeight, BiomeType biome, int x, int z) {
        return clampToWorld(baseHeight + biomeHeightModifier.calculateHeightDelta(biome, x, z));
    }

    /**
     * Base height plus a weighted blend of all biomes' modifiers. Falls back to
     * a single-biome modifier when one biome overwhelmingly dominates the blend
     * (avoids redundant noise evaluations).
     */
    public int generateBlendedHeight(int baseHeight, BiomeBlendResult blend, int x, int z) {
        if (blend.isStronglyDominant(STRONG_DOMINANCE_THRESHOLD)) {
            return applyBiomeModifier(baseHeight, blend.getDominantBiome(), x, z);
        }

        float[] weights = blend.getWeightsByOrdinal();
        float weightedDelta = 0f;
        for (int i = 0; i < weights.length; i++) {
            float w = weights[i];
            if (w <= 0f) {
                continue;
            }
            weightedDelta += w * biomeHeightModifier.calculateHeightDelta(BIOMES[i], x, z);
        }
        return clampToWorld(baseHeight + Math.round(weightedDelta));
    }

    /**
     * Fills a 16x16 base-height grid for the given chunk, indexed [x*16+z].
     * No biome-aware modulation; callers that want biome-driven terrain should
     * use {@link #populateChunkHeights(int, int, BiomeManager, int[])}.
     */
    public void populateChunkHeights(int chunkX, int chunkZ, int[] out) {
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                out[x * CHUNK_SIZE + z] = generateHeight(baseX + x, baseZ + z);
            }
        }
    }

    /**
     * Fills a 16x16 biome-aware height grid with blended transitions.
     */
    public void populateChunkHeights(int chunkX, int chunkZ, BiomeManager biomeManager, int[] out) {
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                int worldX = baseX + x;
                int worldZ = baseZ + z;
                int baseHeight = generateHeight(worldX, worldZ);
                BiomeBlendResult blend = biomeBlender.getBlendedBiome(biomeManager, worldX, worldZ);
                out[x * CHUNK_SIZE + z] = generateBlendedHeight(baseHeight, blend, worldX, worldZ);
            }
        }
    }

    private static int clampToWorld(int height) {
        return Math.max(1, Math.min(height, WORLD_HEIGHT - 1));
    }
}
