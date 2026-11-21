package com.stonebreak.world.generation.diffusion.biomes;

import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chance-based Shade overlay of DaedalusTGM-Exp worlds ({@link ShadeOverlay}). */
class ShadeOverlayTest {

    private static final ShadeOverlay.SeaProbe NO_SEA = (x, z) -> false;

    private static final short PLAINS_ID = 1, FOREST_SPARSE_ID = 108, FOREST_ID = 8, SWAMP_ID = 6, DESERT_ID = 5,
            MEADOW_ID = 29, JUNGLE_ID = 23, SAVANNA_ID = 17;

    @Test
    void regionsAreShadeWithTheirChanceAndHoldTogether() {
        ShadeOverlay overlay = ShadeOverlay.of(4242L);
        int samples = 0, shade = 0, agree = 0, pairs = 0;
        for (long z = -40_000; z < 40_000; z += 97) {
            for (long x = -40_000; x < 40_000; x += 97) {
                boolean s = overlay.isShadeRegion(x, z);
                samples++;
                shade += s ? 1 : 0;
                pairs++;
                agree += s == overlay.isShadeRegion(x + 8, z) ? 1 : 0;
            }
        }
        double share = (double) shade / samples;
        assertTrue(share > ShadeOverlay.CHANCE - 0.08 && share < ShadeOverlay.CHANCE + 0.08, "region share " + share);
        assertTrue((double) agree / pairs > 0.97, "whole woods, not speckles: " + agree + "/" + pairs);
    }

    @Test
    void onlyTheModelsPlainsAndSparseForestTurnShade() {
        ShadeOverlay overlay = ShadeOverlay.of(7L);
        long[] spot = findShadeRegion(overlay);
        long x = spot[0], z = spot[1];
        assertEquals(BiomeType.SHADE, overlay.apply(BiomeType.PLAINS, PLAINS_ID, x, z, NO_SEA));
        assertEquals(BiomeType.SHADE, overlay.apply(BiomeType.MEADOW, FOREST_SPARSE_ID, x, z, NO_SEA));
        assertEquals(BiomeType.SHADE, overlay.apply(BiomeType.PLAINS, FOREST_ID, x, z, NO_SEA), "the model's forest is swallowed");
        assertEquals(BiomeType.SHADE, overlay.apply(BiomeType.MEADOW, MEADOW_ID, x, z, NO_SEA));
        assertEquals(BiomeType.PLAINS, overlay.apply(BiomeType.PLAINS, JUNGLE_ID, x, z, NO_SEA), "jungle stays");
        assertEquals(BiomeType.PLAINS, overlay.apply(BiomeType.PLAINS, SAVANNA_ID, x, z, NO_SEA), "savanna stays");
        assertEquals(BiomeType.SWAMP, overlay.apply(BiomeType.SWAMP, SWAMP_ID, x, z, NO_SEA));
        assertEquals(BiomeType.DESERT, overlay.apply(BiomeType.DESERT, DESERT_ID, x, z, NO_SEA));
        assertEquals(BiomeType.BEACH, overlay.apply(BiomeType.BEACH, PLAINS_ID, x, z, NO_SEA), "a shore stays a beach");
    }

    @Test
    void worldsWithoutTheShadeAndOtherSeedsDiffer() {
        ShadeOverlay a = ShadeOverlay.of(1L), b = ShadeOverlay.of(2L);
        long[] spot = findShadeRegion(a);
        assertEquals(BiomeType.PLAINS, ShadeOverlay.NONE.apply(BiomeType.PLAINS, PLAINS_ID, spot[0], spot[1], NO_SEA));
        assertFalse(ShadeOverlay.NONE.isShadeRegion(spot[0], spot[1]));
        int differ = 0;
        for (long x = 0; x < 64 * 640; x += 640) {
            differ += a.isShadeRegion(x, 0) != b.isShadeRegion(x, 0) ? 1 : 0;
        }
        assertTrue(differ > 0, "the regions follow the world seed");
        assertEquals(a.isShadeRegion(12_345, -6_789), ShadeOverlay.of(1L).isShadeRegion(12_345, -6_789), "pure");
    }

