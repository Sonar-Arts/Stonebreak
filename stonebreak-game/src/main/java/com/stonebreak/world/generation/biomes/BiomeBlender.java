package com.stonebreak.world.generation.biomes;

/**
 * Blends biome influences by sampling a small grid around a target position and
 * accumulating distance-weighted contributions. Produces a {@link BiomeBlendResult}
 * used by downstream systems (height, block choice) to smooth transitions across
 * biome borders.
 *
 * Hot path characteristics:
 * <ul>
 *   <li>No boxing: weights accumulate into a primitive float[] indexed by BiomeType ordinal.</li>
 *   <li>No runtime sqrt: sample weights are precomputed once in a static initializer.</li>
 *   <li>Fixed 3x3 sample grid (9 biome lookups per position).</li>
 * </ul>
 *
 * Falloff is {@code (1 - d/D)^2} in grid-units, matching the original reference
 * implementation (squared linear falloff, emphasizing centre biome).
 */
public final class BiomeBlender {

    /** Radius of the sampling grid. Radius 1 yields a 3x3 grid (9 samples). */
    private static final int SAMPLE_RADIUS = 1;

    /** Spacing between samples in world blocks. */
    private static final int SAMPLE_SPACING = 8;

    /** Maximum falloff distance in grid-units. Samples past this contribute zero. */
    private static final float BLEND_DISTANCE = 32.0f;

    private static final int GRID_SIZE = SAMPLE_RADIUS * 2 + 1;
    private static final int SAMPLE_COUNT = GRID_SIZE * GRID_SIZE;

    private static final int BIOME_COUNT = BiomeType.values().length;

    /** Precomputed sample weights indexed by (dx + R) * GRID_SIZE + (dz + R). */
    private static final float[] SAMPLE_WEIGHTS = new float[SAMPLE_COUNT];

    static {
        for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS; dx++) {
            for (int dz = -SAMPLE_RADIUS; dz <= SAMPLE_RADIUS; dz++) {
                float distance = (float) Math.sqrt(dx * dx + dz * dz);
                float linear = Math.max(0f, 1f - distance / BLEND_DISTANCE);
                SAMPLE_WEIGHTS[(dx + SAMPLE_RADIUS) * GRID_SIZE + (dz + SAMPLE_RADIUS)] = linear * linear;
            }
        }
    }

    /**
     * Computes the blended biome influences at a world position.
     *
     * @param biomeManager source of biome classification at each sample point
     * @param x            world X coordinate
     * @param z            world Z coordinate
     */
    public BiomeBlendResult getBlendedBiome(BiomeManager biomeManager, int x, int z) {
        float[] weights = new float[BIOME_COUNT];
        float totalWeight = 0f;

        for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS; dx++) {
            for (int dz = -SAMPLE_RADIUS; dz <= SAMPLE_RADIUS; dz++) {
                float weight = SAMPLE_WEIGHTS[(dx + SAMPLE_RADIUS) * GRID_SIZE + (dz + SAMPLE_RADIUS)];
                if (weight <= 0f) {
                    continue;
                }
                int sampleX = x + dx * SAMPLE_SPACING;
                int sampleZ = z + dz * SAMPLE_SPACING;
                BiomeType biome = biomeManager.getBiome(sampleX, sampleZ);
                weights[biome.ordinal()] += weight;
                totalWeight += weight;
            }
        }

        // Normalize so weights sum to 1.0.
        float inv = 1f / totalWeight;
        for (int i = 0; i < weights.length; i++) {
            weights[i] *= inv;
        }

        return new BiomeBlendResult(weights);
    }
}
