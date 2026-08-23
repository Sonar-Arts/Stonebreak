package com.stonebreak.rendering.models.entities;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves {@link SolidColorTexture#pixel} produces byte-identical upload data to
 * the six per-colour texture creators that used to be copy-pasted in
 * {@code EntityRenderer} (kept here verbatim as the oracle).
 */
class SolidColorTextureTest {

    /** The exact pixel-building statement from the old createXxxTexture methods. */
    private static ByteBuffer legacyPixel(int r, int g, int b, int a) {
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        pixel.put((byte) r).put((byte) g).put((byte) b).put((byte) a).flip();
        return pixel;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.duplicate().get(out);
        return out;
    }

    private static void assertSamePixel(String name, int r, int g, int b, int a) {
        ByteBuffer expected = legacyPixel(r, g, b, a);
        ByteBuffer actual = SolidColorTexture.pixel(r, g, b, a);
        assertEquals(0, actual.position(), name + ": buffer must be flipped");
        assertEquals(4, actual.remaining(), name + ": one RGBA8 pixel");
        assertArrayEquals(bytes(expected), bytes(actual), name);
    }

    @Test
    void fallbackWhite() {
        assertSamePixel("fallback", 255, 255, 255, 255);
    }

    @Test
    void arrowSaddleBrown() {
        assertSamePixel("arrow", 139, 90, 43, 255);
    }

    @Test
    void fireBoltOrange() {
        assertSamePixel("fireBolt", 255, 140, 0, 255);
    }

    @Test
    void nullSpikeViolet() {
        assertSamePixel("nullSpike", 178, 102, 255, 255);
    }

    @Test
    void leylineZoneTranslucentCyan() {
        assertSamePixel("leylineZone", 64, 210, 255, 110);
    }

    @Test
    void caltropMetallic() {
        assertSamePixel("caltrop", 150, 160, 175, 200);
    }

    @Test
    void valuesAbove127WrapToTheSameUnsignedByte() {
        // The legacy creators relied on (byte) narrowing for 128..255; the helper must too.
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0x8C, 0, (byte) 0xFF},
                bytes(SolidColorTexture.pixel(255, 140, 0, 255)));
    }
}
