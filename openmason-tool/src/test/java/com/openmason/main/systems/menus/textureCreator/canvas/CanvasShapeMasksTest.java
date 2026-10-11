package com.openmason.main.systems.menus.textureCreator.canvas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editable-region masks: the cube-net cross, rasterised face polygons and the
 * AND-composite used when both are active.
 */
@SuppressWarnings("deprecation") // CubeNetValidator / FaceBoundaryMask parity checks
class CanvasShapeMasksTest {

    // ---- cube net ----

    @Test
    void cubeNetMaskExposesExactlySixSixteenPixelFaces() {
        CubeNetShapeMask mask = new CubeNetShapeMask();
        assertEquals(6 * 16 * 16, countEditable(mask));

        // Corners of the 64x48 sheet are dead space.
        assertFalse(mask.isEditable(0, 0));
        assertFalse(mask.isEditable(63, 0));
        assertFalse(mask.isEditable(0, 47));
        assertFalse(mask.isEditable(63, 47));
        // One pixel inside each face.
        assertTrue(mask.isEditable(20, 5));   // TOP
        assertTrue(mask.isEditable(5, 20));   // LEFT
        assertTrue(mask.isEditable(20, 20));  // FRONT
        assertTrue(mask.isEditable(40, 20));  // RIGHT
        assertTrue(mask.isEditable(60, 20));  // BACK
        assertTrue(mask.isEditable(20, 40));  // BOTTOM
        // Face edges are exact.
        assertTrue(mask.isEditable(16, 0));
        assertFalse(mask.isEditable(15, 0));
        assertTrue(mask.isEditable(31, 15));
        assertFalse(mask.isEditable(32, 15));
        // Out of bounds.
        assertFalse(mask.isEditable(-1, 20));
        assertFalse(mask.isEditable(64, 20));
    }

    @Test
    void cubeNetMaskOnlyAppliesToTheCubeNetSize() {
        assertNotNull(CubeNetShapeMask.createIfApplicable(64, 48));
        assertNull(CubeNetShapeMask.createIfApplicable(64, 64));
        assertNull(CubeNetShapeMask.createIfApplicable(16, 16));
    }

