package com.stonebreak.world.generation;

import com.stonebreak.world.generation.diffusion.SurfaceChannels;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import com.stonebreak.world.generation.diffusion.biomes.BiomeManager;
import com.stonebreak.world.generation.diffusion.tgmpipe.TGMPipe;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FX2 (2026-11-19): the coarse-LOD column profiles - every block column takes the lod-L tile sample covering it, by
 * the same water / river / biome / surface rules as the full-detail profiles - and the distance-ordered LOD priority.
 */
class CoarseProfilesTest {

    private static final int N = 256;   // samples per tile axis (TerrainScale.TILE_SIZE_BLOCKS)

    /** One lod tile in sample units with recognisable planes. */
    private static TerrainTile tile(int tx, int tz) {
        int i1 = tx * N, j1 = tz * N;
        short[] h = new short[N * N], b = new short[N * N], w = new short[N * N], f = new short[N * N],
            r = new short[N * N], fl = new short[N * N], c = new short[N * N];
        byte[] canopy = new byte[N * N];
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                int k = i * N + j, si = i1 + i, sj = j1 + j;
                h[k] = (short) (40 + Math.floorMod(si * 7 + sj * 3, 50));
                b[k] = (short) Math.floorMod(si + sj, 3);
                w[k] = (si + sj) % 5 == 0 ? (short) 64 : TerrainTile.NO_WATER;
                f[k] = TerrainTile.NO_TUNNEL;
                r[k] = TerrainTile.NO_TUNNEL;
                fl[k] = (short) ((si & 1) == 0 ? Math.floorMod(sj, 8) : -1);
                c[k] = (short) Math.floorMod(si * 31 + sj, 1000);
                canopy[k] = (byte) Math.floorMod(si * 13 + sj * 5, 250);
            }
        }
        byte[][] planes = new byte[16][];
        planes[0] = canopy;
        return new TerrainTile(tx, tz, i1, j1, i1 + N, j1 + N, N, N, h, b, w, f, r, fl, c,
            new SurfaceChannels(planes));
    }

    private static final class Source implements TerrainTileSource, AutoCloseable {
        final AtomicInteger fetches = new AtomicInteger();
        boolean closed;

        @Override
        public TerrainTile getTile(int x, int z) {
            fetches.incrementAndGet();
            return tile(Math.floorDiv(x, N), Math.floorDiv(z, N));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void everyBlockColumnTakesTheSampleCoveringIt() {
        for (int lod : new int[] {2, 4, 8, 16}) {
            Source src = new Source();
            NativeTerrain.CoarseProfiles p = new NativeTerrain.CoarseProfiles(lod, src);
            long cx = -3, cz = 70;   // negative x and a cube past the first coarse tile on z
            int[] height = new int[1024], water = new int[1024], floor = new int[1024], roof = new int[1024];
            byte[] biome = new byte[1024], flow = new byte[1024];
            short[] carve = new short[1024];
            byte[] surface = new byte[16 * 1024];
            java.util.Arrays.fill(surface, (byte) 0xFF);
            assertTrue(p.fill(cx, cz, height, water, floor, roof, biome, flow, carve, surface));
            assertEquals(1, src.fetches.get(), "a column cube never straddles two coarse tiles");
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    int si = Math.floorDiv((int) cx * 32 + x, lod), sj = Math.floorDiv((int) cz * 32 + z, lod);
                    TerrainTile t = tile(Math.floorDiv(si, N), Math.floorDiv(sj, N));
                    int dst = z * 32 + x;
                    int h = t.heightAt(si, sj);
                    assertEquals(h, height[dst]);
                    short wl = t.waterLevelAt(si, sj);
                    assertEquals(wl == TerrainTile.NO_WATER ? NativeCubeGenerator.NO_WATER : wl, water[dst]);
                    assertEquals(t.riverFloorAt(si, sj), floor[dst]);
                    assertEquals(t.riverRoofAt(si, sj), roof[dst]);
                    short fv = t.riverFlowAt(si, sj);
                    assertEquals((byte) (fv < 0 || fv > 7 ? -1 : fv), flow[dst]);
                    assertEquals(t.carveAt(si, sj), carve[dst]);
                    assertEquals((byte) BiomeManager.biomeAt(t, si, sj, h).ordinal(), biome[dst]);
                    int k = (si - t.worldI1()) * N + (sj - t.worldJ1());
                    assertEquals(t.surface().plane(0)[k], surface[dst]);
                    assertEquals((byte) 0xFF, surface[1024 + dst], "absent channels stay no-data");
                }
            }
            p.close();
            assertTrue(src.closed);
        }
    }

    @Test
    void rejectsLodsThatDoNotTileAColumnCube() {
        assertThrows(IllegalArgumentException.class, () -> new NativeTerrain.CoarseProfiles(1, new Source()));
        assertThrows(IllegalArgumentException.class, () -> new NativeTerrain.CoarseProfiles(3, new Source()));
    }

    @Test
    void overviewLodGivesWholePlannerCellsPerSample() {
        assertEquals(4, NativeTerrain.overviewLod(NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR));
        assertEquals(8, NativeTerrain.overviewLod(NativeCubeGenerator.TerrainScalePreset.ONE_TO_TWO));
        assertEquals(16, NativeTerrain.overviewLod(NativeCubeGenerator.TerrainScalePreset.ONE_TO_ONE));
    }

    @Test
    void lodPriorityIsNearestFirstBetweenWorldAndRelief() {
        assertEquals(TGMPipe.PRIORITY_LOD, TGMPipe.lodPriority(0));
        assertEquals(TGMPipe.PRIORITY_LOD, TGMPipe.lodPriority(-5));
        assertTrue(TGMPipe.lodPriority(100) < TGMPipe.lodPriority(1000));
        assertTrue(TGMPipe.lodPriority(1000) < TGMPipe.lodPriority(3000));
        assertEquals(TGMPipe.LOD_PRIORITY_MAX, TGMPipe.lodPriority(Long.MAX_VALUE / 2));
        assertTrue(TGMPipe.PRIORITY_WORLD < TGMPipe.PRIORITY_LOD);
        assertTrue(TGMPipe.LOD_PRIORITY_MAX < TGMPipe.PRIORITY_RELIEF);
        assertTrue(TGMPipe.PRIORITY_RELIEF < TGMPipe.PRIORITY_PREVIEW);
        assertTrue(TGMPipe.PRIORITY_PREVIEW <= 255, "one unsigned byte on the wire");
    }
}
