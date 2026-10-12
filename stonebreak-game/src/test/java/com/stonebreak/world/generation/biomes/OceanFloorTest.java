package com.stonebreak.world.generation.biomes;

import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The OCEAN sea floor mixes 97% sand, 2% dirt and 1% clay, per column and deterministically. */
class OceanFloorTest {

    @Test
    void seaFloorIsNinetySevenPercentSandTwoDirtOneClay() {
        int sand = 0, dirt = 0, clay = 0, n = 0;
        for (int x = -250; x < 250; x++) {
            for (int z = -200; z < 200; z++) {
                BlockType b = OceanFloor.block(x, z, 1234L);
                if (b == BlockType.SAND) sand++;
                else if (b == BlockType.DIRT) dirt++;
                else if (b == BlockType.CLAY) clay++;
                n++;
            }
        }
        assertEquals(n, sand + dirt + clay, "only sand, dirt and clay on the sea floor");
        assertEquals(0.97, sand / (double) n, 0.005);
        assertEquals(0.02, dirt / (double) n, 0.003);
        assertEquals(0.01, clay / (double) n, 0.003);
    }

    @Test
    void sameColumnAlwaysGetsTheSameBlockAndSeedsDiffer() {
        int differ = 0;
        for (int i = 0; i < 2000; i++) {
            int x = i * 37 - 5000, z = i * 91 + 17;
            assertEquals(OceanFloor.block(x, z, 7L), OceanFloor.block(x, z, 7L));
            if (OceanFloor.block(x, z, 7L) != OceanFloor.block(x, z, 8L)) differ++;
        }
        assertTrue(differ > 20, "different worlds should scatter dirt and clay differently");
    }
}
