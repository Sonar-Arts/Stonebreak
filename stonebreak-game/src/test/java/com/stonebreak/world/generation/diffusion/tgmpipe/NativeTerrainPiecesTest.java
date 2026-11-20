package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.SurfaceChannel;
import com.stonebreak.world.generation.diffusion.SurfaceChannels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GPU-free pieces of the in-process terrain pipeline (native engine phase 3): the tile disk cache in the TGMPipe
 * service's file format (tile_cache.py SCHEMA 2), cache namespaces, surface-channel mapping, size parsing.
 */
class NativeTerrainPiecesTest {

    private static TileDiskCache.Entry entry(int rows, int cols) {
        short[][] planes = new short[7][rows * cols];
        for (int p = 0; p < 7; p++) {
            for (int k = 0; k < rows * cols; k++) {
                planes[p][k] = (short) (p * 1000 - k);
            }
        }
        Map<String, byte[]> surface = new LinkedHashMap<>();
        byte[] canopy = new byte[rows * cols];
        for (int k = 0; k < canopy.length; k++) {
            canopy[k] = (byte) (k % 256);
        }
        surface.put("canopy_cover", canopy);
        surface.put("not_a_channel_yet", new byte[rows * cols]);
        return new TileDiskCache.Entry(rows, cols, planes, surface);
    }

    @Test
    void cacheFileRoundTripsInTheServiceFormat(@TempDir Path dir) throws IOException {
        TileDiskCache cache = new TileDiskCache(dir, List.of("nsA", "nsB"), 1L << 30);
        TileDiskCache.Entry e = entry(5, 3);
        cache.put(1, -42L, 7, -3, 2, e, Map.of("canopy_cover", new float[]{1f / 254f, 0f}));
        Path file = dir.resolve("nsB").resolve("s-42_x7_z-3_l2.tile");
        assertTrue(Files.isRegularFile(file), "file named like tile_cache.py's _path");
        byte[] raw = Files.readAllBytes(file);
        // u32 rows, u32 cols (little endian), 7 int16 planes, u8 count, ...
        assertEquals(5, raw[0]);
        assertEquals(3, raw[4]);
        assertEquals(2, raw[8 + 2 * 7 * 15]);
        TileDiskCache.Entry back = cache.get(1, -42L, 7, -3, 2, 7);
        assertNotNull(back);
        assertEquals(5, back.rows());
        assertEquals(3, back.cols());
        for (int p = 0; p < 7; p++) {
            assertArrayEquals(e.planes()[p], back.planes()[p]);
        }
        assertEquals(List.of("canopy_cover", "not_a_channel_yet"), List.copyOf(back.surface().keySet()));
        assertArrayEquals(e.surface().get("canopy_cover"), back.surface().get("canopy_cover"));
        assertNull(cache.get(0, -42L, 7, -3, 2, 7), "worlds are separate namespaces");
    }

    @Test
    void tornFilesAreDroppedNotTrusted(@TempDir Path dir) throws IOException {
        TileDiskCache cache = new TileDiskCache(dir, List.of("ns"), 1L << 30);
        Path file = dir.resolve("ns").resolve("s1_x0_z0_l1.tile");
        Files.write(file, new byte[]{4, 0, 0, 0, 4, 0, 0, 0, 1, 2, 3});
        assertNull(cache.get(0, 1L, 0, 0, 1, 7));
        assertTrue(Files.notExists(file));
    }

    @Test
    void evictionKeepsTheRootUnderBudget(@TempDir Path dir) throws IOException {
        TileDiskCache.Entry e = entry(16, 16);
        long one = TileDiskCache.encode(e, Map.of()).length;
        TileDiskCache cache = new TileDiskCache(dir, List.of("ns"), one * 3);
        for (int i = 0; i < 6; i++) {
            cache.put(0, 1L, i, 0, 1, e, Map.of());
        }
        long total;
        try (var s = Files.list(dir.resolve("ns"))) {
            total = s.mapToLong(p -> p.toFile().length()).sum();
        }
        assertTrue(total <= one * 3, "root holds " + total + " bytes, budget " + one * 3);
        assertNotNull(cache.get(0, 1L, 5, 0, 1, 7), "the newest tile survives");
    }

