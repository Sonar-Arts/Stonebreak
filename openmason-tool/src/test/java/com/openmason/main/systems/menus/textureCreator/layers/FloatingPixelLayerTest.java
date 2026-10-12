package com.openmason.main.systems.menus.textureCreator.layers;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Non-destructive floating previews (paste / move overlays) composited over the
 * real canvas.
 */
class FloatingPixelLayerTest {

    private static final int RED = PixelCanvas.packRGBA(255, 0, 0, 255);
    private static final int BLUE = PixelCanvas.packRGBA(0, 0, 255, 255);
    private static final int HALF_GREEN = PixelCanvas.packRGBA(0, 255, 0, 128);

    /** Plain fake: floating pixels from a map keyed by "x,y". */
    private static final class MapFloatingLayer extends FloatingPixelLayer {
        private final Map<String, Integer> pixels = new HashMap<>();

        MapFloatingLayer put(int x, int y, int color) {
            pixels.put(x + "," + y, color);
            return this;
        }

        @Override
        protected Integer getFloatingPixelAt(int canvasX, int canvasY) {
            return pixels.get(canvasX + "," + canvasY);
        }
    }

    @Test
    void previewCompositesFloatingPixelsWithoutTouchingTheSource() {
        PixelCanvas source = new PixelCanvas(3, 3);
        source.fill(BLUE);
        int[] sourceBefore = source.getPixels().clone();

        MapFloatingLayer floating = new MapFloatingLayer()
                .put(0, 0, RED)
                .put(1, 1, HALF_GREEN)
                .put(2, 2, PixelCanvas.packRGBA(9, 9, 9, 0)); // transparent: skipped

        PixelCanvas preview = floating.createPreviewCanvas(source);

        assertArrayEquals(sourceBefore, source.getPixels(), "preview must be non-destructive");
        assertEquals(RED, preview.getPixel(0, 0));
        assertEquals(PixelCanvas.blendColors(HALF_GREEN, BLUE), preview.getPixel(1, 1));
        assertEquals(BLUE, preview.getPixel(2, 2));
        assertEquals(BLUE, preview.getPixel(2, 0));
    }

    @Test
    void multiLayerPreviewStacksBackgroundThenActiveLayerThenFloating() {
        PixelCanvas background = new PixelCanvas(2, 1);
        background.fill(BLUE);
        PixelCanvas active = new PixelCanvas(2, 1);
        active.setPixel(0, 0, RED);

        MapFloatingLayer floating = new MapFloatingLayer().put(0, 0, HALF_GREEN);
        PixelCanvas preview = floating.createMultiLayerPreviewCanvas(background, active);

        assertEquals(PixelCanvas.blendColors(HALF_GREEN, RED), preview.getPixel(0, 0));
        assertEquals(BLUE, preview.getPixel(1, 0), "transparent active pixels show the background");
        assertEquals(BLUE, background.getPixel(0, 0));
    }
}
