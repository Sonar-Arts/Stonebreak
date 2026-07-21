package com.stonebreak.world.generation.noise;

import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the invariant the FastLOD ring depends on: per-point sampling
 * (FastLOD, worm carver) and batched chunk sampling must agree bit-for-bit at
 * the same block coordinate — on whichever backend is active.
 *
 * On the Java backend these pass trivially (fill delegates to sample); on the
 * native backend they prove the integer-position/DomainScale design holds
 * through the whole stack.
 */
class TerrainNoiseParityTest {

    private static final long SEED = 987654321L;

    @Test
    void perPointChannelsMatchBatchedGrids() {
        NoiseRouter router = new NoiseRouter(SEED);
        int baseX = -3200, baseZ = 4816; // arbitrary, crosses zero on neither axis
        int n = 16;
        float[] c = new float[n * n];
        float[] pv = new float[n * n];
        float[] e = new float[n * n];
        float[] d = new float[n * n];
        float[] tRaw = new float[n * n];
        float[] mRaw = new float[n * n];
        router.fillShapeChannels(baseX, baseZ, n, n, 1, c, pv, e, d);
        router.fillClimateChannels(baseX, baseZ, n, n, 1, tRaw, mRaw);

        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                int idx = x * n + z;
                int wx = baseX + x, wz = baseZ + z;
                assertEquals(router.continentalness(wx, wz), c[idx], "continentalness (" + wx + "," + wz + ")");
                assertEquals(router.peaksValleys(wx, wz), pv[idx], "peaksValleys (" + wx + "," + wz + ")");
                assertEquals(router.erosion(wx, wz), e[idx], "erosion (" + wx + "," + wz + ")");
                assertEquals(router.detail(wx, wz), d[idx], "detail (" + wx + "," + wz + ")");
                assertEquals(router.moisture(wx, wz), NoiseRouter.moistureFromRaw(mRaw[idx]), "moisture");
                assertEquals(router.temperature(wx, wz, 80),
                    NoiseRouter.temperatureFromRaw(tRaw[idx], 80), "temperature");
            }
        }
    }

    @Test
    void chunkHeightsMatchPerPointHeights() {
        // Exactly what FastLOD L0 requires: populateChunkHeights (batched)
        // must equal generateHeight (per-point) for every column.
        NoiseRouter router = new NoiseRouter(SEED);
        HeightMapGenerator heightMap = new HeightMapGenerator(router);
        int chunkX = -7, chunkZ = 13;
        int[] batched = new int[16 * 16];
        heightMap.populateChunkHeights(chunkX, chunkZ, batched);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                assertEquals(heightMap.generateHeight(chunkX * 16 + x, chunkZ * 16 + z),
                    batched[x * 16 + z],
                    "column (" + x + "," + z + ") of chunk (" + chunkX + "," + chunkZ + ")");
            }
        }
    }

    @Test
    void stridedFillMatchesPerPoint() {
        // FastLOD samples at cell-size strides; strided grids must hit the
        // same values as per-point sampling at those coordinates.
        NoiseRouter router = new NoiseRouter(SEED);
        int stride = 8, n = 6, baseX = 1024, baseZ = -512;
        float[] c = new float[n * n];
        float[] pv = new float[n * n];
        float[] e = new float[n * n];
        float[] d = new float[n * n];
        router.fillShapeChannels(baseX, baseZ, n, n, stride, c, pv, e, d);
        for (int ix = 0; ix < n; ix++) {
            for (int iz = 0; iz < n; iz++) {
                assertEquals(router.continentalness(baseX + ix * stride, baseZ + iz * stride),
                    c[ix * n + iz], "strided continentalness");
            }
        }
    }

    @Test
    void seedsProduceDistinctDeterministicTerrain() {
        NoiseRouter a1 = new NoiseRouter(SEED);
        NoiseRouter a2 = new NoiseRouter(SEED);
        NoiseRouter b = new NoiseRouter(SEED + 1);
        assertEquals(a1.continentalness(100, 200), a2.continentalness(100, 200),
            "same seed must reproduce exactly");
        assertNotEquals(a1.continentalness(100, 200), b.continentalness(100, 200),
            "different seeds must diverge");
    }

    @Test
    void batchedColumnProbeMatchesPerPointApi() {
        // FastLodSampler now feeds off sampleColumns; every value must equal
        // the per-point API it replaced (heights, surface blocks, trees).
        var terrain = new com.stonebreak.world.generation.TerrainGenerationSystem(SEED);
        int count = 10, stride = 4, x0 = -333, z0 = 777;
        int[] heights = new int[count * count];
        var surface = new com.stonebreak.blocks.BlockType[count * count];
        var trees = new com.stonebreak.world.generation.features.VegetationGenerator.TreeSample[count * count];
        terrain.sampleColumns(x0, z0, count, stride, heights, surface, trees);
        for (int ix = 0; ix < count; ix++) {
            for (int iz = 0; iz < count; iz++) {
                int idx = ix * count + iz;
                int wx = x0 + ix * stride, wz = z0 + iz * stride;
                assertEquals(terrain.getFinalTerrainHeightAt(wx, wz), heights[idx], "height @" + wx + "," + wz);
                assertEquals(terrain.getSurfaceBlockAt(wx, wz), surface[idx], "surface @" + wx + "," + wz);
                assertEquals(terrain.getTreeAt(wx, wz), trees[idx], "tree @" + wx + "," + wz);
            }
        }
    }

    @Test
    void nativeBackendIsActiveWhenLibraryPresent() {
        // Documents which backend this test run exercised. Only meaningful
        // when the native lib is built; skipped otherwise so CI without the
        // lib stays green.
        assumeTrue(com.openmason.engine.cenda.CendaKernels.isAvailable(),
            "Cenda kernels not built — parity tests above ran on the Java backend");
        assertEquals(TerrainNoise.Backend.NATIVE, TerrainNoise.backend(),
            "native lib is present but backend resolution did not pick it");
    }
}
