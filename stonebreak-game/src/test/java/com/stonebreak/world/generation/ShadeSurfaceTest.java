package com.stonebreak.world.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.biomes.BiomeType;

/** The Shade lays dry grass over dirt, so its dry oaks have ground to grow on. */
class ShadeSurfaceTest {

    @Test
    void shadeIsDryGrassOverDirt() {
        assertEquals(BlockType.DRY_GRASS, TerrainGenerationSystem.surfaceBlock(BiomeType.SHADE));
        assertEquals(BlockType.DIRT, TerrainGenerationSystem.subsurfaceBlock(BiomeType.SHADE));
    }
}
