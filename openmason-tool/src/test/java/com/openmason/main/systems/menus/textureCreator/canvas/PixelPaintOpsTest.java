package com.openmason.main.systems.menus.textureCreator.canvas;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared painting primitives (rect, Bresenham line, ellipse, flood fill,
 * auto-outline) over a plain recording writer.
 */
class PixelPaintOpsTest {

    private static final int RED = PixelCanvas.packRGBA(255, 0, 0, 255);
    private static final int GREEN = PixelCanvas.packRGBA(0, 255, 0, 255);

    /** Writes straight into a canvas, counting only real changes. */
    private static PixelPaintOps.PixelWriter writerFor(PixelCanvas canvas) {
        return (x, y, color) -> {
            if (!canvas.isValidCoordinate(x, y) || canvas.getPixel(x, y) == color) return 0;
            canvas.setPixel(x, y, color);
            return 1;
        };
    }

    @Test
    void filledRectPaintsWidthTimesHeight() {
        PixelCanvas canvas = new PixelCanvas(10, 10);
        assertEquals(12, PixelPaintOps.rect(writerFor(canvas), 2, 3, 4, 3, RED, true));
        assertEquals(RED, canvas.getPixel(2, 3));
        assertEquals(RED, canvas.getPixel(5, 5));
        assertEquals(0, canvas.getPixel(6, 5));
        assertEquals(0, canvas.getPixel(2, 6));
    }

    @Test
    void outlineRectPaintsOnlyThePerimeter() {
        PixelCanvas canvas = new PixelCanvas(10, 10);
        assertEquals(2 * 5 + 2 * 3, PixelPaintOps.rect(writerFor(canvas), 1, 1, 5, 5, RED, false));
        assertEquals(0, canvas.getPixel(3, 3), "interior stays empty");
        assertEquals(RED, canvas.getPixel(5, 3));
        assertEquals(0, PixelPaintOps.rect(writerFor(canvas), 0, 0, 0, 5, RED, true));
        assertEquals(0, PixelPaintOps.rect(writerFor(canvas), 0, 0, 5, -1, RED, true));
    }

    @Test
    void lineHitsBothEndpointsAndIsReversible() {
        for (int[] seg : new int[][]{{0, 0, 7, 3}, {7, 0, 0, 5}, {3, 3, 3, 3}, {0, 6, 7, 6}, {2, 0, 2, 7}}) {
            Set<Long> forward = new HashSet<>();
            Set<Long> backward = new HashSet<>();
            int n = PixelPaintOps.line((x, y, c) -> forward.add(key(x, y)) ? 1 : 0, seg[0], seg[1], seg[2], seg[3], RED);
            PixelPaintOps.line((x, y, c) -> backward.add(key(x, y)) ? 1 : 0, seg[2], seg[3], seg[0], seg[1], RED);

            assertTrue(forward.contains(key(seg[0], seg[1])));
            assertTrue(forward.contains(key(seg[2], seg[3])));
            int expectedLength = Math.max(Math.abs(seg[2] - seg[0]), Math.abs(seg[3] - seg[1])) + 1;
            assertEquals(expectedLength, n, "Bresenham visits one pixel per major-axis step");
            assertEquals(forward.size(), backward.size());
        }
    }

    @Test
    void ellipseRingIsAStrictSubsetOfTheFilledEllipse() {
        Set<Long> filled = new HashSet<>();
        Set<Long> ring = new HashSet<>();
        PixelPaintOps.ellipse((x, y, c) -> filled.add(key(x, y)) ? 1 : 0, 0, 0, 11, 7, RED, true);
        PixelPaintOps.ellipse((x, y, c) -> ring.add(key(x, y)) ? 1 : 0, 0, 0, 11, 7, RED, false);

        assertTrue(filled.containsAll(ring));
        assertTrue(ring.size() < filled.size());
        assertTrue(filled.contains(key(5, 3)), "centre is filled");
        assertFalse(ring.contains(key(5, 3)), "centre is not on the ring");
        assertFalse(filled.contains(key(0, 0)), "bounding-box corner is outside the ellipse");
        // Symmetric about both axes of the box.
        for (long k : filled) {
            int x = (int) (k >> 32), y = (int) k;
            assertTrue(filled.contains(key(10 - x, y)));
            assertTrue(filled.contains(key(x, 6 - y)));
        }
        assertEquals(0, PixelPaintOps.ellipse((x, y, c) -> 1, 0, 0, 0, 4, RED, true));
    }

