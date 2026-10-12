package com.openmason.main.systems.menus.textureCreator.selection;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selection shapes: inclusive rectangles and per-pixel masks (the result of
 * every brush / wand / union / subtract operation).
 */
class SelectionRegionsTest {

    // ---- rectangle ----

    @Test
    void rectangleIsInclusiveAndNormalisesItsCorners() {
        RectangularSelection a = new RectangularSelection(5, 4, 2, 1);
        RectangularSelection b = new RectangularSelection(2, 1, 5, 4);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(new Rectangle(2, 1, 4, 4), a.getBounds());
        assertEquals(4, a.getWidth());
        assertTrue(a.contains(2, 1));
        assertTrue(a.contains(5, 4));
        assertFalse(a.contains(6, 4));
        assertFalse(a.contains(2, 0));
        assertFalse(a.isEmpty());
        assertEquals(SelectionRegion.SelectionType.RECTANGLE, a.getType());
    }

    @Test
    void singlePixelRectangleSelectsExactlyOnePixel() {
        RectangularSelection one = new RectangularSelection(3, 3, 3, 3);
        assertEquals(1, count(one, 10, 10));
        assertEquals(new Rectangle(3, 3, 1, 1), one.getBounds());
    }

    @Test
    void rectangleFromAwtRectangleRoundTripsItsBounds() {
        Rectangle r = new Rectangle(-2, 7, 5, 3);
        assertEquals(r, new RectangularSelection(r).getBounds());
    }

    @Test
    void translatedRectangleMovesEveryPixel() {
        RectangularSelection base = new RectangularSelection(1, 1, 3, 2);
        SelectionRegion moved = base.translate(4, -1);
        for (int y = -2; y < 8; y++) {
            for (int x = -2; x < 10; x++) {
                assertEquals(base.contains(x, y), moved.contains(x + 4, y - 1));
            }
        }
    }

    // ---- mask ----

    @Test
    void encodeDecodeRoundTripsNegativeAndLargeCoordinates() {
        int[] values = {0, 1, -1, 17, -300, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int x : values) {
            for (int y : values) {
                long packed = MaskSelectionRegion.encode(x, y);
                assertEquals(x, MaskSelectionRegion.decodeX(packed));
                assertEquals(y, MaskSelectionRegion.decodeY(packed));
            }
        }
    }

    @Test
    void maskSelectsExactlyItsPixelsWithTightBounds() {
        Set<Long> pixels = Set.of(
                MaskSelectionRegion.encode(2, 3),
                MaskSelectionRegion.encode(5, 3),
                MaskSelectionRegion.encode(4, 6));
        MaskSelectionRegion mask = MaskSelectionRegion.fromEncodedPixels(pixels);

        assertEquals(new Rectangle(2, 3, 4, 4), mask.getBounds());
        assertEquals(3, count(mask, 10, 10));
        assertTrue(mask.contains(5, 3));
        assertFalse(mask.contains(3, 3), "holes inside the bounds are not selected");
        assertFalse(mask.contains(99, 99));
        assertEquals(SelectionRegion.SelectionType.FREEFORM, mask.getType());
    }

    @Test
    void duplicatePixelsAreCountedOnce() {
        List<Long> pixels = new ArrayList<>();
        pixels.add(MaskSelectionRegion.encode(1, 1));
        pixels.add(MaskSelectionRegion.encode(1, 1));
        MaskSelectionRegion mask = MaskSelectionRegion.fromEncodedPixels(pixels);
        assertEquals(MaskSelectionRegion.fromEncodedPixels(List.of(MaskSelectionRegion.encode(1, 1))), mask);
    }