    /** A DaedalusTGM-Exp world's biome mapping: plains and jungle tiles, the Shade only where the overlay rolled it. */
    @Test
    void theBiomeManagerMapsTheModelsPlainsToShadeInShadeRegions() {
        int n = 256;
        java.util.Map<Long, TerrainTile> cache = new java.util.HashMap<>();
        TerrainTileSource tiles = (x, z) -> {
            int tx = Math.floorDiv(x, n), tz = Math.floorDiv(z, n);
            return cache.computeIfAbsent(((long) tx << 32) ^ (tz & 0xFFFFFFFFL), k -> {
                short[] h = new short[n * n], b = new short[n * n], w = new short[n * n];
                java.util.Arrays.fill(h, (short) 90);
                java.util.Arrays.fill(w, TerrainTile.NO_WATER);
                for (int i = 0; i < n * n; i++) {
                    b[i] = (i / n) < n / 2 ? PLAINS_ID : JUNGLE_ID;   // plains in the first half of the tile's rows
                }
                return new TerrainTile(tx, tz, tx * n, tz * n, tx * n + n, tz * n + n, n, n, h, b, w);
            });
        };
        ShadeOverlay overlay = ShadeOverlay.of(99L);
        BiomeManager with = new BiomeManager(tiles, BeachRule.LEGACY, overlay);
        BiomeManager without = new BiomeManager(tiles, BeachRule.LEGACY);
        // around a Shade region, wide enough to cross its border
        long[] spot = findShadeRegion(overlay);
        int x0 = (int) spot[0] - 2048, z0 = (int) spot[1] - 512;
        int shade = 0, plains = 0;
        for (int x = x0; x < x0 + 4096; x += 7) {
            for (int z = z0; z < z0 + 1024; z += 7) {
                boolean modelPlains = Math.floorMod(x, n) < n / 2;
                BiomeType mapped = with.getBiome(x, z);
                assertEquals(BiomeType.PLAINS, without.getBiome(x, z), "no overlay: plains and jungle both map to PLAINS");
                if (!modelPlains) {
                    assertEquals(BiomeType.PLAINS, mapped, "the model's jungle never turns Shade");
                    continue;
                }
                plains++;
                assertEquals(overlay.isShadeRegion(x, z) ? BiomeType.SHADE : BiomeType.PLAINS, mapped);
                shade += mapped == BiomeType.SHADE ? 1 : 0;
            }
        }
        assertTrue(shade > 0 && shade < plains, shade + " of " + plains + " plains columns");
    }

    /** Never on a small isle nor along the shore: no open sea within SEA_CLEARANCE blocks. */
    @Test
    void smallIslesAndShoresStayClear() {
        ShadeOverlay overlay = ShadeOverlay.of(7L);
        long[] spot = findShadeRegion(overlay);
        long x = spot[0], z = spot[1];
        // an isle of radius 60 around (x, z): sea beyond it
        ShadeOverlay.SeaProbe isle = (px, pz) -> (px - x) * (px - x) + (pz - z) * (pz - z) > 60 * 60;
        assertEquals(BiomeType.PLAINS, overlay.apply(BiomeType.PLAINS, PLAINS_ID, x, z, isle), "a small isle");
        // a continent: the sea starts 300 blocks east of (x, z)
        ShadeOverlay.SeaProbe coast = (px, pz) -> px > x + 300;
        assertEquals(BiomeType.SHADE, overlay.apply(BiomeType.PLAINS, PLAINS_ID, x, z, coast), "deep land");
        if (overlay.isShadeRegion(x + 250, z)) {
            assertEquals(BiomeType.PLAINS, overlay.apply(BiomeType.PLAINS, PLAINS_ID, x + 250, z, coast), "the shore strip");
        }
        assertTrue(ShadeOverlay.clearOfSea(x, z, coast));
        assertFalse(ShadeOverlay.clearOfSea(x + 300 - ShadeOverlay.SEA_CLEARANCE + 10, z, coast));
    }

    private static long[] findShadeRegion(ShadeOverlay overlay) {
        for (long x = 0; x < 100_000; x += 64) {
            if (overlay.isShadeRegion(x, 0)) return new long[]{x, 0};
        }
        throw new AssertionError("no Shade region");
    }
}
