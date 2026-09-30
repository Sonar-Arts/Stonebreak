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

/** Swamps sit on flat, wet, non-cold lowland, and only swamps grow cypress. */
class SwampBiomeTest {

    private final BiomeSelector selector = new BiomeSelector();

    /** c, erosion, pv, temperature, moisture. */
    private BiomeType select(float c, float e, float pv, float t, float m) {
        return selector.select(new MultiNoiseSample(c, e, pv, t, m));
    }

    @Test
    void flatWetTemperateLowlandIsSwamp() {
        assertEquals(BiomeType.SWAMP, select(0.0f, 0.5f, 0.0f, 0.5f, 0.8f));
        assertEquals(BiomeType.SWAMP, select(-0.1f, 0.3f, -0.2f, 0.7f, 0.7f));
    }

    @Test
    void notSwampWhenHillyHighValleyCoastalDryOrCold() {
        assertNotEquals(BiomeType.SWAMP, select(0.0f, 0.5f, 0.2f, 0.5f, 0.8f));   // hilly
        assertNotEquals(BiomeType.SWAMP, select(0.0f, 0.5f, 0.6f, 0.5f, 0.8f));   // mountain
        assertNotEquals(BiomeType.SWAMP, select(0.5f, 0.5f, 0.0f, 0.5f, 0.8f));   // high inland
        assertNotEquals(BiomeType.SWAMP, select(0.0f, 0.5f, -0.5f, 0.5f, 0.8f));  // valley floor
        assertNotEquals(BiomeType.SWAMP, select(-0.3f, 0.5f, 0.0f, 0.5f, 0.8f));  // coast
        assertNotEquals(BiomeType.SWAMP, select(0.0f, 0.5f, 0.0f, 0.5f, 0.5f));   // dry
        assertNotEquals(BiomeType.SWAMP, select(0.0f, 0.5f, 0.0f, 0.3f, 0.8f));   // cold
    }

    @Test
    void swampsAreCommonButNotDominantOnLand() {
        NoiseRouter noise = new NoiseRouter(1234L);
        BiomeManager biomes = new BiomeManager(noise, new HeightMapGenerator(noise));
        int land = 0;
        int swamp = 0;
        for (int x = -40_000; x < 40_000; x += 97) {
            for (int z = -40_000; z < 40_000; z += 97) {
                BiomeType b = biomes.getBiome(x, z);
                if (b == BiomeType.BEACH || b == BiomeType.ICE_FIELDS) continue;
                land++;
                if (b == BiomeType.SWAMP) swamp++;
            }
        }
        double share = swamp / (double) land;
        System.out.printf("[SwampBiomeTest] swamp share of land: %.2f%% (%d / %d)%n", share * 100, swamp, land);
        assertTrue(share > 0.01 && share < 0.15, "swamp share " + share);
    }

    @Test
    void cypressIsProbedOnlyInSwamps() {
        DeterministicRandom rng = new DeterministicRandom(99L);
        int cypress = 0;
        for (int x = 0; x < 300; x++) {
            for (int z = 0; z < 300; z++) {
                TreeSample swamp = VegetationGenerator.probeTree(x, z, BiomeType.SWAMP, BlockType.SWAMPY_GRASS, rng);
                if (swamp != null) {
                    assertEquals(TreeKind.CYPRESS, swamp.kind());
                    cypress++;
                }
                for (BiomeType other : BiomeType.values()) {
                    if (other == BiomeType.SWAMP) continue;
                    for (BlockType ground : new BlockType[]{BlockType.GRASS, BlockType.SWAMPY_GRASS, BlockType.SNOWY_DIRT}) {
                        TreeSample s = VegetationGenerator.probeTree(x, z, other, ground, rng);
                        assertTrue(s == null || s.kind() != TreeKind.CYPRESS, other + " grew a cypress");
                    }
                }
            }
        }
        assertTrue(cypress > 0, "swamps should grow some cypress");
    }
}
