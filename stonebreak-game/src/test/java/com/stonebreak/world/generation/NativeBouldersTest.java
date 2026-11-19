package com.stonebreak.world.generation;

import com.stonebreak.world.generation.biomes.BiomeType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Boulders through the Java binding (2026-11-19): {@link NativeCubeGenerator#FLAG_BOULDERS} with the shipped rock bank
 * resource ({@code worldgen/rocks/rock_bank_v1.sbrb}) registers and generates, boulders raise the column surface of a
 * tundra profile world, deterministically, and {@link WorldFeatureVersion} decides which worlds get them.
 */
class NativeBouldersTest {

    /** A rolling tundra profile (heights 78..86), no water, no carve codes. */
    private static boolean tundra(long cx, long cz, int[] height, int[] water, int[] riverFloor, int[] riverRoof,
                                  byte[] biome, byte[] riverFlow, short[] carve) {
        for (int lz = 0; lz < 32; lz++) {
            for (int lx = 0; lx < 32; lx++) {
                int c = lz * 32 + lx;
                double x = cx * 32 + lx, z = cz * 32 + lz;
                height[c] = 82 + (int) Math.round(4.0 * Math.sin(x / 19.0) * Math.cos(z / 23.0));
                water[c] = NativeCubeGenerator.NO_WATER;
                riverFloor[c] = 0;
                riverRoof[c] = 0;
                biome[c] = (byte) BiomeType.TUNDRA.ordinal();
                riverFlow[c] = -1;
                carve[c] = 0;
            }
        }
        return true;
    }

    private static NativeCubeGenerator generator(boolean boulders) {
        NativeCubeGenerator.Config c = new NativeCubeGenerator.Config();
        c.seed = 4242L;
        c.kind = NativeCubeGenerator.Kind.PROFILE;
        c.profiles = NativeBouldersTest::tundra;
        c.blockIds = NativeCubeGenerator.blockIds(n -> NativeCubeGenerator.Block.valueOf(n).ordinal());
        if (boulders) {
            c.flags |= NativeCubeGenerator.FLAG_BOULDERS;
            c.rockBank = WorldFeatureVersion.CURRENT.rockBank();
        }
        return NativeCubeGenerator.create(c);
    }

    private static long[] tops(NativeCubeGenerator g) {
        long[] all = new long[6 * 6 * 1024];
        long[] sky = new long[1024], top = new long[1024];
        int k = 0;
        for (int cx = -3; cx < 3; cx++) {
            for (int cz = -3; cz < 3; cz++) {
                assertTrue(g.columnSurface(cx, cz, sky, top));
                System.arraycopy(top, 0, all, k, 1024);
                k += 1024;
            }
        }
        return all;
    }

    @Test
    void bouldersRaiseTheSurfaceDeterministically() {
        assumeTrue(NativeCubeGenerator.isAvailable(), NativeCubeGenerator.unavailableReason());
        long[] plain, rocky, again;
        try (NativeCubeGenerator off = generator(false)) {
            plain = tops(off);
        }
        try (NativeCubeGenerator on = generator(true)) {
            rocky = tops(on);
        }
        try (NativeCubeGenerator on = generator(true)) {
            again = tops(on);
        }
        assertArrayEquals(rocky, again, "boulders are a pure function of the seed and the profiles");
        int raised = 0, lowered = 0;
        for (int i = 0; i < plain.length; i++) {
            if (rocky[i] > plain[i]) raised++;
            if (rocky[i] < plain[i]) lowered++;
        }
        assertEquals(0, lowered, "boulders only add blocks");
        assertTrue(raised > 50, "boulders / snow caps raise the surface of a tundra world: " + raised);
    }

    @Test
    void onlyNewWorldsGetBoulders() {
        assertFalse(WorldFeatureVersion.LEGACY.boulders());
        assertTrue(WorldFeatureVersion.CURRENT.boulders());
        assertEquals(WorldFeatureVersion.V0, WorldFeatureVersion.of(-1, WorldFeatureVersion.LEGACY));
        assertEquals(WorldFeatureVersion.V1, WorldFeatureVersion.of(1, WorldFeatureVersion.LEGACY));
        assertNotNull(NativeCubeGenerator.class.getResource(
            "/worldgen/rocks/rock_bank_v" + WorldFeatureVersion.CURRENT.rockBank() + ".sbrb"));
    }
}
