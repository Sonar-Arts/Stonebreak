package com.openmason.main.systems.menus.textureCreator.tools.move;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformOverlayRendererColorsTest {

    private static final int[] BLUE_CONSTANTS = {
            TransformOverlayRenderer.HANDLE_HOVER_FILL,
            TransformOverlayRenderer.HANDLE_HOVER_OUTLINE,
            TransformOverlayRenderer.HANDLE_ACTIVE_FILL,
            TransformOverlayRenderer.HANDLE_ACTIVE_OUTLINE,
            TransformOverlayRenderer.PIVOT_HOVER_COLOR
    };

    @Test
    void hoverAndActiveConstantsDecodeAsFullyOpaque() {
        for (int constant : BLUE_CONSTANTS) {
            int[] rgba = PixelCanvas.unpackRGBA(constant);
            assertEquals(255, rgba[3], "alpha must be opaque, got " + rgba[3]);
        }
    }

    @Test
    void outlineConstantsAreBluerThanRed() {
        for (int constant : BLUE_CONSTANTS) {
            int[] rgba = PixelCanvas.unpackRGBA(constant);
            assertTrue(rgba[2] > rgba[0],
                    "blue channel must outrank red (outlines are blue, not orange), got R=" + rgba[0] + " B=" + rgba[2]);
        }
    }

    @Test
    void grayAndWhiteConstantsSurvivePackingUnchanged() {
        int[] outline = PixelCanvas.unpackRGBA(TransformOverlayRenderer.OUTLINE_COLOR);
        assertEquals(61, outline[0]);
        assertEquals(61, outline[1]);
        assertEquals(61, outline[2]);

        int[] pivot = PixelCanvas.unpackRGBA(TransformOverlayRenderer.PIVOT_COLOR);
        assertEquals(61, pivot[0]);
        assertEquals(61, pivot[1]);
        assertEquals(61, pivot[2]);

        int[] fill = PixelCanvas.unpackRGBA(TransformOverlayRenderer.HANDLE_FILL);
        assertEquals(255, fill[0]);
        assertEquals(255, fill[1]);
        assertEquals(255, fill[2]);

        int[] handleOutline = PixelCanvas.unpackRGBA(TransformOverlayRenderer.HANDLE_OUTLINE);
        assertEquals(0, handleOutline[0]);
        assertEquals(0, handleOutline[1]);
        assertEquals(0, handleOutline[2]);
    }
}
