package com.stonebreak.world.generation.diffusion.tgmpipe;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tiles are a pure function of (world, seed, tile, lod): the pipeline's caches (their sizes, the host-memory budget,
 * evictions, an idle release) may only change speed and memory, never a byte of a tile. Builds a fixed tile set straight
 * through the native engine (no disk tile cache), hashes every plane and surface channel, and writes / compares the
 * hashes. Opt-in ({@code -Dstonebreak.terrain.parity.out=<file>} writes, {@code .expect=<file>} compares); run it with
 * {@code -Dstonebreak.terrain.hostCacheMb=8} to force constant eviction, and with
 * {@code -Dstonebreak.terrain.parity.releaseBetween=true} to drop every cache between tiles.
 */
@RequiresDaedalus
class TerrainCacheParityLiveTest {

    /** (world, seed, tileX, tileZ, lod): neighbours share windows, the repeat at the end hits (or rebuilds) them. */
    private static final long[][] TILES = {
            {0, 12345, 0, 0, 8}, {0, 12345, 1, 0, 8}, {0, 12345, 0, 0, 1}, {0, 12345, 0, 1, 1}, {0, 12345, 1, 1, 1},
            {0, 987654321, 3, -2, 4}, {0, 12345, 0, 0, 1}, {0, 12345, 0, 0, 8}};

    @Test
    void tilesDoNotDependOnTheCaches() throws Exception {
        String out = System.getProperty("stonebreak.terrain.parity.out");
        String expect = System.getProperty("stonebreak.terrain.parity.expect");
        assumeTrue(out != null || expect != null, "opt-in: -Dstonebreak.terrain.parity.out / .expect");
        boolean releaseBetween = Boolean.getBoolean("stonebreak.terrain.parity.releaseBetween");
        NativeTerrainLauncher.Opened opened = NativeTerrainLauncher.open();
        List<String> lines = new ArrayList<>();
        try (NativeTerrain terrain = opened.terrain()) {
            terrain.setLanes(1);
            terrain.warmup();
            List<String> worlds = NativeTerrainLauncher.worlds();
            for (int i = 0; i < worlds.size(); i++) {
                terrain.setWorld(i, worlds.get(i));
            }
            // -Dstonebreak.terrain.parity.soak=N: N tiles of fresh seeds first (each fills the window caches anew),
            // then the parity set (which must still match: order never changes a tile).
            int soak = Integer.getInteger("stonebreak.terrain.parity.soak", 0);
            long s0 = System.nanoTime();
            for (int i = 0; i < soak; i++) {
                terrain.tile(0, 0, 1000 + i, i % 3, i % 2, i % 2 == 0 ? 1 : 8);   // a fresh seed: fresh windows every tile
                if ((i + 1) % 10 == 0 || i + 1 == soak) {
                    System.out.printf("soak %d/%d %.1f s  %s  %s%n", i + 1, soak, (System.nanoTime() - s0) / 1e9,
                            memory(terrain.stats()), rss());
                }
            }
            for (long[] t : TILES) {
                long t0 = System.nanoTime();
                NativeTerrain.Tile tile = terrain.tile(0, (int) t[0], t[1], (int) t[2], (int) t[3], (int) t[4]);
                String line = String.format("w%d seed %d tile (%d,%d) lod %d %s", t[0], t[1], t[2], t[3], t[4], hash(tile));
                lines.add(line);
                System.out.printf("%s  %.0f ms%n", line, (System.nanoTime() - t0) / 1e6);
                if (releaseBetween) {
                    terrain.release();
                }
            }
            System.out.println("native memory (MiB): " + memory(terrain.stats()));
            System.out.println("process RSS: " + rss());
        }
        if (out != null) {
            Files.write(Path.of(out), lines);
        }
        if (expect != null) {
            assertEquals(Files.readAllLines(Path.of(expect)), lines, "tiles changed");
        }
    }

    /** The host-memory numbers of the native stats, in MiB. */
    private static String memory(String stats) {
        StringBuilder out = new StringBuilder();
        for (String k : List.of("host_cache_bytes", "host_evictions", "pool_host_live", "pool_host_cached", "pool_live",
                "pool_cached")) {
            var m = java.util.regex.Pattern.compile("\"" + k + "\":(\\d+)").matcher(stats);
            if (m.find()) {
                long v = Long.parseLong(m.group(1));
                out.append(k).append('=').append(k.endsWith("evictions") ? v : v >> 20).append(' ');
            }
        }
        return out.toString().strip();
    }

    private static String rss() {
        try {
            return Files.readAllLines(Path.of("/proc/self/status")).stream().filter(l -> l.startsWith("VmRSS"))
                    .findFirst().orElse("?").replaceAll("\\s+", " ");
        } catch (Exception e) {
            return "?";
        }
    }

    private static String hash(NativeTerrain.Tile t) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(ByteBuffer.allocate(8).putInt(t.rows()).putInt(t.cols()).array());
        for (short[] plane : t.planes()) {
            ByteBuffer b = ByteBuffer.allocate(plane.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            b.asShortBuffer().put(plane);
            md.update(b.array());
        }
        for (Map.Entry<String, byte[]> e : new TreeMap<>(t.surface()).entrySet()) {
            md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
            md.update(e.getValue());
        }
        return HexFormat.of().formatHex(md.digest()).substring(0, 24);
    }
}
