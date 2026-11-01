package com.stonebreak.world.generation;

import com.openmason.engine.cenda.CendaKernels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The game's own worldgen library (#386) loads through its own handshake, separate from the
 * engine's. CI sets {@code -Dstonebreak.worldgen.requireNative=true} so a platform that cannot
 * build or load it fails here instead of quietly skipping every parity suite.
 */
class WorldgenNativesTest {

    @Test
    void loadsWhenRequired() {
        if (Boolean.getBoolean("stonebreak.worldgen.requireNative")) {
            assertTrue(WorldgenNatives.isAvailable(), "Stonebreak worldgen natives are required but did not load from "
                + WorldgenNatives.libraryPath().map(Object::toString).orElse("<not found>")
                + " — build stonebreak-game/native (cmake --preset release && cmake --build --preset release)");
        }
        assumeTrue(WorldgenNatives.isAvailable(), "Stonebreak worldgen natives not built");
    }

    @Test
    void isItsOwnLibraryNotTheEngines() {
        assumeTrue(WorldgenNatives.isAvailable(), "Stonebreak worldgen natives not built");
        String name = WorldgenNatives.libraryPath().orElseThrow().getFileName().toString();
        assertTrue(name.contains("stonebreak_worldgen"), name);
        CendaKernels.libraryPath().ifPresent(engine ->
            assertNotEquals(engine, WorldgenNatives.libraryPath().orElseThrow()));
    }

    @Test
    void createsAndCarvesWithoutTheEngineLibraryHoldingTheKernels() {
        assumeTrue(WorldgenNatives.isAvailable(), "Stonebreak worldgen natives not built");
        long ctx = com.stonebreak.world.generation.noise.NoiseRouter.createCarverTerrainContext(424242L,
            com.stonebreak.world.generation.heightmap.HeightMapGenerator.splineXs(),
            com.stonebreak.world.generation.heightmap.HeightMapGenerator.splineYs(),
            com.stonebreak.world.generation.heightmap.HeightMapGenerator.splineSizes(),
            com.stonebreak.world.generation.heightmap.HeightMapGenerator.DETAIL_AMPLITUDE);
        assertNotEquals(0L, ctx, "terrain context");
        try {
            int[] heights = new int[256];
            java.util.Arrays.fill(heights, 200);
            long[] a = new long[1024];
            long[] b = new long[1024];
            long first = WorldgenNatives.carveWorms(ctx, 2, -2, heights, null, null, a);
            long second = WorldgenNatives.carveWorms(ctx, 2, -2, heights, null, null, b);
            assertTrue(first >= 0, "carve succeeds");
            assertEquals(first, second);
            assertTrue(java.util.Arrays.equals(a, b), "same input reproduces the mask");
        } finally {
            WorldgenNatives.terrainDestroy(ctx);
        }
        WorldgenNatives.terrainDestroy(0L); // safe
    }
}
