package com.stonebreak.world.generation.biomes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.noise.MultiNoiseSample;
import com.stonebreak.world.generation.noise.NoiseRouter;

/**
 * The Shade in Standard worlds (Java mirror of native shape.cpp {@code shadeOver}; the terrain mapper reads it): inside
 * a Shade region ({@link ShadeRegions}) it takes all temperate-to-warm land whatever its moisture, so it stands as one
 * mass; it stops at the sea and shore, mountains and ice, the cold and the hot. The selector itself never picks it.
 */
class ShadeBiomeTest {

    private static final long SEED = 1234L;

    private final BiomeSelector selector = new BiomeSelector();

    private static MultiNoiseSample sample(int height, float t, float m) {
        return new MultiNoiseSample(1.0f, 0.0f, 0.1f, 0.15f, height, t, m);
    }

    private static long[] inside(boolean shade) {
        for (long x = 0; x < 200_000; x += 97) {
            if (ShadeRegions.isShadeRegion(SEED, x, 0) == shade) return new long[]{x, 0};
        }
        throw new AssertionError("no region");
    }

    @Test
    void insideARegionTheShadeTakesTemperateLandWhateverItsMoisture() {
        long[] in = inside(true);
        for (float m : new float[]{0.15f, 0.35f, 0.55f, 0.75f}) {   // arid, dry, moist, wet
            MultiNoiseSample s = sample(90, 0.5f, m);
            BiomeType selected = selector.select(s, false);
            assertNotEquals(BiomeType.SHADE, selected, "the selector never picks it");
            assertEquals(BiomeType.SHADE, BiomeSelector.shadeOver(selected, s, SEED, in[0], in[1]), "moisture " + m);
        }
        MultiNoiseSample warm = sample(90, 0.7f, 0.35f);   // warm and dry: red sand, swallowed too
        assertEquals(BiomeType.SHADE, BiomeSelector.shadeOver(selector.select(warm, false), warm, SEED, in[0], in[1]));
    }

    @Test
    void itStopsAtTheSeaMountainsIceTheColdAndTheHot() {
        long[] in = inside(true);
        MultiNoiseSample temperate = sample(90, 0.5f, 0.35f);
        for (BiomeType wall : new BiomeType[]{BiomeType.OCEAN, BiomeType.BEACH, BiomeType.ICE_FIELDS,
                BiomeType.STONY_PEAKS, BiomeType.SNOWY_PLAINS, BiomeType.TAIGA, BiomeType.TUNDRA}) {
            assertEquals(wall, BiomeSelector.shadeOver(wall, temperate, SEED, in[0], in[1]));
        }
        MultiNoiseSample cold = sample(90, 0.30f, 0.35f), hot = sample(90, 0.85f, 0.20f);
        assertEquals(selector.select(cold, false), BiomeSelector.shadeOver(selector.select(cold, false), cold, SEED, in[0], in[1]));
        assertEquals(selector.select(hot, false), BiomeSelector.shadeOver(selector.select(hot, false), hot, SEED, in[0], in[1]));
        long[] out = inside(false);
        assertEquals(BiomeType.PLAINS, BiomeSelector.shadeOver(BiomeType.PLAINS, temperate, SEED, out[0], out[1]));
    }

    @Test
    void shadeIsRegionalAndPlainsStayCommoner() {
        NoiseRouter noise = new NoiseRouter(SEED);
        BiomeManager biomes = new BiomeManager(noise, new HeightMapGenerator(noise)).withShade(SEED, 1.0);
        int land = 0, shade = 0, plains = 0;
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
        // 3 % of regions (ShadeRegions.CHANCE), only their deep temperate land: a rare landmark (~2 % of land).
        assertTrue(share > 0.005 && share < 0.05, "shade share " + share);
        assertTrue(plains > shade, "plains should stay the commoner temperate biome");
    }
}
