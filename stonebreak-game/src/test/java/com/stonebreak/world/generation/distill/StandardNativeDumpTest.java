package com.stonebreak.world.generation.distill;

import com.stonebreak.world.generation.NativeCubeGenerator;
import com.stonebreak.world.generation.NativeTerrain;
import com.stonebreak.world.generation.StandardShapeVersion;
import com.stonebreak.world.generation.TerrainGeneratorType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in distillation dump of the REAL native Standard generator (agent SD, 2026-11-19): raw column heights of every
 * tile in a spec file ({@code seed tileX tileZ lod} per line), as {@link StandardTerrainDump} writes them (its .bin
 * format: int32 n, int16 height[n*n], int16 biome[n*n] = -1 (not dumped), float32 planes [6][n*n] = 0), so
 * {@code terrain_slm.eval.distill_stats} measures the shipped generator, not the fit's model of it.
 *
 * <pre>-Dstonebreak.distill.dump=&lt;spec.txt&gt;|&lt;outDir&gt;|&lt;standard shape 1|2&gt;</pre>
 */
class StandardNativeDumpTest {

    private static final int N = 256;

    @Test
    void dump() throws IOException {
        String arg = System.getProperty("stonebreak.distill.dump");
        assumeTrue(arg != null && !arg.isBlank(), "opt-in: -Dstonebreak.distill.dump=<spec>|<outDir>|<shape>");
        assumeTrue(NativeTerrain.isAvailable(), NativeCubeGenerator.unavailableReason());
        String[] a = arg.split("\\|");
        Path out = Path.of(a[1]);
        StandardShapeVersion shape = StandardShapeVersion.of(Integer.parseInt(a[2]));
        Files.createDirectories(out);
        Files.writeString(out.resolve("curve.json"),
            "{\"scale\": \"1:4\", \"version\": " + shape.heightCurve().number() + "}\n");
        Files.writeString(out.resolve("biomes.txt"), "0 PLAINS\n");
        Map<Long, NativeTerrain> terrains = new HashMap<>();
        List<String> lines = Files.readAllLines(Path.of(a[0]));
        long t0 = System.nanoTime();
        int done = 0;
        try {
            for (String line : lines) {
                String[] f = line.trim().split("\\s+");
                if (f.length != 4) {
                    continue;
                }
                long seed = Long.parseLong(f[0]);
                int tx = Integer.parseInt(f[1]), tz = Integer.parseInt(f[2]), lod = Integer.parseInt(f[3]);
                Path file = out.resolve("s" + seed + "_x" + tx + "_z" + tz + "_l" + lod + ".bin");
                if (Files.exists(file)) {
                    continue;
                }
                NativeTerrain t = terrains.computeIfAbsent(seed, s -> NativeTerrain.create(s,
                    TerrainGeneratorType.STANDARD, NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR, null, null,
                    null, shape, null));
                int[] heights = new int[N * N];
                int[] water = new int[N * N];
                t.sampleRawColumns(tx * N * lod, tz * N * lod, N, lod, heights, water, null);
                ByteBuffer b = ByteBuffer.allocate(4 + 4 * N * N + 24 * N * N).order(ByteOrder.LITTLE_ENDIAN);
                b.putInt(N);
                for (int h : heights) {
                    b.putShort((short) h);
                }
                for (int i = 0; i < N * N; i++) {
                    b.putShort((short) -1);
                }
                Files.write(file, b.array());
                done++;
                if (terrains.size() > 2) {   // seeds come in runs: keep the generators' caches bounded
                    for (var e : terrains.entrySet()) {
                        if (e.getKey() != seed) {
                            e.getValue().close();
                        }
                    }
                    terrains.keySet().removeIf(k -> k != seed);
                }
            }
        } finally {
            terrains.values().forEach(NativeTerrain::close);
        }
        System.out.printf("[distill-dump] %d tiles (shape %s) in %.1f s -> %s%n", done, shape,
            (System.nanoTime() - t0) / 1e9, out);
    }
}
