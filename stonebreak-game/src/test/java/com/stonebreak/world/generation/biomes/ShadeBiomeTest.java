package com.stonebreak.world.generation.biomes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.generation.features.VegetationGenerator;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeKind;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeSample;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.noise.MultiNoiseSample;
import com.stonebreak.world.generation.noise.NoiseRouter;

/** The Shade takes dry temperate flat and hilly land, and grows dense but clumped dry oaks. */
class ShadeBiomeTest {

    private final BiomeSelector selector = new BiomeSelector();

    /** c, erosion, pv, temperature, moisture. */
    private BiomeType select(float c, float e, float pv, float t, float m) {
        return selector.select(new MultiNoiseSample(c, e, pv, t, m), false);
    }

    @Test
    void dryTemperateFlatAndHillyLandIsShade() {
        assertEquals(BiomeType.SHADE, select(0.3f, 0.5f, 0.0f, 0.5f, 0.35f));   // flat
        assertEquals(BiomeType.SHADE, select(0.3f, 0.5f, 0.2f, 0.45f, 0.32f));  // hilly
        assertEquals(BiomeType.SHADE, select(0.0f, 0.5f, -0.5f, 0.55f, 0.40f)); // valley floor
    }

    @Test
    void notShadeWhenWetAridColdWarmCoastalMountainOrSubmerged() {
        assertNotEquals(BiomeType.SHADE, select(0.3f, 0.5f, 0.0f, 0.5f, 0.55f));   // moist: plains
        assertNotEquals(BiomeType.SHADE, select(0.3f, 0.5f, 0.0f, 0.5f, 0.27f));   // arid: plains
        assertNotEquals(BiomeType.SHADE, select(0.3f, 0.5f, 0.0f, 0.3f, 0.35f));   // cold
        assertNotEquals(BiomeType.SHADE, select(0.3f, 0.5f, 0.0f, 0.7f, 0.35f));   // warm: red sand
        assertNotEquals(BiomeType.SHADE, select(-0.3f, 0.5f, 0.0f, 0.5f, 0.35f));  // coast
        assertNotEquals(BiomeType.SHADE, select(0.3f, 0.5f, 0.6f, 0.5f, 0.35f));   // mountain
        assertNotEquals(BiomeType.SHADE,
            selector.select(new MultiNoiseSample(0.3f, 0.5f, 0.0f, 0.5f, 0.35f), true));
    }

    @Test
    void shadeIsCommonButNotDominantOnLand() {
        NoiseRouter noise = new NoiseRouter(1234L);
        BiomeManager biomes = new BiomeManager(noise, new HeightMapGenerator(noise));
        int land = 0;
        int shade = 0;
        int plains = 0;
        for (int x = -40_000; x < 40_000; x += 97) {
            for (int z = -40_000; z < 40_000; z += 97) {
                BiomeType b = biomes.getBiome(x, z);
                if (b == BiomeType.BEACH || b == BiomeType.ICE_FIELDS || b == BiomeType.OCEAN) continue;
                land++;
                if (b == BiomeType.SHADE) shade++;
                if (b == BiomeType.PLAINS) plains++;
            }
        }
        double share = shade / (double) land;
        System.out.printf("[ShadeBiomeTest] shade %.2f%%, plains %.2f%% of land (%d)%n",
            share * 100, plains * 100.0 / land, land);
        assertTrue(share > 0.03 && share < 0.12, "shade share " + share);
        assertTrue(plains > shade, "plains should stay the commoner temperate biome");
    }

    @Test
    void dryOakIsProbedOnlyInTheShade() {
        DeterministicRandom rng = new DeterministicRandom(99L);
        int dryOak = 0;
        for (int x = 0; x < 300; x++) {
            for (int z = 0; z < 300; z++) {
                TreeSample s = VegetationGenerator.probeTree(x, z, BiomeType.SHADE, BlockType.DRY_GRASS, rng);
                if (s != null) {
                    assertEquals(TreeKind.DRY_OAK, s.kind());
                    dryOak++;
                }
                for (BiomeType other : BiomeType.values()) {
                    if (other == BiomeType.SHADE) continue;
                    for (BlockType ground : new BlockType[]{BlockType.GRASS, BlockType.DRY_GRASS, BlockType.SNOWY_DIRT}) {
                        TreeSample o = VegetationGenerator.probeTree(x, z, other, ground, rng);
                        assertTrue(o == null || o.kind() != TreeKind.DRY_OAK, other + " grew a dry oak");
                    }
                }
            }
        }
        assertTrue(dryOak > 0, "the Shade should grow dry oaks");
    }

    /**
     * Woodland throughout, but still grouped: several times plains' trees overall, the woods'
     * cores capped well below a plains forest core scaled up (the 2026-10-30 first cut peaked
     * at 170 trees per 32x32 window and averaged 49), and the thinnest glades several times
     * sparser than the thickest woods, thin enough to read as clearings.
     */
    @Test
    void shadeIsEvenWoodlandThatStillGroups() {
        DeterministicRandom rng = new DeterministicRandom(7L);
        final int size = 1024;
        final int window = 32;
        int shadeTrees = 0;
        int plainsTrees = 0;
        int windows = 0;
        int densestWindow = 0;
        int sparsestWindow = Integer.MAX_VALUE;
        for (int wx = 0; wx < size; wx += window) {
            for (int wz = 0; wz < size; wz += window) {
                int inWindow = 0;
                for (int x = wx; x < wx + window; x++) {
                    for (int z = wz; z < wz + window; z++) {
                        if (VegetationGenerator.probeTree(x, z, BiomeType.SHADE, BlockType.DRY_GRASS, rng) != null) {
                            inWindow++;
                        }
                        if (VegetationGenerator.probeTree(x, z, BiomeType.PLAINS, BlockType.GRASS, rng) != null) {
                            plainsTrees++;
                        }
                    }
                }
                shadeTrees += inWindow;
                windows++;
                densestWindow = Math.max(densestWindow, inWindow);
                sparsestWindow = Math.min(sparsestWindow, inWindow);
            }
        }
        double mean = shadeTrees / (double) windows;
        System.out.printf("[ShadeBiomeTest] trees per 32x32: shade %.1f (densest %d, sparsest %d), plains %.1f%n",
            mean, densestWindow, sparsestWindow, plainsTrees / (double) windows);
        assertTrue(shadeTrees > 4 * plainsTrees, "shade " + shadeTrees + " vs plains " + plainsTrees);
        assertTrue(mean > 49, "the Shade should hold more trees than its first cut's 49 per window: " + mean);
        assertTrue(densestWindow < 120, "the woods' cores should be thinner than the first cut's 170: " + densestWindow);
        assertTrue(densestWindow > 3 * Math.max(1, sparsestWindow), "the trees should still group");
        assertTrue(sparsestWindow <= 10, "the lows should open into clearings: " + sparsestWindow);
    }
}
