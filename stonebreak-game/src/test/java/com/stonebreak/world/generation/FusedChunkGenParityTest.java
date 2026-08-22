package com.stonebreak.world.generation;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.heightmap.CavernCarver;
import com.stonebreak.world.generation.heightmap.MegaCavernCarver;
import com.stonebreak.world.generation.noise.TerrainNoise;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The fused native chunk generator ({@code ck_generate_chunk}) must be
 * BYTE-IDENTICAL to the legacy mixed path (native worms + Java caverns/
 * formations + Java block fill) — the worm walk is the same native code in
 * both, and the cavern/formation/fill/magma ports are exact (no libm). This
 * pins every C++ port against the living Java implementations, including the
 * kernel-returned sky heightmap vs. a Java recompute.
 *
 * <p>Chunk selection deliberately includes cavern- and megacavern-bearing
 * coordinates (found via the carvers' own hash predicates) so formations and
 * cavern connectors are exercised, not just plain terrain.
 *
 * <p>It also includes a {@code STONY_PEAKS}-bearing chunk. That is the only biome that reads
 * the crag channel in the overhang band, and none of the fixed coordinates above happen to
 * contain one — so without the search this test pinned the crag port <em>vacuously</em>, and a
 * Java/native divergence there would have gone unnoticed. The assertion below keeps that
 * coverage from silently lapsing if the terrain shifts.
 */
@Tag("regression")
class FusedChunkGenParityTest {

    private static final long SEED = 424242L;

    @Test
    void fusedPathMatchesLegacyPathByteForByte() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda kernels not built");
        assumeTrue(TerrainNoise.backend() == TerrainNoise.Backend.NATIVE, "native backend inactive");

        TerrainGenerationSystem fused = new TerrainGenerationSystem(SEED);
        assumeTrue(fused.isFusedGenerationActive(), "fused generation gate not active");

        TerrainGenerationSystem legacy;
        System.setProperty("stonebreak.terraingen.backend", "java");
        try {
            legacy = new TerrainGenerationSystem(SEED);
        } finally {
            System.clearProperty("stonebreak.terraingen.backend");
        }
        assertTrue(!legacy.isFusedGenerationActive(), "legacy system must not use the fused path");

        int[] stony = nearestStonyPeaksChunk(fused);
        assertTrue(stony != null,
            "no STONY_PEAKS chunk found in range — the crag channel would go untested");

        List<int[]> coordsList = interestingChunks();
        coordsList.add(stony);

        boolean sawNonTrivialChunk = false;
        for (int[] coords : coordsList) {
            Chunk chunkFused = fused.generateTerrainOnly(coords[0], coords[1]).chunk();
            Chunk chunkLegacy = legacy.generateTerrainOnly(coords[0], coords[1]).chunk();

            int nonAir = 0;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 256; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockType expected = chunkLegacy.getBlock(x, y, z);
                        assertEquals(expected, chunkFused.getBlock(x, y, z),
                            "block mismatch at chunk (" + coords[0] + "," + coords[1] + ") "
                                + x + "/" + y + "/" + z);
                        if (expected != BlockType.AIR) {
                            nonAir++;
                        }
                    }
                }
            }
            if (nonAir > 1000) {
                sawNonTrivialChunk = true;
            }

            // Kernel heightmap vs. Java recompute over the legacy chunk.
            assertTrue(chunkFused.getHeightMap().isPopulated(),
                "fused chunk heightmap must arrive populated");
            chunkLegacy.getHeightMap().recomputeAll(chunkLegacy.getOpacityProbe());
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    assertEquals(chunkLegacy.getHeightMap().getHeight(lx, lz),
                        chunkFused.getHeightMap().getHeight(lx, lz),
                        "heightmap mismatch at chunk (" + coords[0] + "," + coords[1] + ") "
                            + lx + "/" + lz);
                }
            }
        }
        assertTrue(sawNonTrivialChunk, "generation produced substantive terrain");
    }

    /**
     * Plain chunks plus the nearest cavern- and megacavern-bearing chunks (and
     * a neighbor of each, so cross-chunk blob spill and connectors are covered).
     */
    /**
     * The nearest chunk holding a {@code STONY_PEAKS} column. Searched rather than hardcoded so
     * the coordinate tracks the generator instead of pinning a number that quietly stops
     * meaning anything the next time the biome fields move.
     */
    private static int[] nearestStonyPeaksChunk(TerrainGenerationSystem gen) {
        for (int r = 0; r <= 40; r++) {
            for (int cx = -r; cx <= r; cx++) {
                for (int cz = -r; cz <= r; cz++) {
                    if (Math.max(Math.abs(cx), Math.abs(cz)) != r) {
                        continue;
                    }
                    if (chunkHasStonyPeaks(gen, cx, cz)) {
                        return new int[]{cx, cz};
                    }
                }
            }
        }
        return null;
    }

    private static boolean chunkHasStonyPeaks(TerrainGenerationSystem gen, int cx, int cz) {
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                if (gen.getBiomeAt(cx * 16 + lx, cz * 16 + lz) == BiomeType.STONY_PEAKS) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<int[]> interestingChunks() {
        List<int[]> chunks = new ArrayList<>(List.of(
            new int[]{0, 0}, new int[]{3, -2}, new int[]{-7, 11}, new int[]{25, 25}));
        CavernCarver cavern = new CavernCarver(SEED, null);
        MegaCavernCarver mega = new MegaCavernCarver(SEED, null);
        boolean foundCavern = false;
        boolean foundMega = false;
        for (int r = 0; r <= 40 && !(foundCavern && foundMega); r++) {
            for (int cx = -r; cx <= r && !(foundCavern && foundMega); cx++) {
                for (int cz = -r; cz <= r; cz++) {
                    if (Math.max(Math.abs(cx), Math.abs(cz)) != r) {
                        continue;
                    }
                    if (!foundCavern && cavern.hasCavern(cx, cz)) {
                        foundCavern = true;
                        chunks.add(new int[]{cx, cz});
                        chunks.add(new int[]{cx + 1, cz});
                    }
                    if (!foundMega && mega.hasCavern(cx, cz)) {
                        foundMega = true;
                        chunks.add(new int[]{cx, cz});
                        chunks.add(new int[]{cx, cz + 1});
                    }
                    if (foundCavern && foundMega) {
                        break;
                    }
                }
            }
        }
        return chunks;
    }
}
