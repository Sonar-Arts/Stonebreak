package com.stonebreak.world.generation.features;

import com.stonebreak.world.DeterministicRandom;

/**
 * Smooth density fields used to clump trees into forests with open clearings between
 * them, and to gather elms into smaller pockets nested inside those forests.
 *
 * <p>Each field is a bilinear (smoothstep) interpolation across a sparse lattice of
 * pseudo-random values from {@link DeterministicRandom}. Lattice spacing controls the
 * scale of the resulting blobs:</p>
 * <ul>
 *   <li><b>Forest field</b> (48 block spacing) — large stretches of woods alternating
 *       with open meadow.</li>
 *   <li><b>Elm pocket field</b> (24 block spacing) — smaller groves of elms inside the
 *       forests, surrounded by oaks.</li>
 * </ul>
 *
 * <p>All methods are static and pure: identical seeds and coordinates always return the
 * same value, so the LOD probe path and the chunk-population path stay in agreement.</p>
 */
public final class ForestDensityField {

    private static final int FOREST_LATTICE = 96;
    private static final int ELM_LATTICE = 32;

    /** Below this raw density the forest multiplier is zero — produces real clearings. */
    private static final float FOREST_THRESHOLD = 0.40f;
    /** Slope above the threshold. Combined with the bilinear distribution this peaks
     *  near ~3.5x and averages near 1.0x, leaving overall tree density roughly stable. */
    private static final float FOREST_PEAK_MULT = 5.8f;

    /** High threshold so most plains positions never become elms. */
    private static final float ELM_THRESHOLD = 0.55f;
    /** Probability of elm-vs-oak inside the hottest part of an elm pocket. */
    private static final float ELM_PEAK_PROB = 0.85f;

    private ForestDensityField() {}

    /**
     * Per-column multiplier applied to the base tree probability. Returns 0 inside
     * clearings and roughly 3.5 inside dense forest cores.
     */
    public static float forestMultiplier(int worldX, int worldZ, DeterministicRandom rng) {
        float d = sampleLattice(worldX, worldZ, FOREST_LATTICE, "forest_density", rng);
        if (d <= FOREST_THRESHOLD) return 0f;
        return (d - FOREST_THRESHOLD) * FOREST_PEAK_MULT;
    }

    /**
     * Probability that a placed plains tree should be an elm rather than an oak.
     * Concentrated into small pockets — most positions return 0.
     */
    public static float elmProbability(int worldX, int worldZ, DeterministicRandom rng) {
        float d = sampleLattice(worldX, worldZ, ELM_LATTICE, "elm_pocket", rng);
        if (d <= ELM_THRESHOLD) return 0f;
        float t = (d - ELM_THRESHOLD) / (1f - ELM_THRESHOLD);
        return t * ELM_PEAK_PROB;
    }

    private static float sampleLattice(int worldX, int worldZ, int spacing,
                                       String feature, DeterministicRandom rng) {
        int x0 = Math.floorDiv(worldX, spacing);
        int z0 = Math.floorDiv(worldZ, spacing);
        float tx = (worldX - x0 * spacing) / (float) spacing;
        float tz = (worldZ - z0 * spacing) / (float) spacing;
        // Smoothstep softens the lattice seams so blobs blend continuously.
        tx = tx * tx * (3f - 2f * tx);
        tz = tz * tz * (3f - 2f * tz);

        float v00 = rng.getFloat(x0,     z0,     feature);
        float v10 = rng.getFloat(x0 + 1, z0,     feature);
        float v01 = rng.getFloat(x0,     z0 + 1, feature);
        float v11 = rng.getFloat(x0 + 1, z0 + 1, feature);

        float a = v00 + (v10 - v00) * tx;
        float b = v01 + (v11 - v01) * tx;
        return a + (b - a) * tz;
    }
}