    @Test
    void floodFillReplacesOnlyTheConnectedRegion() {
        PixelCanvas canvas = new PixelCanvas(6, 6);
        // Vertical wall at x=3 splits the canvas.
        for (int y = 0; y < 6; y++) canvas.setPixel(3, y, GREEN);

        int changed = PixelPaintOps.flood(canvas, writerFor(canvas), canvas::isValidCoordinate, 0, 0, RED);

        assertEquals(3 * 6, changed);
        assertEquals(RED, canvas.getPixel(2, 5));
        assertEquals(GREEN, canvas.getPixel(3, 0));
        assertEquals(0, canvas.getPixel(4, 0), "the far side of the wall is untouched");
    }

    @Test
    void floodFillIsFourConnectedAndRespectsTheProbe() {
        PixelCanvas canvas = new PixelCanvas(4, 4);
        // Diagonal wall: 4-connected fill cannot leak through diagonal gaps.
        for (int i = 0; i < 4; i++) canvas.setPixel(i, 3 - i, GREEN);
        PixelPaintOps.flood(canvas, writerFor(canvas), canvas::isValidCoordinate, 0, 0, RED);
        assertEquals(0, canvas.getPixel(3, 3));

        PixelCanvas open = new PixelCanvas(4, 4);
        int changed = PixelPaintOps.flood(open, writerFor(open), (x, y) -> y < 2, 0, 0, RED);
        assertEquals(8, changed, "non-traversable rows block the fill");

        assertEquals(0, PixelPaintOps.flood(open, writerFor(open), open::isValidCoordinate, 0, 0, RED),
                "filling with the seed's own colour is a no-op");
        assertEquals(0, PixelPaintOps.flood(open, writerFor(open), open::isValidCoordinate, 9, 9, RED));
    }

    @Test
    void outerOutlineGrowsIntoTransparentFourNeighbours() {
        int w = 5, h = 5;
        int[] src = new int[w * h];
        src[2 * w + 2] = RED; // single opaque pixel in the centre
        PixelCanvas canvas = new PixelCanvas(w, h);
        System.arraycopy(src, 0, canvas.getPixels(), 0, src.length);

        int changed = PixelPaintOps.outline(writerFor(canvas), src, w, h, false, GREEN);

        assertEquals(4, changed);
        assertEquals(GREEN, canvas.getPixel(1, 2));
        assertEquals(GREEN, canvas.getPixel(2, 1));
        assertEquals(0, canvas.getPixel(1, 1), "diagonals are not part of a 4-neighbour outline");
        assertEquals(RED, canvas.getPixel(2, 2));
    }

    @Test
    void innerOutlineRecoloursTheSilhouetteEdgeOnly() {
        int w = 5, h = 5;
        int[] src = new int[w * h];
        for (int y = 1; y <= 3; y++) for (int x = 1; x <= 3; x++) src[y * w + x] = RED;
        PixelCanvas canvas = new PixelCanvas(w, h);
        System.arraycopy(src, 0, canvas.getPixels(), 0, src.length);

        int changed = PixelPaintOps.outline(writerFor(canvas), src, w, h, true, null);

        assertEquals(8, changed);
        assertEquals(RED, canvas.getPixel(2, 2), "the interior pixel is not on the edge");
        assertEquals(PixelPaintOps.outlineOf(RED), canvas.getPixel(1, 1));
    }

    @Test
    void outlineColourIsDarkerOpaqueAndNeverBlack() {
        for (int c : new int[]{RED, GREEN, PixelCanvas.packRGBA(0, 0, 0, 255), PixelCanvas.packRGBA(255, 255, 255, 40)}) {
            int[] in = PixelCanvas.unpackRGBA(c);
            int[] out = PixelCanvas.unpackRGBA(PixelPaintOps.outlineOf(c));
            assertEquals(255, out[3]);
            assertNotEquals(PixelCanvas.packRGBA(0, 0, 0, 255), PixelPaintOps.outlineOf(c));
            assertTrue(out[0] + out[1] + out[2] <= Math.max(in[0] + in[1] + in[2], 36));
        }
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }
}
