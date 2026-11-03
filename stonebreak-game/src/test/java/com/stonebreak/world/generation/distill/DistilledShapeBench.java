package com.stonebreak.world.generation.distill;

import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.noise.NoiseChannel2D;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.generation.noise.TerrainNoise;

/**
 * A/B timing of the distilled height path against the cost of the old one (its four channel fills:
 * continentalness 8 oct, peaks/valleys 5, erosion 5, detail 3 — the spline math is negligible), plus
 * full Standard chunk generation, in one JVM. Not a test; run by hand:
 *
 * <pre>java -cp ... com.stonebreak.world.generation.distill.DistilledShapeBench</pre>
 */
public final class DistilledShapeBench {

    private DistilledShapeBench() {
    }

    public static void main(String[] args) {
        long seed = 20261101L;
        int chunks = 4000;
        HeightMapGenerator hm = new HeightMapGenerator(new NoiseRouter(seed));
        NoiseChannel2D[] old = {
            TerrainNoise.channel2D(seed + 2, 8, 0.45, 2.0, 1f / 800f, 0, 0, 0, 0),
            TerrainNoise.channel2D(seed + 8, 5, 0.45, 2.0, 1f / 260f, 0, 0, 80_860, 20_020),
            TerrainNoise.channel2D(seed + 5, 5, 0.40, 2.0, 1f / 900f, 0, 0, 20_700, -15_300),
            TerrainNoise.channel2D(seed + 3, 3, 0.50, 2.0, 1f / 45f, 0, 0, 0, 0),
        };
        int[] out = new int[256];
        float[] buf = new float[256];
        System.out.println("backend " + TerrainNoise.backend());
        for (int round = 0; round < 3; round++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < chunks; i++) {
                hm.populateChunkHeights(i % 64 + round * 100, i / 64, out);
            }
            long t1 = System.nanoTime();
            for (int i = 0; i < chunks; i++) {
                for (NoiseChannel2D c : old) {
                    c.fill(buf, (i % 64 + round * 100) * 16, (i / 64) * 16, 16, 16, 1);
                }
            }
            long t2 = System.nanoTime();
            for (int i = 0; i < 2000; i++) {
                hm.generateHeight(i * 37 + round * 100_000, i * 53);
            }
            long t3 = System.nanoTime();
            System.out.printf("round %d: chunk heights new %.1f us/chunk, old fills %.1f us/chunk; per-point height %.2f us%n",
                round, (t1 - t0) / 1e3 / chunks, (t2 - t1) / 1e3 / chunks, (t3 - t2) / 1e3 / 2000);
        }
        TerrainGenerationSystem sys = new TerrainGenerationSystem(seed);
        for (int round = 0; round < 2; round++) {
            long t0 = System.nanoTime();
            int n = 200;
            for (int i = 0; i < n; i++) {
                sys.generateTerrainOnly(i % 20 + round * 40, i / 20);
            }
            System.out.printf("full terrain-only chunk (round %d): %.2f ms/chunk%n", round,
                (System.nanoTime() - t0) / 1e6 / n);
        }
    }
}