    @Test
    void emptyMasksAreSharedAndContainNothing() {
        assertSame(MaskSelectionRegion.empty(), MaskSelectionRegion.fromEncodedPixels(null));
        assertSame(MaskSelectionRegion.empty(), MaskSelectionRegion.fromEncodedPixels(List.of()));
        assertTrue(MaskSelectionRegion.empty().isEmpty());
        assertFalse(MaskSelectionRegion.empty().contains(0, 0));
        assertSame(MaskSelectionRegion.empty(), MaskSelectionRegion.empty().translate(3, 3));
    }

    @Test
    void maskOfNegativeCoordinatesWorks() {
        MaskSelectionRegion mask = MaskSelectionRegion.fromEncodedPixels(List.of(
                MaskSelectionRegion.encode(-3, -2), MaskSelectionRegion.encode(-1, -2)));
        assertTrue(mask.contains(-3, -2));
        assertFalse(mask.contains(-2, -2));
        assertEquals(new Rectangle(-3, -2, 3, 1), mask.getBounds());
    }

    @Test
    @Tag("regression")
    void setAlgebraOverRandomMasksMatchesAPlainSetModel() {
        // Union / subtract / intersect / invert are built by the selection tools as
        // encoded pixel sets; the mask must contain exactly the modelled pixels.
        Random random = new Random(11);
        int w = 12, h = 9;
        for (int trial = 0; trial < 40; trial++) {
            Set<Long> a = randomPixels(random, w, h);
            Set<Long> b = randomPixels(random, w, h);

            Set<Long> union = new HashSet<>(a);
            union.addAll(b);
            Set<Long> subtract = new HashSet<>(a);
            subtract.removeAll(b);
            Set<Long> intersect = new HashSet<>(a);
            intersect.retainAll(b);
            Set<Long> invert = new HashSet<>();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    long k = MaskSelectionRegion.encode(x, y);
                    if (!a.contains(k)) invert.add(k);
                }
            }

            for (Set<Long> model : List.of(union, subtract, intersect, invert)) {
                MaskSelectionRegion region = MaskSelectionRegion.fromEncodedPixels(model);
                assertEquals(model.isEmpty(), region.isEmpty());
                for (int y = -1; y <= h; y++) {
                    for (int x = -1; x <= w; x++) {
                        assertEquals(model.contains(MaskSelectionRegion.encode(x, y)), region.contains(x, y),
                                "trial " + trial + " (" + x + "," + y + ")");
                    }
                }
                if (!model.isEmpty()) {
                    Rectangle bounds = region.getBounds();
                    for (long k : model) {
                        assertTrue(bounds.contains(MaskSelectionRegion.decodeX(k), MaskSelectionRegion.decodeY(k)));
                    }
                }
            }
        }
    }

    @Test
    void translatedMaskKeepsItsShapeAndEqualityIsByContent() {
        MaskSelectionRegion mask = MaskSelectionRegion.fromEncodedPixels(List.of(
                MaskSelectionRegion.encode(0, 0), MaskSelectionRegion.encode(2, 1)));
        SelectionRegion moved = mask.translate(5, 5);
        assertTrue(moved.contains(5, 5));
        assertTrue(moved.contains(7, 6));
        assertFalse(moved.contains(6, 5));
        assertFalse(moved.contains(0, 0));

        MaskSelectionRegion same = MaskSelectionRegion.fromEncodedPixels(List.of(
                MaskSelectionRegion.encode(5, 5), MaskSelectionRegion.encode(7, 6)));
        assertEquals(same, moved);
        assertEquals(same.hashCode(), moved.hashCode());
        assertNotEquals(mask, moved);
    }

    private static Set<Long> randomPixels(Random random, int w, int h) {
        Set<Long> pixels = new HashSet<>();
        int n = random.nextInt(w * h / 2);
        for (int i = 0; i < n; i++) {
            pixels.add(MaskSelectionRegion.encode(random.nextInt(w), random.nextInt(h)));
        }
        return pixels;
    }

    static int count(SelectionRegion region, int w, int h) {
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (region.contains(x, y)) n++;
            }
        }
        return n;
    }
}
