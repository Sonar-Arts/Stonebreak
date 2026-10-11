package com.openmason.main.systems.menus.textureCreator.canvas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canvas view state: zoom clamping, panning and the framing/coordinate maths
 * behind "Edit Texture" on a face.
 */
class CanvasStateTest {

    @Test
    void zoomIsClampedToItsRange() {
        CanvasState state = new CanvasState();
        state.setZoomLevel(1000f);
        assertEquals(32f, state.getZoomLevel());
        state.setZoomLevel(0.0001f);
        assertEquals(0.25f, state.getZoomLevel());

        state.resetZoom();
        state.zoomIn(2f);
        assertEquals(16f, state.getZoomLevel());
        state.zoomOut(4f);
        assertEquals(4f, state.getZoomLevel());
    }

    @Test
    void panningAccumulatesMouseDeltasOnlyWhileActive() {
        CanvasState state = new CanvasState();
        state.updatePanning(50, 50); // not panning: ignored
        assertEquals(0f, state.getPanOffsetX());

        state.startPanning(10, 10);
        state.updatePanning(15, 7);
        state.updatePanning(20, 10);
        assertTrue(state.isPanning());
        assertEquals(10f, state.getPanOffsetX());
        assertEquals(0f, state.getPanOffsetY());

        state.stopPanning();
        state.updatePanning(100, 100);
        assertFalse(state.isPanning());
        assertEquals(10f, state.getPanOffsetX());

        state.resetView();
        assertEquals(0f, state.getPanOffsetX());
        assertEquals(8f, state.getZoomLevel());
    }

    @Test
    void zoomToFitUsesTheConstrainingAxisWithMargin() {
        // 800x400 viewport, 16x16 region: height constrains (400 * 0.9 / 16 = 22.5).
        assertEquals(22.5f, CanvasState.calculateZoomToFit(16, 16, 800, 400), 1e-4);
        assertEquals(32f, CanvasState.calculateZoomToFit(1, 1, 4000, 4000), "clamped to max");
        assertEquals(0.25f, CanvasState.calculateZoomToFit(10000, 10000, 100, 100), "clamped to min");
        assertEquals(8f, CanvasState.calculateZoomToFit(0, 16, 800, 400), "degenerate input -> default");
    }

    @Test
    void screenToCanvasInvertsPanAndZoomWithFloor() {
        CanvasState state = new CanvasState();
        state.setZoomLevel(4f);
        state.adjustPanOffset(8, -4);
        int[] out = new int[2];
        state.screenToCanvasCoords(100 + 8 + 4 * 3 + 1, 50 - 4 + 4 * 5 + 3, 100, 50, out);
        assertArrayEquals(new int[]{3, 5}, out);

        state.screenToCanvasCoords(100 + 8 - 1, 50 - 4, 100, 50, out);
        assertArrayEquals(new int[]{-1, 0}, out, "left of the canvas floors to -1, not 0");
    }

    @Test
    void frameToCentresTheRegionAndFitsIt() {
        CanvasState state = new CanvasState();
        // Top-left quarter of a 64x64 canvas in a 600x600 viewport.
        state.frameTo(0f, 0f, 0.5f, 0.5f, 64, 64, 600, 600);

        float zoom = state.getZoomLevel();
        assertEquals(600 * 0.9f / 32, zoom, 1e-4);
        // The renderer centres the full canvas; the pan must move the region centre
        // (16,16) onto the canvas centre (32,32).
        assertEquals((32 - 16) * zoom, state.getPanOffsetX(), 1e-3);
        assertEquals((32 - 16) * zoom, state.getPanOffsetY(), 1e-3);
    }

    @Test
    void frameToWithAnEmptyRegionResetsTheView() {
        CanvasState state = new CanvasState();
        state.setZoomLevel(20f);
        state.adjustPanOffset(30, 30);
        state.frameTo(0.5f, 0.5f, 0.5f, 0.9f, 64, 64, 600, 600);
        assertEquals(8f, state.getZoomLevel());
        assertEquals(0f, state.getPanOffsetX());
    }
}
