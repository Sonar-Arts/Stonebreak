package com.stonebreak.world.generation.biomes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.noise.MultiNoiseSample;
import com.stonebreak.world.generation.noise.NoiseRouter;

/** The Shade takes dry temperate flat and hilly land, and grows dense but clumped dry oaks. */
class ShadeBiomeTest {

    private final BiomeSelector selector = new BiomeSelector();

    /** land, mountain, hilly, rough, shaped height, temperature, moisture (DistilledShape fields + climate). */
    private BiomeType select(float land, float mountain, float hilly, float rough, int height, float t, float m) {
        return selector.select(new MultiNoiseSample(land, mountain, hilly, rough, height, t, m), false);
    }

    @Test
    void dryTemperateFlatAndHillyLandIsShade() {
        assertEquals(BiomeType.SHADE, select(1.0f, 0.0f, 0.1f, 0.15f, 90, 0.5f, 0.35f));   // flat
        assertEquals(BiomeType.SHADE, select(1.0f, 0.0f, 0.5f, 0.30f, 125, 0.45f, 0.32f)); // hill country
        assertEquals(BiomeType.SHADE, select(1.0f, 0.5f, 0.1f, 0.30f, 130, 0.55f, 0.40f)); // range flank
    }

    @Test
    void notShadeWhenWetAridColdWarmCoastalMountainOrSubmerged() {
        assertNotEquals(BiomeType.SHADE, select(1.0f, 0.0f, 0.1f, 0.15f, 90, 0.5f, 0.55f));  // moist: plains
        assertNotEquals(BiomeType.SHADE, select(1.0f, 0.0f, 0.1f, 0.15f, 90, 0.5f, 0.27f));  // arid: plains
        assertNotEquals(BiomeType.SHADE, select(1.0f, 0.0f, 0.1f, 0.15f, 90, 0.3f, 0.35f));  // cold
        assertNotEquals(BiomeType.SHADE, select(1.0f, 0.0f, 0.1f, 0.15f, 90, 0.7f, 0.35f));  // warm: red sand
        assertNotEquals(BiomeType.SHADE, select(0.7f, 0.0f, 0.1f, 0.15f, 65, 0.5f, 0.35f));  // shore strip
        assertNotEquals(BiomeType.SHADE, select(1.0f, 0.7f, 0.1f, 0.15f, 170, 0.5f, 0.35f)); // range body
        assertNotEquals(BiomeType.SHADE,
            selector.select(new MultiNoiseSample(1.0f, 0.0f, 0.1f, 0.15f, 60, 0.5f, 0.35f), true));
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
}
