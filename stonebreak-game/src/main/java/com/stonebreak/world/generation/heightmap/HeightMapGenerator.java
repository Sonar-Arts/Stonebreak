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
 * Maps continentalness noise to terrain height via a spline, then applies a
 * weighted blend of per-biome height deltas plus a low-amplitude erosion
 * weathering pass.
 *
 * Final height = clamp( (base + weighted biome delta) * (1 + erosion * ERODE_FACTOR) )
 */
public class HeightMapGenerator {
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final float CONTINENTALNESS_SCALE = 1f / 800f;

    /** Above this dominance fraction we skip blending entirely (cheap fast path). */
    private static final float STRONG_DOMINANCE_THRESHOLD = 0.8f;

    /** Erosion noise scales at 1/40th world coordinates for high-frequency weathering. */
    private static final float EROSION_SCALE = 1f / 40f;
    /** Erosion noise output is dampened to this fraction of raw [-1, 1] range. */
    private static final float EROSION_AMPLITUDE = 0.3f;
    /** How much the dampened erosion value scales height (5% multiplicative variation). */
    private static final float EROSION_STRENGTH = 0.05f;

    private static final BiomeType[] BIOMES = BiomeType.values();

    private final NoiseGenerator continentalnessNoise;
    private final NoiseGenerator erosionNoise;
    private final SplineInterpolator terrainSpline;
    private final BiomeHeightModifier biomeHeightModifier;
    private final BiomeBlender biomeBlender;

    public HeightMapGenerator(long seed) {
        this.continentalnessNoise = new NoiseGenerator(seed + 2);
        this.erosionNoise = new NoiseGenerator(seed + 5, 4, 0.35, 2.0);
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
     * {@link #populateChunkHeights(int, int, BiomeManager, int[], int[])}; use this for
     * off-chunk placements where the chunk height grid is not available.
     */
    public int generateHeight(int x, int z, BiomeManager biomeManager) {
        int baseHeight = generateHeight(x, z);
        BiomeBlendResult blend = biomeBlender.getBlendedBiome(biomeManager, x, z, baseHeight);
        return generateBlendedHeight(baseHeight, blend, x, z);
    }

    /** Base height plus a single biome's modifier (erosion applied). */
    public int applyBiomeModifier(int baseHeight, BiomeType biome, int x, int z) {
        int modified = baseHeight + biomeHeightModifier.calculateHeightDelta(biome, x, z);
        return clampToWorld(applyErosion(modified, x, z));
    }

    /**
     * Base height plus a weighted blend of all biomes' modifiers, with an erosion
     * pass. Falls back to a single-biome modifier when one biome overwhelmingly
     * dominates the blend (avoids redundant noise evaluations).
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
        int preErosion = baseHeight + Math.round(weightedDelta);
        return clampToWorld(applyErosion(preErosion, x, z));
    }

    /** Raw erosion noise sample in approximately [-0.3, 0.3]. */
    public float getErosionNoise(int x, int z) {
        return erosionNoise.noise(x * EROSION_SCALE, z * EROSION_SCALE) * EROSION_AMPLITUDE;
    }

    /** Blended biome-aware height before erosion is applied (for debug). */
    public int getHeightBeforeErosion(int x, int z, BiomeManager biomeManager) {
        int baseHeight = generateHeight(x, z);
        BiomeBlendResult blend = biomeBlender.getBlendedBiome(biomeManager, x, z, baseHeight);
        if (blend.isStronglyDominant(STRONG_DOMINANCE_THRESHOLD)) {
            return clampToWorld(baseHeight
                + biomeHeightModifier.calculateHeightDelta(blend.getDominantBiome(), x, z));
        }
        float[] weights = blend.getWeightsByOrdinal();
        float weightedDelta = 0f;
        for (int i = 0; i < weights.length; i++) {
            float w = weights[i];
            if (w > 0f) {
                weightedDelta += w * biomeHeightModifier.calculateHeightDelta(BIOMES[i], x, z);
            }
        }
        return clampToWorld(baseHeight + Math.round(weightedDelta));
    }

    private int applyErosion(int height, int x, int z) {
        float erosion = getErosionNoise(x, z);
        return Math.round(height * (1f + erosion * EROSION_STRENGTH));
    }

    /**
     * Fills a 16x16 base-height grid (continentalness only) for the given chunk,
     * indexed [x*16+z]. Callers that want biome-driven blended terrain should
     * follow up with {@link #populateChunkHeights(int, int, BiomeManager, int[], int[])}.
     */
    public void populateBaseHeights(int chunkX, int chunkZ, int[] out) {
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                out[x * CHUNK_SIZE + z] = generateHeight(baseX + x, baseZ + z);
            }
        }
    }

    /**
     * Fills a 16x16 biome-aware height grid (blended + eroded). {@code baseHeights}
     * must already contain per-cell continentalness-only heights (from
     * {@link #populateBaseHeights(int, int, int[])}); they drive altitude chill in
     * the biome blend.
     */
    public void populateChunkHeights(int chunkX, int chunkZ, BiomeManager biomeManager,
                                     int[] baseHeights, int[] out) {
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                int idx = x * CHUNK_SIZE + z;
                int worldX = baseX + x;
                int worldZ = baseZ + z;
                int baseHeight = baseHeights[idx];
                BiomeBlendResult blend = biomeBlender.getBlendedBiome(biomeManager, worldX, worldZ, baseHeight);
                out[idx] = generateBlendedHeight(baseHeight, blend, worldX, worldZ);
            }
        }
    }

    private static int clampToWorld(int height) {
        return Math.max(1, Math.min(height, WORLD_HEIGHT - 1));
    }
}