    @Test
    void namespacesSeparateWorldsModelsAndExports() {
        String a = NativeTerrainService.namespace("{\"tile_size\":256}", "m:nt1", "k1");
        assertEquals(16, a.length());
        assertEquals(a, NativeTerrainService.namespace("{\"tile_size\":256}", "m:nt1", "k1"));
        assertNotEquals(a, NativeTerrainService.namespace("{\"tile_size\":128}", "m:nt1", "k1"));
        assertNotEquals(a, NativeTerrainService.namespace("{\"tile_size\":256}", "m2:nt1", "k1"));
        assertNotEquals(a, NativeTerrainService.namespace("{\"tile_size\":256}", "m:nt1", "k2"));
    }

    @Test
    void surfaceChannelsMapByNameAndDropNoData() {
        assertSame(SurfaceChannels.NONE, NativeTerrainService.surfaceOf(Map.of()));
        byte[] none = new byte[4];
        java.util.Arrays.fill(none, (byte) SurfaceChannel.NO_DATA);
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("canopy_cover", new byte[]{1, 2, 3, 4});
        m.put("scree", none);
        m.put("mystery", new byte[]{9, 9, 9, 9});
        SurfaceChannels s = NativeTerrainService.surfaceOf(m);
        assertNotNull(s);
    }

    @Test
    void sizesParseLikeTheService() {
        assertEquals(8L << 30, NativeTerrainLauncher.parseBytes("8G"));
        assertEquals(512L << 20, NativeTerrainLauncher.parseBytes("512m"));
        assertEquals(1536L, NativeTerrainLauncher.parseBytes("1.5K"));
        assertEquals(1234L, NativeTerrainLauncher.parseBytes("1234"));
    }

    @Test
    void defaultBackendIsTheLauncherConstant() {
        String old = System.clearProperty("stonebreak.terrain.backend");
        try {
            assertEquals(NativeTerrainLauncher.DEFAULT_MODE, NativeTerrainLauncher.mode());
        } finally {
            if (old != null) {
                System.setProperty("stonebreak.terrain.backend", old);
            }
        }
    }

    @Test
    void laneCountComesFromThePropertyWithinBounds() {
        String old = System.clearProperty("stonebreak.terrain.lanes");
        try {
            System.setProperty("stonebreak.terrain.lanes", "1");
            assertEquals(1, NativeTerrainService.configuredLanes());     // the sequential pipeline (A/B, fallback)
            System.setProperty("stonebreak.terrain.lanes", "3");
            assertEquals(3, NativeTerrainService.configuredLanes());
            System.setProperty("stonebreak.terrain.lanes", "0");
            assertEquals(1, NativeTerrainService.configuredLanes());
            System.setProperty("stonebreak.terrain.lanes", "99");
            assertEquals(8, NativeTerrainService.configuredLanes());
        } finally {
            if (old != null) {
                System.setProperty("stonebreak.terrain.lanes", old);
            } else {
                System.clearProperty("stonebreak.terrain.lanes");
            }
        }
    }

    @Test
    void autoLanesFollowTheGpuMemory() {
        assertEquals(1, NativeTerrainService.lanesFor(-1));          // no nvidia-smi
        assertEquals(1, NativeTerrainService.lanesFor(8192));
        assertEquals(1, NativeTerrainService.lanesFor(NativeTerrainService.AUTO_LANES_MIN_VRAM_MIB - 1));
        assertEquals(NativeTerrainService.DEFAULT_LANES,
                NativeTerrainService.lanesFor(NativeTerrainService.AUTO_LANES_MIN_VRAM_MIB));
        assertEquals(NativeTerrainService.DEFAULT_LANES, NativeTerrainService.lanesFor(98304));
    }
}
