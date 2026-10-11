package com.openmason.main.systems.menus.textureCreator.tools;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;
import com.openmason.main.systems.menus.textureCreator.tools.selection.RectanglePreview;
import com.openmason.main.systems.menus.textureCreator.tools.selection.SelectionPreview;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rectangle marquee (drag creates an inclusive, normalised selection; a click
 * clears it; the preview exists only mid-drag) and the eyedropper.
 */
class RectangleSelectionAndPickerToolTest {

    @Test
    void dragInAnyDirectionCreatesAnInclusiveNormalisedRectangle() {
        RectangleSelectionTool tool = new RectangleSelectionTool();

        tool.onMouseDown(6, 5, 0, null, null);
        tool.onMouseDrag(2, 1, 0, null, null);
        tool.onMouseUp(0, null, null);

        assertTrue(tool.hasSelection());
        SelectionRegion selection = tool.getSelection();
        assertEquals(new Rectangle(2, 1, 5, 5), selection.getBounds());
        assertTrue(selection.contains(2, 1));
        assertTrue(selection.contains(6, 5));
        assertFalse(selection.contains(7, 5));
    }

    @Test
    void aClickWithoutDragReportsAClearedSelection() {
        RectangleSelectionTool tool = new RectangleSelectionTool();

        tool.onMouseDown(3, 3, 0, null, null);
        tool.onMouseUp(0, null, null);

        assertTrue(tool.hasSelection(), "the change (clear) must still be reported");
        assertNull(tool.getSelection());
    }

    @Test
    void singleRowDragIsAValidSelection() {
        RectangleSelectionTool tool = new RectangleSelectionTool();

        tool.onMouseDown(1, 4, 0, null, null);
        tool.onMouseDrag(5, 4, 0, null, null);
        tool.onMouseUp(0, null, null);

        assertEquals(new Rectangle(1, 4, 5, 1), tool.getSelection().getBounds());
    }

    @Test
    void previewOnlyExistsWhileDragging() {
        RectangleSelectionTool tool = new RectangleSelectionTool();
        assertNull(tool.getPreview());

        tool.onMouseDown(0, 0, 0, null, null);
        tool.onMouseDrag(3, 2, 0, null, null);
        SelectionPreview preview = tool.getPreview();
        assertInstanceOf(RectanglePreview.class, preview);

        tool.onMouseUp(0, null, null);
        assertNull(tool.getPreview());
    }

    @Test
    void clearSelectionConsumesTheChangeFlagAndResetForgetsEverything() {
        RectangleSelectionTool tool = new RectangleSelectionTool();
        tool.onMouseDown(0, 0, 0, null, null);
        tool.onMouseDrag(2, 2, 0, null, null);
        tool.onMouseUp(0, null, null);

        tool.clearSelection();
        assertFalse(tool.hasSelection());

        tool.reset();
        assertNull(tool.getSelection());
        tool.onMouseDrag(5, 5, 0, null, null); // drag without press is ignored
        tool.onMouseUp(0, null, null);
        assertFalse(tool.hasSelection());
    }

    @Test
    void colorPickerSamplesTheLastPixelUnderTheCursorWithoutPainting() {
        PixelCanvas canvas = new PixelCanvas(4, 4);
        canvas.setPixel(1, 1, 0xFF0000FF);
        canvas.setPixel(2, 3, 0x80FF00FF);
        int[] before = canvas.getPixels().clone();
        ColorPickerTool picker = new ColorPickerTool();

        picker.onMouseDown(1, 1, 0, canvas, null);
        assertEquals(0xFF0000FF, picker.getPickedColor());

        picker.onMouseDrag(2, 3, 0, canvas, null);
        picker.onMouseUp(0, canvas, null);
        assertEquals(0x80FF00FF, picker.getPickedColor(), "alpha is sampled too");

        picker.onMouseDown(-1, 9, 0, canvas, null);
        assertEquals(0x00000000, picker.getPickedColor(), "off-canvas samples transparent");

        assertEquals(java.util.Arrays.toString(before), java.util.Arrays.toString(canvas.getPixels()));
    }
}
