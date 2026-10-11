package com.openmason.main.systems.menus.textureCreator.canvas;

import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core pixel storage of the texture editor: bounds handling, packing, alpha
 * compositing, resizing, byte conversion and the write constraints (shape mask,
 * selection, bypass) every tool relies on.
 */
class PixelCanvasTest {

    private static final int RED = PixelCanvas.packRGBA(255, 0, 0, 255);
    private static final int BLUE = PixelCanvas.packRGBA(0, 0, 255, 255);

    @Test
    void rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new PixelCanvas(0, 4));
        assertThrows(IllegalArgumentException.class, () -> new PixelCanvas(4, -1));
    }

    @Test
    void newCanvasIsFullyTransparent() {
        PixelCanvas canvas = new PixelCanvas(3, 2);
        for (int p : canvas.getPixels()) {
            assertEquals(0, p);
        }
    }

    @Test
    void setThenGetRoundTripsEveryPixelInRowMajorOrder() {
        PixelCanvas canvas = new PixelCanvas(5, 3);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 5; x++) {
                canvas.setPixel(x, y, PixelCanvas.packRGBA(x, y, x + y, 255));
            }
        }
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 5; x++) {
                assertEquals(PixelCanvas.packRGBA(x, y, x + y, 255), canvas.getPixel(x, y));
                assertEquals(canvas.getPixel(x, y), canvas.getPixels()[y * 5 + x]);
            }
        }
    }

    @Test
    void outOfBoundsWritesAreIgnoredAndReadsAreTransparent() {
        PixelCanvas canvas = new PixelCanvas(4, 4);
        canvas.fill(RED);
        long version = canvas.getModificationVersion();

        canvas.setPixel(-1, 0, BLUE);
        canvas.setPixel(4, 0, BLUE);
        canvas.setPixel(0, -1, BLUE);
        canvas.setPixel(0, 4, BLUE);

        assertEquals(version, canvas.getModificationVersion(), "ignored writes must not bump the version");
        for (int p : canvas.getPixels()) {
            assertEquals(RED, p);
        }
        assertEquals(0, canvas.getPixel(-1, -1));
        assertEquals(0, canvas.getPixel(4, 0));
        assertFalse(canvas.isValidCoordinate(4, 3));
        assertTrue(canvas.isValidCoordinate(3, 3));
    }

    @Test
    void packAndUnpackAreInversesForEveryChannelValue() {
        Random random = new Random(1234);
        for (int i = 0; i < 2000; i++) {
            int r = random.nextInt(256), g = random.nextInt(256), b = random.nextInt(256), a = random.nextInt(256);
            assertArrayEquals(new int[]{r, g, b, a}, PixelCanvas.unpackRGBA(PixelCanvas.packRGBA(r, g, b, a)));
        }
        // Layout is 0xAABBGGRR.
        assertEquals(0x80030201, PixelCanvas.packRGBA(1, 2, 3, 0x80));
    }

    // ---- alpha compositing ----

    @Test
    @Tag("regression")
    void opaqueSourceReplacesAndTransparentSourceLeavesDestination() {
        int dst = PixelCanvas.packRGBA(10, 20, 30, 200);
        assertEquals(RED, PixelCanvas.blendColors(RED, dst));
        assertEquals(dst, PixelCanvas.blendColors(PixelCanvas.packRGBA(99, 99, 99, 0), dst));
        assertEquals(0, PixelCanvas.blendColors(0, 0));
    }

    @Test
    void halfAlphaOverOpaqueMixesChannelsAndStaysOpaque() {
        int halfRed = PixelCanvas.packRGBA(255, 0, 0, 128);
        int[] out = PixelCanvas.unpackRGBA(PixelCanvas.blendColors(halfRed, BLUE));
        assertEquals(255, out[3]);
        assertEquals(128, out[0], 1);
        assertEquals(127, out[2], 1);
    }

    @Test
    @Tag("regression")
    void translucentColourOverTransparencyKeepsItsExactRgbaForEveryValue() {
        // Truncation used to drop a channel by one level (e.g. alpha 1 white -> 254).
        for (int a = 1; a <= 255; a++) {
            for (int c = 0; c <= 255; c++) {
                int src = PixelCanvas.packRGBA(c, 255 - c, c / 2, a);
                assertEquals(src, PixelCanvas.blendColors(src, 0), "a=" + a + " c=" + c);
            }
        }
    }

    @Test
    @Tag("regression")
    void paintingAColourOverItselfNeverDriftsIt() {
        // Repeated strokes of a translucent colour over the same opaque colour
        // must not darken it one level at a time.
        for (int a = 1; a <= 255; a++) {
            for (int c = 0; c <= 255; c++) {
                int dst = PixelCanvas.packRGBA(c, c, c, 255);
                int src = PixelCanvas.packRGBA(c, c, c, a);
                assertEquals(dst, PixelCanvas.blendColors(src, dst), "a=" + a + " c=" + c);
            }
        }
    }

    @Test
    void compositedAlphaIsNeverLowerThanEitherInput() {
        Random random = new Random(7);
        for (int i = 0; i < 5000; i++) {
            int src = random.nextInt();
            int dst = random.nextInt();
            int outA = PixelCanvas.unpackRGBA(PixelCanvas.blendColors(src, dst))[3];
            int srcA = PixelCanvas.unpackRGBA(src)[3];
            int dstA = PixelCanvas.unpackRGBA(dst)[3];
            assertTrue(outA >= Math.max(srcA, dstA) - 1, "src=" + srcA + " dst=" + dstA + " out=" + outA);
            assertTrue(outA <= 255);
        }
    }

    // ---- copy / resize ----

    @Test
    void copyIsDeepAndCopyFromRequiresMatchingSize() {
        PixelCanvas canvas = new PixelCanvas(4, 4);
        canvas.setPixel(1, 2, RED);
        PixelCanvas copy = canvas.copy();
        assertNotSame(canvas.getPixels(), copy.getPixels());
        assertArrayEquals(canvas.getPixels(), copy.getPixels());

        copy.setPixel(1, 2, BLUE);
        assertEquals(RED, canvas.getPixel(1, 2), "mutating the copy must not touch the original");

        assertThrows(IllegalArgumentException.class, () -> canvas.copyFrom(new PixelCanvas(4, 5)));
    }

    @Test
    void resizedToSameSizeReturnsTheSameInstance() {
        PixelCanvas canvas = new PixelCanvas(8, 8);
        assertSame(canvas, canvas.resized(8, 8));
    }

    @Test
    @Tag("regression")
    void integerUpscaleThenDownscaleIsLossless() {
        PixelCanvas canvas = randomCanvas(new Random(42), 5, 3);
        PixelCanvas up = canvas.resized(20, 9);
        // Every source pixel becomes a solid 4x3 block.
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 20; x++) {
                assertEquals(canvas.getPixel(x / 4, y / 3), up.getPixel(x, y));
            }
        }
        assertArrayEquals(canvas.getPixels(), up.resized(5, 3).getPixels());
    }

    @Test
    void downscaleSamplesTopLeftOfEachBlock() {
        PixelCanvas canvas = new PixelCanvas(4, 4);
        canvas.setPixel(0, 0, RED);
        canvas.setPixel(2, 2, BLUE);
        canvas.setPixel(3, 3, PixelCanvas.packRGBA(1, 1, 1, 255)); // never sampled
        PixelCanvas down = canvas.resized(2, 2);
        assertEquals(RED, down.getPixel(0, 0));
        assertEquals(BLUE, down.getPixel(1, 1));
        assertEquals(0, down.getPixel(1, 0));
    }

    // ---- byte conversion ----

    @Test
    @Tag("regression")
    void rgbaByteRoundTripIsExact() {
        PixelCanvas canvas = randomCanvas(new Random(99), 7, 5);
        byte[] bytes = canvas.getPixelsAsRGBABytes();
        assertEquals(7 * 5 * 4, bytes.length);
        int[] rgba = PixelCanvas.unpackRGBA(canvas.getPixel(0, 0));
        assertEquals(rgba[0], bytes[0] & 0xFF);
        assertEquals(rgba[3], bytes[3] & 0xFF);

        PixelCanvas back = PixelCanvas.fromRGBABytes(7, 5, bytes);
        assertArrayEquals(canvas.getPixels(), back.getPixels());
    }

    @Test
    void subRegionBytesAreClampedToTheCanvas() {
        PixelCanvas canvas = randomCanvas(new Random(5), 6, 6);
        byte[] region = canvas.getPixelsAsRGBABytes(4, 4, 10, 10); // clamps to 2x2
        assertEquals(2 * 2 * 4, region.length);
        int[] expected = PixelCanvas.unpackRGBA(canvas.getPixel(5, 5));
        assertEquals(expected[0], region[12] & 0xFF);
        assertEquals(expected[3], region[15] & 0xFF);

        assertEquals(0, canvas.getPixelsAsRGBABytes(10, 10, 3, 3).length);
        assertEquals(0, canvas.getPixelsAsRGBABytes(-5, 0, 3, 3).length);
    }

    @Test
    void fromRgbaBytesToleratesShortBuffers() {
        PixelCanvas canvas = PixelCanvas.fromRGBABytes(2, 2, new byte[]{1, 2, 3, 4});
        assertEquals(PixelCanvas.packRGBA(1, 2, 3, 4), canvas.getPixel(0, 0));
        assertEquals(0, canvas.getPixel(1, 1));
    }

    // ---- write constraints ----

    @Test
    void shapeMaskBlocksWritesOutsideItAndReportsCoverage() {
        PixelCanvas canvas = new PixelCanvas(4, 4);
        canvas.setShapeMask(new LeftHalfMask(4, 4));

        canvas.setPixel(1, 1, RED);
        canvas.setPixel(2, 1, RED);

        assertEquals(RED, canvas.getPixel(1, 1));
        assertEquals(0, canvas.getPixel(2, 1));
        assertTrue(canvas.isEditablePixel(1, 3));
        assertFalse(canvas.isEditablePixel(3, 3));
        assertFalse(canvas.isEditablePixel(-1, 0));
        assertEquals(1.0f, canvas.getMaskCoverage(0, 0));
        assertEquals(0.0f, canvas.getMaskCoverage(3, 0));
        assertEquals(0.0f, canvas.getMaskCoverage(9, 9));

        canvas.setShapeMask(null);
        assertEquals(1.0f, canvas.getMaskCoverage(3, 0));
        canvas.setPixel(2, 1, RED);
        assertEquals(RED, canvas.getPixel(2, 1));
    }

    @Test
    void selectionConstrainsWritesUnlessBypassed() {
        SelectionManager selections = new SelectionManager();
        PixelCanvas canvas = new PixelCanvas(4, 4);
        canvas.setSelectionManager(selections);
        selections.setActiveSelection(new RectangularSelection(0, 0, 1, 1));

        canvas.setPixel(0, 0, RED);
        canvas.setPixel(3, 3, RED);
        assertEquals(RED, canvas.getPixel(0, 0));
        assertEquals(0, canvas.getPixel(3, 3));

        canvas.setBypassSelectionConstraint(true);
        canvas.setPixel(3, 3, RED);
        canvas.setBypassSelectionConstraint(false);
        assertEquals(RED, canvas.getPixel(3, 3));

        canvas.clearSelection();
        assertFalse(canvas.hasActiveSelection());
        canvas.setPixel(2, 2, BLUE);
        assertEquals(BLUE, canvas.getPixel(2, 2));
    }

    @Test
    void bulkOperationsIgnoreTheSelection() {
        // fill/copyFrom are whole-canvas operations (used by load/restore paths).
        SelectionManager selections = new SelectionManager();
        PixelCanvas canvas = new PixelCanvas(3, 3);
        canvas.setSelectionManager(selections);
        selections.setActiveSelection(new RectangularSelection(0, 0, 0, 0));
        canvas.fill(RED);
        for (int p : canvas.getPixels()) {
            assertEquals(RED, p);
        }
    }

    @Test
    void changeListenersReceiveDirtyRegionsAndCanBeRemoved() {
        PixelCanvas canvas = new PixelCanvas(8, 6);
        List<int[]> events = new ArrayList<>();
        CanvasChangeListener listener = (c, x, y, w, h) -> events.add(new int[]{x, y, w, h});
        canvas.addChangeListener(listener);
        canvas.addChangeListener(listener); // duplicate registration ignored

        canvas.setPixel(3, 2, RED);
        canvas.setPixel(-1, 2, RED); // ignored, no event
        canvas.fill(BLUE);
        canvas.notifyFullCanvasDirty();

        assertEquals(3, events.size());
        assertArrayEquals(new int[]{3, 2, 1, 1}, events.get(0));
        assertArrayEquals(new int[]{0, 0, 8, 6}, events.get(1));
        assertArrayEquals(new int[]{0, 0, 8, 6}, events.get(2));

        canvas.removeChangeListener(listener);
        canvas.setPixel(0, 0, RED);
        assertEquals(3, events.size());
    }

    @Test
    void everyEffectiveWriteBumpsTheModificationVersion() {
        PixelCanvas canvas = new PixelCanvas(2, 2);
        long v0 = canvas.getModificationVersion();
        canvas.setPixel(0, 0, RED);
        canvas.clear();
        canvas.copyFrom(new PixelCanvas(2, 2));
        assertEquals(v0 + 3, canvas.getModificationVersion());
    }

    static PixelCanvas randomCanvas(Random random, int w, int h) {
        PixelCanvas canvas = new PixelCanvas(w, h);
        for (int i = 0; i < canvas.getPixels().length; i++) {
            canvas.getPixels()[i] = random.nextInt();
        }
        return canvas;
    }

    /** Plain fake: columns 0..w/2-1 editable. */
    private record LeftHalfMask(int width, int height) implements CanvasShapeMask {
        @Override
        public boolean isEditable(int x, int y) {
            return x >= 0 && x < width / 2 && y >= 0 && y < height;
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }
    }
}
