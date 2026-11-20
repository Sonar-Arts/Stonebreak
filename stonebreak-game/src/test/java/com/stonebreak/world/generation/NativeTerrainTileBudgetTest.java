package com.stonebreak.world.generation;

import com.stonebreak.world.generation.diffusion.SurfaceChannel;
import com.stonebreak.world.generation.diffusion.SurfaceChannels;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tile cache sizing of {@link NativeTerrain#lodRing} (2026-11-20) and the allocation-free surface checks. */
class NativeTerrainTileBudgetTest {

    @Test
    void squaresCountEveryTileTheyCanTouch() {
        assertEquals(1, NativeTerrain.squareTiles(0, 256), "a point lies in one tile");
        assertEquals(9, NativeTerrain.squareTiles(256, 256));
        // Ultra (128 blocks per cell): lod 1 reaches 1024 blocks + one tile of margin -> 11 x 11.
        assertEquals(121, NativeTerrain.squareTiles(1024 + 256, 256));
    }

    @Test
    void ringsLeaveOutWhatLiesInside() {
        int square = NativeTerrain.squareTiles(4096, 512);
        int ring = NativeTerrain.ringTiles(2048, 4096, 512);
        assertTrue(ring < square && ring > 0, ring + " of " + square);
        assertEquals(NativeTerrain.squareTiles(4096, 512), NativeTerrain.ringTiles(0, 4096, 512));
    }

    @Test
    void theNearCacheBoundsCoverEveryShippedQuality() {
        for (var q : com.stonebreak.world.fastlod.FastLodQuality.values()) {
            long reach = Math.max(8 * 16, Math.min(80L * 16, 8L * q.blocksPerCell())) + 256;
            int tiles = NativeTerrain.squareTiles(reach, 256);
            assertTrue(tiles <= NativeTerrain.NEAR_TILES_MAX, q + " needs " + tiles + " tiles");
            assertTrue(tiles >= NativeTerrain.NEAR_TILES_MIN || q.blocksPerCell() < 128, q + ": " + tiles);
        }
    }

    @Test
    void surfaceEmptinessIsKnownWithoutScanning() {
        assertTrue(SurfaceChannels.NONE.isEmpty());
        byte[][] planes = new byte[SurfaceChannel.SLOTS][];
        planes[SurfaceChannel.SLOTS - 1] = new byte[4];
        assertFalse(new SurfaceChannels(planes).isEmpty());
    }
}