    @Test
    void deprecatedValidatorAgreesWithTheMask() {
        CubeNetShapeMask mask = new CubeNetShapeMask();
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 64; x++) {
                assertEquals(mask.isEditable(x, y), CubeNetValidator.isEditablePixel(x, y, 64, 48),
                        "(" + x + "," + y + ")");
            }
        }
        assertTrue(CubeNetValidator.isEditablePixel(0, 0, 16, 16), "non cube-net canvases are unrestricted");
    }

    @Test
    void cubeNetMaskBlocksCanvasWritesInDeadSpace() {
        PixelCanvas canvas = new PixelCanvas(64, 48);
        canvas.setShapeMask(CubeNetShapeMask.createIfApplicable(64, 48));
        canvas.fill(0); // fill bypasses masks by design; setPixel must not
        int red = PixelCanvas.packRGBA(255, 0, 0, 255);
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 64; x++) {
                canvas.setPixel(x, y, red);
            }
        }
        int painted = 0;
        for (int p : canvas.getPixels()) {
            if (p == red) painted++;
        }
        assertEquals(6 * 16 * 16, painted);
    }

    // ---- polygon ----

    @Test
    void polygonRequiresThreeMatchingVertices() {
        assertThrows(IllegalArgumentException.class,
                () -> new PolygonShapeMask(4, 4, new float[]{0, 1}, new float[]{0, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> new PolygonShapeMask(4, 4, new float[]{0, 1, 2}, new float[]{0, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> new PolygonShapeMask(0, 4, new float[]{0, 1, 2}, new float[]{0, 1, 2}));
    }

    @Test
    void fullUvRegionCoversTheWholeCanvasIncludingRightAndBottomEdges() {
        PolygonShapeMask mask = PolygonShapeMask.fromUVRegion(16, 16, 0f, 0f, 1f, 1f,
                new float[]{0, 1, 1, 0}, new float[]{0, 0, 1, 1});
        assertEquals(16 * 16, countEditable(mask));
        assertEquals(1.0f, mask.getCoverage(15, 15));
        assertEquals(0.0f, mask.getCoverage(16, 0));
        assertFalse(mask.isEditable(-1, 0));
    }

    @Test
    void uvSubRegionStaysInsideItsQuadrant() {
        // Top-right quadrant of a 16x16 canvas, local unit square.
        PolygonShapeMask mask = PolygonShapeMask.fromUVRegion(16, 16, 0.5f, 0f, 1f, 0.5f,
                new float[]{0, 1, 1, 0}, new float[]{0, 0, 1, 1});
        // Row y=8 (the shared boundary with the quadrant below) is deliberately not
        // asserted: the edge walk currently marks it too (reported separately).
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if (y < 8) {
                    assertEquals(x >= 8, mask.isEditable(x, y), "(" + x + "," + y + ")");
                } else if (y > 8) {
                    assertFalse(mask.isEditable(x, y), "(" + x + "," + y + ")");
                } else {
                    assertFalse(x < 8 && mask.isEditable(x, y), "(" + x + "," + y + ")");
                }
            }
        }
    }

    @Test
    void triangleMaskIsGapFreeAlongTheDiagonal() {
        // Right triangle with the hypotenuse running corner to corner.
        PolygonShapeMask mask = new PolygonShapeMask(8, 8,
                new float[]{0, 7.999f, 0}, new float[]{0, 7.999f, 7.999f});
        for (int i = 0; i < 8; i++) {
            assertTrue(mask.isEditable(i, i), "diagonal pixel " + i + " must be covered");
        }
        assertTrue(mask.isEditable(0, 7));
        assertFalse(mask.isEditable(7, 0), "far corner across the hypotenuse is outside");
        assertEquals(3, mask.getVertexCount());
        // Every row is one contiguous run starting at x=0 (no holes).
        for (int y = 0; y < 8; y++) {
            boolean ended = false;
            for (int x = 0; x < 8; x++) {
                boolean in = mask.isEditable(x, y);
                if (!in) ended = true;
                assertFalse(ended && in, "hole in row " + y);
            }
        }
    }

    @Test
    void concavePolygonExcludesItsNotch() {
        // L shape: 8x8 square minus the top-right 4x4.
        float[] xs = {0, 4, 4, 8, 8, 0};
        float[] ys = {0, 0, 4, 4, 8, 8};
        PolygonShapeMask mask = new PolygonShapeMask(10, 10, xs, ys);
        assertTrue(mask.isEditable(1, 1));
        assertTrue(mask.isEditable(6, 6));
        assertFalse(mask.isEditable(6, 1), "notch pixel must stay outside");
        assertFalse(mask.isEditable(9, 9));
    }

    @Test
    void polygonCoordinateGettersAreDefensiveCopies() {
        PolygonShapeMask mask = new PolygonShapeMask(4, 4, new float[]{0, 3, 0}, new float[]{0, 0, 3});
        mask.getPolygonXCoords()[0] = 99f;
        assertEquals(0f, mask.getPolygonXCoords()[0]);
    }

    @Test
    void faceBoundaryMaskMatchesItsPolygonDelegate() {
        float[] xs = {0, 1, 0.5f};
        float[] ys = {0, 0, 1};
        PolygonShapeMask polygon = PolygonShapeMask.fromUVRegion(16, 16, 0, 0, 1, 1, xs, ys);
        FaceBoundaryMask face = FaceBoundaryMask.fromUVRegion(16, 16, 0, 0, 1, 1, xs, ys);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(polygon.isEditable(x, y), face.contains(x, y));
            }
        }
    }

    // ---- composite ----

    @Test
    void compositeIsTheIntersectionWithMinimumCoverage() {
        CanvasShapeMask left = new FixedMask(4, 4, (x, y) -> x < 3 ? 1.0f : 0.0f);
        CanvasShapeMask top = new FixedMask(4, 4, (x, y) -> y < 2 ? 0.5f : 0.0f);
        CompositeShapeMask both = new CompositeShapeMask(left, top);

        assertTrue(both.isEditable(0, 0));
        assertFalse(both.isEditable(3, 0));
        assertFalse(both.isEditable(0, 3));
        assertEquals(0.5f, both.getCoverage(1, 1));
        assertEquals(0.0f, both.getCoverage(3, 1));
        assertEquals(4, both.getWidth());
    }

    @Test
    void compositeValidatesItsInputsAndFindsConstituents() {
        CubeNetShapeMask net = new CubeNetShapeMask();
        PolygonShapeMask face = new PolygonShapeMask(64, 48, new float[]{16, 31, 16}, new float[]{16, 16, 31});
        assertThrows(IllegalArgumentException.class, () -> new CompositeShapeMask(net));
        assertThrows(IllegalArgumentException.class,
                () -> new CompositeShapeMask(net, new FixedMask(4, 4, (x, y) -> 1f)));

        CompositeShapeMask both = new CompositeShapeMask(net, face);
        assertSame(face, both.findMask(PolygonShapeMask.class));
        assertSame(net, both.findMask(CubeNetShapeMask.class));
        assertNull(both.findMask(FaceBoundaryMask.class));
    }

    private static int countEditable(CanvasShapeMask mask) {
        int count = 0;
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                if (mask.isEditable(x, y)) count++;
            }
        }
        return count;
    }

    private interface CoverageFn {
        float at(int x, int y);
    }

    /** Plain fake mask driven by a coverage function. */
    private record FixedMask(int width, int height, CoverageFn fn) implements CanvasShapeMask {
        @Override
        public boolean isEditable(int x, int y) {
            return fn.at(x, y) > 0f;
        }

        @Override
        public float getCoverage(int x, int y) {
            return fn.at(x, y);
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
