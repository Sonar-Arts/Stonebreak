package com.stonebreak.world.generation;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.noise.TerrainNoise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end determinism of native-backend chunk generation: two independent
 * TerrainGenerationSystem instances (each with its own native carver context,
 * channel nodes and density field) must produce byte-identical chunks. Covers
 * the native noise channels, the batched density volume, and the native worm
 * carver in one sweep — if any of them were nondeterministic or disagreed
 * across instances, blocks would diverge.
 */
class NativeCarverIntegrationTest {

    private static final long SEED = 424242L;

    @Test
    void independentGeneratorsProduceIdenticalChunks() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda kernels not built");
        assumeTrue(TerrainNoise.backend() == TerrainNoise.Backend.NATIVE, "native backend inactive");

        TerrainGenerationSystem a = new TerrainGenerationSystem(SEED);
        TerrainGenerationSystem b = new TerrainGenerationSystem(SEED);

        int[][] chunks = {{0, 0}, {3, -2}, {-7, 11}, {25, 25}};
        boolean sawNonTrivialChunk = false;
        for (int[] coords : chunks) {
            var chunkA = a.generateTerrainOnly(coords[0], coords[1]).chunk();
            var chunkB = b.generateTerrainOnly(coords[0], coords[1]).chunk();
            int nonAir = 0;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 256; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockType blockA = chunkA.getBlock(x, y, z);
                        assertEquals(blockA, chunkB.getBlock(x, y, z),
                            "block mismatch at chunk (" + coords[0] + "," + coords[1] + ") "
                                + x + "/" + y + "/" + z);
                        if (blockA != BlockType.AIR) {
                            nonAir++;
                        }
                    }
                }
            }
            if (nonAir > 1000) {
                sawNonTrivialChunk = true;
            }
        }
        assertTrue(sawNonTrivialChunk, "generation produced substantive terrain");
    }
}
