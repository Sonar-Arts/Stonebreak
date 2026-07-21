package com.stonebreak.world;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration.CendaMesher;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.generation.heightmap.PerlinWormCarver;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.generation.noise.TerrainNoise;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Manual benchmark over REAL generated terrain: native mesher kernel vs an
 * equivalent pure-Java flat-array pass (a conservative lower bound for the
 * production Java path, which additionally pays paletted virtual reads),
 * native vs Java worm carver, zstd vs DEFLATE. Run explicitly:
 * mvn test -pl stonebreak-game -Dtest=CendaChunkBenchmarkTest -Dcenda.bench=true
 */
class CendaChunkBenchmarkTest {

    private static final long SEED = 20260720L;

    @Test
    void benchmark() throws Exception {
        assumeTrue(Boolean.getBoolean("cenda.bench"), "manual benchmark (-Dcenda.bench=true)");
        assumeTrue(CendaKernels.isAvailable(), "Cenda kernels not built");
        assumeTrue(TerrainNoise.backend() == TerrainNoise.Backend.NATIVE, "native backend inactive");

        TerrainGenerationSystem terrain = new TerrainGenerationSystem(SEED);
        var chunk = terrain.generateTerrainOnly(0, 0).chunk();
        var storage = (CcoPalettedChunkStorage) chunk.getBlockStorageView();

        // ── Mesher inputs from the real chunk ──
        short[] blocks = new short[65536];
        for (int s = 0; s < storage.getSectionCount(); s++) {
            storage.getSection(s).writeBlockIdsInto(blocks, s * 4096);
        }
        CendaMesher.rebuildClassTable(t -> false);
        byte[] classTable = CendaMesher.classTable();
        short[] heights = new short[18 * 18];
        for (int lz = -1; lz <= 16; lz++) {
            for (int lx = -1; lx <= 16; lx++) {
                heights[(lz + 1) * 18 + (lx + 1)] =
                    (short) terrain.getFinalTerrainHeightAt(lx, lz);
            }
        }
        int highest = 255;
        for (int y = 255; y >= 0 && highest == 255; y--) {
            for (int i = 0; i < 256; i++) {
                if (blocks[y * 256 + i] != 0) { highest = Math.min(y + 1, 255); break; }
            }
        }
        final int maxY = highest;

        float[] out = new float[9 * 70000];
        int quads = CendaKernels.meshChunk(blocks, classTable, 0, null, null, null, null,
            null, null, null, null, heights, maxY, true, out);
        System.out.printf("mesher: %d quads from real chunk (maxY=%d)%n", quads, maxY);

        long nativeNs = best(30, 200, () -> CendaKernels.meshChunk(blocks, classTable, 0,
            null, null, null, null, null, null, null, null, heights, maxY, true, out));
        long javaNs = best(30, 200, () -> javaReferenceMesh(blocks, classTable, heights, maxY));
        System.out.printf("mesher: java-flat %.1f us/chunk, native %.1f us/chunk, speedup %.1fx%n",
            javaNs / 1000.0, nativeNs / 1000.0, (double) javaNs / nativeNs);

        // ── Carver ──
        int[] chunkHeights = new int[256];
        // populateChunkHeights convention [x*16+z]
        new com.stonebreak.world.generation.heightmap.HeightMapGenerator(new NoiseRouter(SEED))
            .populateChunkHeights(0, 0, chunkHeights);
        PerlinWormCarver javaCarver = new PerlinWormCarver(SEED,
            new com.stonebreak.world.generation.heightmap.HeightMapGenerator(new NoiseRouter(SEED)));
        long ctx = NoiseRouter.createCarverTerrainContext(SEED,
            com.stonebreak.world.generation.heightmap.HeightMapGenerator.splineXs(),
            com.stonebreak.world.generation.heightmap.HeightMapGenerator.splineYs(),
            com.stonebreak.world.generation.heightmap.HeightMapGenerator.splineSizes(),
            com.stonebreak.world.generation.heightmap.HeightMapGenerator.DETAIL_AMPLITUDE);
        long[] mask = new long[1024];
        long javaCarveNs = best(5, 20, () -> javaCarver.carveMaskForChunk(0, 0, chunkHeights));
        long nativeCarveNs = best(5, 20, () -> CendaKernels.carveWorms(ctx, 0, 0, chunkHeights, null, null, mask));
        System.out.printf("carver: java %.1f us/chunk, native %.1f us/chunk, speedup %.1fx%n",
            javaCarveNs / 1000.0, nativeCarveNs / 1000.0, (double) javaCarveNs / nativeCarveNs);
        CendaKernels.terrainDestroy(ctx);

        // ── Codec ──
        byte[] raw = new byte[65536 * 2];
        for (int i = 0; i < 65536; i++) {
            raw[i * 2] = (byte) blocks[i];
            raw[i * 2 + 1] = (byte) (blocks[i] >> 8);
        }
        byte[] zstd = CendaKernels.zstdCompress(raw, 3);
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(deflated)) { d.write(raw); }
        long zstdNs = best(20, 100, () -> CendaKernels.zstdCompress(raw, 3));
        long deflateNs = best(20, 100, () -> {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (DeflaterOutputStream d = new DeflaterOutputStream(b)) { d.write(raw); }
            catch (Exception e) { throw new RuntimeException(e); }
            return b;
        });
        System.out.printf("codec: deflate %.1f us (%d B), zstd %.1f us (%d B), speedup %.1fx%n",
            deflateNs / 1000.0, deflated.size(), zstdNs / 1000.0, zstd.length,
            (double) deflateNs / zstdNs);
    }

    private interface Op { Object run() throws Exception; }

    private static long best(int warmup, int reps, Op op) throws Exception {
        for (int i = 0; i < warmup; i++) op.run();
        long bestNs = Long.MAX_VALUE;
        for (int r = 0; r < 3; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < reps; i++) op.run();
            bestNs = Math.min(bestNs, (System.nanoTime() - t0) / reps);
        }
        return bestNs;
    }

    // Compact flat-array Java reference: same culling+lighting as the kernel.
    private static final int[][][] OFF = {
        {{0,1,1},{1,1,1},{1,1,0},{0,1,0}}, {{0,0,0},{1,0,0},{1,0,1},{0,0,1}},
        {{1,0,0},{0,0,0},{0,1,0},{1,1,0}}, {{0,0,1},{1,0,1},{1,1,1},{0,1,1}},
        {{1,0,1},{1,0,0},{1,1,0},{1,1,1}}, {{0,0,0},{0,0,1},{0,1,1},{0,1,0}}};
    private static final int[] FDX = {0,0,0,0,1,-1}, FDY = {1,-1,0,0,0,0}, FDZ = {0,0,-1,1,0,0};
    private static final int[][] AON = {{0,0,0},{0,-1,0},{0,0,-1},{0,0,0},{0,0,0},{-1,0,0}};
    private static final int[][] AT1 = {{-1,0,0},{-1,0,0},{-1,0,0},{-1,0,0},{0,-1,0},{0,-1,0}};
    private static final int[][] AT2 = {{0,0,-1},{0,0,-1},{0,-1,0},{0,-1,0},{0,0,-1},{0,0,-1}};

    private static Object javaReferenceMesh(short[] blocks, byte[] ct, short[] heights, int maxY) {
        ArrayList<float[]> quads = new ArrayList<>();
        for (int lx = 0; lx < 16; lx++) {
            for (int ly = 0; ly <= maxY; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    int id = blocks[(ly * 16 + lz) * 16 + lx];
                    int c = id >= 0 && id < ct.length ? ct[id] : 0;
                    if ((c & CendaKernels.CLASS_CUBE) == 0) continue;
                    boolean transparent = (c & CendaKernels.CLASS_TRANSPARENT) != 0;
                    for (int face = 0; face < 6; face++) {
                        int ax = lx + FDX[face], ay = ly + FDY[face], az = lz + FDZ[face];
                        int adj = (ay < 0 || ay >= 256 || ax < 0 || ax >= 16 || az < 0 || az >= 16)
                            ? 0 : blocks[(ay * 16 + az) * 16 + ax];
                        boolean render = adj == 0 || (transparent ? adj != id
                            : ((adj < ct.length ? ct[adj] : 0) & CendaKernels.CLASS_TRANSPARENT) != 0);
                        if (!render) continue;
                        float[] rec = new float[4];
                        for (int corner = 0; corner < 4; corner++) {
                            int ivx = lx + OFF[face][corner][0];
                            int ivy = ly + OFF[face][corner][1];
                            int ivz = lz + OFF[face][corner][2];
                            rec[corner] = light(blocks, ct, heights, ivx, ivy, ivz, face);
                        }
                        quads.add(rec);
                    }
                }
            }
        }
        return quads;
    }

    private static float light(short[] blocks, byte[] ct, short[] heights,
                               int ivx, int ivy, int ivz, int face) {
        int lit = 0, sampled = 0;
        for (int a = -1; a <= 0; a++) {
            for (int b = -1; b <= 0; b++) {
                int cx, cy, cz;
                switch (face) {
                    case 0 -> { cx = ivx + a; cy = ivy;     cz = ivz + b; }
                    case 1 -> { cx = ivx + a; cy = ivy - 1; cz = ivz + b; }
                    case 2 -> { cx = ivx + a; cy = ivy + b; cz = ivz - 1; }
                    case 3 -> { cx = ivx + a; cy = ivy + b; cz = ivz;     }
                    case 4 -> { cx = ivx;     cy = ivy + a; cz = ivz + b; }
                    default -> { cx = ivx - 1; cy = ivy + a; cz = ivz + b; }
                }
                int h = heights[(cz + 1) * 18 + (cx + 1)];
                if (h < 0) continue;
                sampled++;
                if (cy >= h) lit++;
            }
        }
        float sky = sampled == 0 ? 1.0f : (float) lit / sampled;
        int[] n = AON[face], t1 = AT1[face], t2 = AT2[face];
        boolean s1 = solid(blocks, ct, ivx + n[0] + t1[0], ivy + n[1] + t1[1], ivz + n[2] + t1[2]);
        boolean s2 = solid(blocks, ct, ivx + n[0] + t2[0], ivy + n[1] + t2[1], ivz + n[2] + t2[2]);
        boolean co = solid(blocks, ct, ivx + n[0] + t1[0] + t2[0], ivy + n[1] + t1[1] + t2[1],
            ivz + n[2] + t1[2] + t2[2]);
        int count = (s1 ? 1 : 0) + (s2 ? 1 : 0) + (co ? 1 : 0);
        if (s1 && s2) count = 3;
        return sky * (1.0f - 0.13f * count);
    }

    private static boolean solid(short[] blocks, byte[] ct, int x, int y, int z) {
        if (y < 0 || y >= 256 || x < 0 || x >= 16 || z < 0 || z >= 16) return false;
        int id = blocks[(y * 16 + z) * 16 + x];
        return ((id < ct.length ? ct[id] : 0) & CendaKernels.CLASS_OPAQUE_LIGHT) != 0;
    }
}
