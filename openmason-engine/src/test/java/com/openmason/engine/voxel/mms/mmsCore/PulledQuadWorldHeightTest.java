package com.openmason.engine.voxel.mms.mmsCore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the vertical range of the three pulled-quad records against a 1024-tall
 * world.
 *
 * <p>These formats were authored on a branch where {@code WORLD_HEIGHT} was 256
 * and budgeted 9 bits for Y accordingly — whole blocks for QUAD16/WATERQUAD16
 * (max y=511), HALF blocks for LODQUAD16 (max y=255.5). This branch runs
 * {@code WORLD_HEIGHT} 1024 with sea level at 320, so the LOD writer silently
 * dropped every quad it was asked to emit and the water/SBO writers threw above
 * y=511. Y is absolute world Y in all three (the mesh origin's Y is 0), so
 * nothing about the region origin rescues it.
 *
 * <p>Each assertion below fails on the 9-bit layouts.
 */
class PulledQuadWorldHeightTest {

    private static final int TOP = 1023;

    @Test
    void lodQuadCarriesTheFullColumnInHalfBlocks() {
        // Sea level (320) is the case that broke in-game: yHalf 640 > the old 511 cap.
        assertEquals(320f, MmsLodQuadCodec.y(MmsLodQuadCodec.word0(0, 0, 640, 0)));
        // ...and the very top of the world, at half-block precision.
        int w0 = MmsLodQuadCodec.word0(-8, 503, 2047, 5);
        assertEquals(1023.5f, MmsLodQuadCodec.y(w0));
        assertEquals(-8, MmsLodQuadCodec.x(w0));
        assertEquals(503, MmsLodQuadCodec.z(w0));
        assertEquals(5, MmsLodQuadCodec.face(w0));
    }

    @Test
    void lodQuadSpansAFoundationWallFromTheTopOfTheWorldToZero() {
        // FastLodMesher drops foundation walls from the cell top down to y=0.
        int w1 = MmsLodQuadCodec.word1(63, 2 * TOP, 4095, true, true, true);
        assertEquals(TOP, MmsLodQuadCodec.height(w1));
        assertEquals(31.5f, MmsLodQuadCodec.width(w1));
        assertEquals(4095, MmsLodQuadCodec.layer(w1));
        assertTrue(MmsLodQuadCodec.alpha(w1));
        assertTrue(MmsLodQuadCodec.smooth(w1));
        assertTrue(MmsLodQuadCodec.lit(w1));
    }

    @Test
    void lodQuadFlagsAreIndependent() {
        // smooth/light/alpha moved out of word0 into word1; make sure they didn't
        // collide with the widened h/layer fields on the way.
        int w1 = MmsLodQuadCodec.word1(1, 1, 0, false, false, false);
        assertFalse(MmsLodQuadCodec.alpha(w1));
        assertFalse(MmsLodQuadCodec.smooth(w1));
        assertFalse(MmsLodQuadCodec.lit(w1));
        assertEquals(0, MmsLodQuadCodec.layer(w1));

        assertTrue(MmsLodQuadCodec.alpha(MmsLodQuadCodec.word1(1, 1, 0, true, false, false)));
        assertTrue(MmsLodQuadCodec.smooth(MmsLodQuadCodec.word1(1, 1, 0, false, true, false)));
        assertTrue(MmsLodQuadCodec.lit(MmsLodQuadCodec.word1(1, 1, 0, false, false, true)));
        assertFalse(MmsLodQuadCodec.smooth(MmsLodQuadCodec.word1(1, 1, 0, true, false, true)));
    }

    @Test
    void chunkQuadCarriesTheFullColumn() {
        int w0 = MmsQuadCodec.word0(255, TOP, 255, 4);
        assertEquals(255, MmsQuadCodec.x(w0));
        assertEquals(TOP, MmsQuadCodec.y(w0));
        assertEquals(255, MmsQuadCodec.z(w0));
        assertEquals(4, MmsQuadCodec.face(w0));

        // w moved into word1 to free word0's top nibble; both extents decode there.
        int w1 = MmsQuadCodec.word1(16, 16, 7, true, true, 65535);
        assertEquals(16, MmsQuadCodec.width(w1));
        assertEquals(16, MmsQuadCodec.height(w1));
        assertEquals(7, MmsQuadCodec.orientation(w1));
        assertEquals(65535, MmsQuadCodec.layer(w1));
        assertTrue(MmsQuadCodec.alpha(w1));
        assertTrue(MmsQuadCodec.translucent(w1));
    }

    @Test
    void waterQuadCarriesTheFullColumn() {
        // A lake or river surface above y=511 used to throw out of word0.
        int w0 = MmsWaterQuadCodec.word0(255, TOP, 255, 5, true, true, true);
        assertEquals(255, MmsWaterQuadCodec.x(w0));
        assertEquals(TOP, MmsWaterQuadCodec.y(w0));
        assertEquals(255, MmsWaterQuadCodec.z(w0));
        assertEquals(5, MmsWaterQuadCodec.face(w0));
        assertTrue(MmsWaterQuadCodec.falling(w0));
        assertTrue(MmsWaterQuadCodec.source(w0));
        assertTrue(MmsWaterQuadCodec.sheet(w0));

        int plain = MmsWaterQuadCodec.word0(0, 320, 0, 0, false, false, false);
        assertEquals(320, MmsWaterQuadCodec.y(plain));
        assertFalse(MmsWaterQuadCodec.falling(plain));
        assertFalse(MmsWaterQuadCodec.source(plain));
        assertFalse(MmsWaterQuadCodec.sheet(plain));
    }
}
