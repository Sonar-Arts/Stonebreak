package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.rendering.PreviewMapping;
import com.openmason.engine.ui.runtime.UiMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pixel / UI / editor conversions and transform inversion (#288). */
class CoordinatesTest {

    @Test
    void transformsInvertExactly() {
        UiTransform t = UiTransform.translate(30, -12).then(UiTransform.rotate(33)).then(UiTransform.scale(2, 0.5f));
        UiTransform inv = t.inverse();
        float x = 17;
        float y = 41;
        float sx = t.applyX(x, y);
        float sy = t.applyY(x, y);
        assertEquals(x, inv.applyX(sx, sy), 1e-3);
        assertEquals(y, inv.applyY(sx, sy), 1e-3);
        assertThrows(IllegalStateException.class, () -> UiTransform.scale(0, 1).inverse());
    }

    @Test
    void rotationIsClockwiseOnScreen() {
        UiTransform r = UiTransform.rotate(90);
        assertEquals(0, r.applyX(1, 0), 1e-6);
        assertEquals(1, r.applyY(1, 0), 1e-6, "+x turns to +y (down) on a y-down screen");
    }

    @Test
    void logicalAndEditorSpaces() {
        UiMetrics m = new UiMetrics(800, 600, 1.5f, 2f);
        assertEquals(10, UiCoordinates.toLogical(m, 30), 1e-6);
        assertEquals(30, UiCoordinates.toPixels(m, 10), 1e-6);
        PreviewMapping zoom = new PreviewMapping(100, 50, 3);
        float[] canvas = UiCoordinates.fromEditor(zoom, 130, 80);
        assertEquals(10, canvas[0], 1e-6);
        assertEquals(10, canvas[1], 1e-6);
        float[] back = UiCoordinates.toEditor(zoom, canvas[0], canvas[1]);
        assertEquals(130, back[0], 1e-6);
        assertTrue(UiCoordinates.onCanvas(m, 0, 0));
        assertFalse(UiCoordinates.onCanvas(m, 800, 10), "the far edge is off the canvas");
        assertFalse(UiCoordinates.onCanvas(m, -0.01f, 10));
    }
}
