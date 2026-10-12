package com.openmason.main.systems.menus.textureCreator.canvas;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-weighted blending used for soft brush/shape edges and polygon mask
 * boundaries.
 */
class CoverageBlenderTest {

    private static final int RED = PixelCanvas.packRGBA(255, 0, 0, 255);
    private static final int BLUE = PixelCanvas.packRGBA(0, 0, 255, 255);

    @Test
    void zeroCoverageKeepsTheExistingPixel() {
        assertEquals(BLUE, CoverageBlender.blendWithCoverage(RED, BLUE, 0.0f));
        assertEquals(BLUE, CoverageBlender.blendWithCoverage(RED, BLUE, -1.0f));
    }

    @Test
    void fullCoverageReplacesThePixelOutright() {
        // Contract the drawing tools rely on: at full coverage the paint colour is
        // written as-is (pencil semantics), translucency included.
        int halfRed = PixelCanvas.packRGBA(255, 0, 0, 128);
        assertEquals(halfRed, CoverageBlender.blendWithCoverage(halfRed, BLUE, 1.0f));
        assertEquals(halfRed, CoverageBlender.blendWithCoverage(halfRed, BLUE, 2.0f));
    }

    @Test
    @Tag("regression")
    void partialCoverageScalesPaintAlphaBeforeCompositing() {
        int[] out = PixelCanvas.unpackRGBA(CoverageBlender.blendWithCoverage(RED, 0, 0.5f));
        assertEquals(255, out[0]);
        assertEquals(128, out[3]); // round(255 * 0.5)

        int[] mixed = PixelCanvas.unpackRGBA(CoverageBlender.blendWithCoverage(RED, BLUE, 0.25f));
        assertEquals(255, mixed[3]);
        assertTrue(mixed[0] > 0 && mixed[2] > mixed[0], "a quarter of red over blue stays mostly blue");
    }

    @Test
    void negligibleCoverageOnFaintPaintIsANoOp() {
        int faint = PixelCanvas.packRGBA(255, 255, 255, 1);
        assertEquals(BLUE, CoverageBlender.blendWithCoverage(faint, BLUE, 0.1f));
    }

    @Test
    void blendedResultIsMonotonicInCoverage() {
        int previousRed = -1;
        for (int i = 1; i < 100; i++) {
            int red = PixelCanvas.unpackRGBA(CoverageBlender.blendWithCoverage(RED, BLUE, i / 100f))[0];
            assertTrue(red >= previousRed, "coverage " + i + "% produced less red than " + (i - 1) + "%");
            previousRed = red;
        }
    }

    @Test
    void smoothstepClampsAndIsSymmetric() {
        assertEquals(0f, CoverageBlender.smoothstep(0f, 1f, -3f));
        assertEquals(1f, CoverageBlender.smoothstep(0f, 1f, 3f));
        assertEquals(0.5f, CoverageBlender.smoothstep(0f, 1f, 0.5f), 1e-6);
        for (float t = 0f; t <= 1f; t += 0.05f) {
            assertEquals(1f, CoverageBlender.smoothstep(0f, 1f, t) + CoverageBlender.smoothstep(0f, 1f, 1f - t), 1e-5);
        }
    }

    @Test
    void pointToSegmentDistanceHandlesProjectionEndpointsAndDegenerateSegments() {
        assertEquals(2f, CoverageBlender.pointToSegmentDistance(5, 2, 0, 0, 10, 0), 1e-6);
        assertEquals(5f, CoverageBlender.pointToSegmentDistance(13, 4, 0, 0, 10, 0), 1e-6); // past the end
        assertEquals(5f, CoverageBlender.pointToSegmentDistance(-3, -4, 0, 0, 10, 0), 1e-6); // before the start
        assertEquals(5f, CoverageBlender.pointToSegmentDistance(3, 4, 0, 0, 0, 0), 1e-6);    // zero length
        assertEquals(0f, CoverageBlender.pointToSegmentDistance(4, 4, 0, 0, 8, 8), 1e-6);    // on the line
    }

    /** Transparent paint used to blend to nothing at partial coverage, so soft-edged erasing did nothing. */
    @Tag("regression")
    @Test
    void transparentPaintErasesTheCoveredFractionOfAlpha() {
        int clear = 0x00000000;
        int[] half = PixelCanvas.unpackRGBA(CoverageBlender.blendWithCoverage(clear, RED, 0.5f));
        assertEquals(255, half[0], "colour is kept, only alpha is removed");
        assertEquals(128, half[3]);

        assertEquals(RED, CoverageBlender.blendWithCoverage(clear, RED, 0.0f));
        assertEquals(clear, CoverageBlender.blendWithCoverage(clear, RED, 1.0f));
        assertEquals(clear, CoverageBlender.blendWithCoverage(clear, PixelCanvas.packRGBA(9, 9, 9, 1), 0.9f),
                "an alpha that rounds to zero becomes fully clear");
    }
}
