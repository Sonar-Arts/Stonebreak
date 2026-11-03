package com.stonebreak.world.generation.noise;

import com.stonebreak.world.generation.heightmap.DistilledShape;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
@Tag("regression")
class TerrainNoiseParityTest {

    private static final long SEED = 987654321L;

    @Test
    void perPointChannelsMatchBatchedGrids() {
        NoiseRouter router = new NoiseRouter(SEED);
        int baseX = -3200, baseZ = 4816; // arbitrary, crosses zero on neither axis
        int n = 16;
        DistilledShape.Column[] cols = new DistilledShape.Column[n * n];
        float[] tRaw = new float[n * n];
        float[] mRaw = new float[n * n];
        router.shape().fill(baseX, baseZ, n, n, 1, cols);
        router.fillClimateChannels(baseX, baseZ, n, n, 1, tRaw, mRaw);

        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                int idx = x * n + z;
                int wx = baseX + x, wz = baseZ + z;
                DistilledShape.Column p = new DistilledShape.Column();
                router.shape().column(wx, wz, p);
                assertEquals(p.height, cols[idx].height, "height (" + wx + "," + wz + ")");
                assertEquals(p.shapedHeight, cols[idx].shapedHeight, "shaped (" + wx + "," + wz + ")");
                assertEquals(p.land, cols[idx].land, "land (" + wx + "," + wz + ")");
                assertEquals(p.mountain, cols[idx].mountain, "mountain (" + wx + "," + wz + ")");
                assertEquals(p.rough, cols[idx].rough, "rough (" + wx + "," + wz + ")");
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
        DistilledShape.Column[] cols = new DistilledShape.Column[n * n];
        router.shape().fill(baseX, baseZ, n, n, stride, cols);
        for (int ix = 0; ix < n; ix++) {
            for (int iz = 0; iz < n; iz++) {
                DistilledShape.Column p = new DistilledShape.Column();
                router.shape().column(baseX + ix * stride, baseZ + iz * stride, p);
                assertEquals(p.elevationM, cols[ix * n + iz].elevationM, "strided elevation");
                assertEquals(p.height, cols[ix * n + iz].height, "strided height");
            }
        }
    }

    @Test
    void seedsProduceDistinctDeterministicTerrain() {
        NoiseRouter a1 = new NoiseRouter(SEED);
        NoiseRouter a2 = new NoiseRouter(SEED);
        NoiseRouter b = new NoiseRouter(SEED + 1);
        DistilledShape.Column c1 = new DistilledShape.Column();
        DistilledShape.Column c2 = new DistilledShape.Column();
        DistilledShape.Column c3 = new DistilledShape.Column();
        a1.shape().column(100, 200, c1);
        a2.shape().column(100, 200, c2);
        b.shape().column(100, 200, c3);
        assertEquals(c1.elevationM, c2.elevationM, "same seed must reproduce exactly");
        assertNotEquals(c1.elevationM, c3.elevationM, "different seeds must diverge");
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
        terrain.sampleColumns(x0, z0, count, stride, heights, null, surface, trees);
        int cut = 0;
        for (int ix = 0; ix < count; ix++) {
            for (int iz = 0; iz < count; iz++) {
                int idx = ix * count + iz;
                int wx = x0 + ix * stride, wz = z0 + iz * stride;
                // sampleColumns reports the CARVED top (a ravine or sinkhole on the probe grid lowers
                // it), so its per-point counterpart is carvedSurfaceHeight, not the raw height.
                assertEquals(terrain.carvedSurfaceHeight(wx, wz), heights[idx], "height @" + wx + "," + wz);
                if (heights[idx] != terrain.getFinalTerrainHeightAt(wx, wz)) {
                    // A cut column exposes stone and grows no tree; the per-point surface/tree API
                    // describes the uncut column, so it has nothing to compare against here.
                    cut++;
                    continue;
                }
                assertEquals(terrain.getSurfaceBlockAt(wx, wz), surface[idx], "surface @" + wx + "," + wz);
                assertEquals(terrain.getTreeAt(wx, wz), trees[idx], "tree @" + wx + "," + wz);
            }
        }
        assertTrue(cut < count * count / 4, "most probe columns must be uncut to compare surfaces: " + cut);
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
