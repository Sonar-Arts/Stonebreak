package com.stonebreak.world.generation.distill;

import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.heightmap.DistilledShape;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.noise.MultiNoiseSample;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.generation.noise.TerrainNoise;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Distillation dump of the Standard generator: for every tile listed in a spec file
 * ({@code seed tileX tileZ lod} per line), writes the same 256 x 256 grid DaedalusTGM-Exp's
 * {@code terrain_slm.eval.distill_sample} builds (row = x, col = z, {@code lod} blocks per
 * sample, point-sampled) so {@code terrain_slm.eval.distill_stats} can compare the two.
 *
 * <p>File {@code s<seed>_x<x>_z<z>_l<lod>.bin}, little endian: int32 n, then int16 height[n*n],
 * int16 biome ordinal[n*n], float32 land, mountain, hilly, rough, tRaw, mRaw [n*n] each — the shape
 * fields and raw climate let biome thresholds be calibrated offline without re-sampling noise. {@code biomes.txt} lists the ordinals.
 *
 * <pre>java -cp ... com.stonebreak.world.generation.distill.StandardTerrainDump spec.txt outDir</pre>
 */
public final class StandardTerrainDump {

    private static final int N = 256;

    private StandardTerrainDump() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: StandardTerrainDump <spec.txt> <outDir>");
        }
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        StringBuilder names = new StringBuilder();
        for (BiomeType b : BiomeType.values()) {
            names.append(b.ordinal()).append(' ').append(b.name()).append('\n');
        }
        Files.writeString(out.resolve("biomes.txt"), names);
        System.out.println("noise backend " + TerrainNoise.backend());

        List<String> lines = Files.readAllLines(Path.of(args[0]));
        long t0 = System.nanoTime();
        int done = 0;
        for (String line : lines) {
            String[] f = line.trim().split("\\s+");
            if (f.length != 4) {
                continue;
            }
            long seed = Long.parseLong(f[0]);
            int tx = Integer.parseInt(f[1]);
            int tz = Integer.parseInt(f[2]);
            int lod = Integer.parseInt(f[3]);
            Path file = out.resolve("s" + seed + "_x" + tx + "_z" + tz + "_l" + lod + ".bin");
            if (Files.exists(file)) {
                continue;
            }
            Files.write(file, dumpTile(seed, tx * N * lod, tz * N * lod, lod));
            done++;
        }
        System.out.printf("%d tiles in %.1f s%n", done, (System.nanoTime() - t0) / 1e9);
    }

    static byte[] dumpTile(long seed, int x0, int z0, int stride) {
        NoiseRouter router = new NoiseRouter(seed);
        HeightMapGenerator heights = new HeightMapGenerator(router);
        BiomeManager biomes = new BiomeManager(router, heights);
        int cells = N * N;
        DistilledShape.Column[] cols = new DistilledShape.Column[cells];
        float[] tRaw = new float[cells];
        float[] mRaw = new float[cells];
        heights.fillColumns(x0, z0, N, N, stride, cols);
        router.fillClimateChannels(x0, z0, N, N, stride, tRaw, mRaw);

        // int32 n, int16 height, int16 biome, float32 land, mountain, hilly, rough, tRaw, mRaw
        ByteBuffer buf = ByteBuffer.allocate(4 + cells * (2 + 2 + 6 * 4)).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(N);
        short[] biome = new short[cells];
        for (int i = 0; i < cells; i++) {
            MultiNoiseSample s = NoiseRouter.sampleOf(cols[i],
                NoiseRouter.temperatureFromRaw(tRaw[i], cols[i].shapedHeight), NoiseRouter.moistureFromRaw(mRaw[i]));
            biome[i] = (short) biomes.selectBiome(s, cols[i].height).ordinal();
            buf.putShort((short) cols[i].height);
        }
        for (short b : biome) {
            buf.putShort(b);
        }
        for (int i = 0; i < cells; i++) buf.putFloat((float) cols[i].land);
        for (int i = 0; i < cells; i++) buf.putFloat((float) cols[i].mountain);
        for (int i = 0; i < cells; i++) buf.putFloat((float) cols[i].hilly);
        for (int i = 0; i < cells; i++) buf.putFloat((float) cols[i].rough);
        for (float v : tRaw) buf.putFloat(v);
        for (float v : mRaw) buf.putFloat(v);
        return buf.array();
    }
}
