package com.stonebreak.world.generation.diffusion.biomes;

import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.biomes.ShadeRegions;

/**
 * The Shade in DaedalusTGM-Exp worlds (Project-Heracles' accursed forest, {@link BiomeType#SHADE}): the world's Shade
 * regions ({@link ShadeRegions}, the same chance-rolled regions Standard worlds use) laid over the model's own biomes
 * where the game maps them ({@link BiomeManager}, the coarse LOD profiles). Heracles placed it in the Python tile
 * pipeline from the model's climate; the native terrain pipeline that replaced it carries no climate to the game, so
 * the overlay works on the biome ids.
 *
 * <p>Inside a Shade region all of the model's temperate grassland and woodland turns Shade - plains (id 1), forest (8),
 * meadow (29) and sparse forest (108) - so the Shade stands as one mass and ends at the region's border or at land it
 * cannot take: jungle, savanna, swamp, taiga and snow, desert, peaks, shore and sea. A pure function of (seed, x, z):
 * every path that maps a column (generation, LOD, queries, the terrain mapper) agrees.
 *
 * <p>Never on a small isle nor along the shore: a column takes the Shade only with no open sea (the model's ocean ids)
 * within {@value #SEA_CLEARANCE} blocks ({@link SeaProbe}, two rings of eight probes), so an isle narrower than about
 * twice that never holds it - Standard's equivalent is the saturated continent mask.
 *
 * <p>Per world: only worlds created with {@code WorldFeatureVersion#shade()} get it ({@link #of}); others use
 * {@link #NONE}, so saved worlds never grow Shade beside terrain generated without it.
 */
public final class ShadeOverlay {

    /** No Shade (worlds created before WorldFeatureVersion V5). */
    public static final ShadeOverlay NONE = new ShadeOverlay(0L, false);

    /** Chance a region is the Shade's ({@link ShadeRegions#CHANCE}). */
    static final double CHANCE = ShadeRegions.CHANCE;

    /** DaedalusTGM-Exp ids the Shade takes over inside its regions. */
    private static final short PLAINS_ID = 1;
    private static final short FOREST_ID = 8;
    private static final short MEADOW_ID = 29;
    private static final short FOREST_SPARSE_ID = 108;

    /** Blocks of clear land a Shade column keeps from the open sea. */
    static final int SEA_CLEARANCE = 96;
    private static final int[] RING_DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] RING_DZ = {0, 1, 1, 1, 0, -1, -1, -1};

    /** Whether block (x, z) is the open sea (a model ocean id); the caller's tiles answer it. */
    @FunctionalInterface
    public interface SeaProbe {
        boolean isSea(long x, long z);
    }

    private final long seed;
    private final boolean enabled;

    private ShadeOverlay(long seed, boolean enabled) {
        this.seed = seed;
        this.enabled = enabled;
    }

    /** The overlay of a world with seed {@code seed}. */
    public static ShadeOverlay of(long seed) {
        return new ShadeOverlay(seed, true);
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * {@code mapped} (the game biome of model id {@code modelId} at block (x, z)), or {@link BiomeType#SHADE} where the
     * column is temperate grassland / woodland inside a Shade region. A host the mapper already turned into something
     * else (a beach on the shore) stays what it is.
     */
    public BiomeType apply(BiomeType mapped, short modelId, long x, long z, SeaProbe sea) {
        if (!enabled || !isHost(modelId) || (mapped != BiomeType.PLAINS && mapped != BiomeType.MEADOW)) {
            return mapped;
        }
        return isShadeRegion(x, z) && clearOfSea(x, z, sea) ? BiomeType.SHADE : mapped;
    }

    /** No open sea within {@link #SEA_CLEARANCE} blocks of (x, z), on two rings of eight probes. */
    static boolean clearOfSea(long x, long z, SeaProbe sea) {
        for (int ring = 1; ring <= 2; ring++) {
            long r = (long) SEA_CLEARANCE * ring / 2;
            for (int d = 0; d < RING_DX.length; d++) {
                // diagonals at the same distance as the axes (r / sqrt 2 per axis)
                long dx = RING_DX[d] * (RING_DZ[d] != 0 ? r * 707 / 1000 : r);
                long dz = RING_DZ[d] * (RING_DX[d] != 0 ? r * 707 / 1000 : r);
                if (sea.isSea(x + dx, z + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether block (x, z) lies in a region rolled Shade. */
    public boolean isShadeRegion(long x, long z) {
        return enabled && ShadeRegions.isShadeRegion(seed, x, z);
    }

    private static boolean isHost(short id) {
        return id == PLAINS_ID || id == FOREST_ID || id == MEADOW_ID || id == FOREST_SPARSE_ID;
    }
}
