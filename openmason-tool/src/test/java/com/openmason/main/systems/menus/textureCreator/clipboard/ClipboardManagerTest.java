package com.openmason.main.systems.menus.textureCreator.clipboard;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.CommandHistory;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import com.openmason.main.systems.menus.textureCreator.commands.PasteCommand;
import com.openmason.main.systems.menus.textureCreator.selection.MaskSelectionRegion;
import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Copy / cut / paste of selected pixels, including irregular selections and
 * selections hanging off the canvas.
 */
class ClipboardManagerTest {

    private static PixelCanvas numbered(int w, int h) {
        PixelCanvas canvas = new PixelCanvas(w, h);
        for (int i = 0; i < w * h; i++) {
            canvas.getPixels()[i] = PixelCanvas.packRGBA(i + 1, 0, 0, 255);
        }
        return canvas;
    }

    @Test
    void copyCapturesTheSelectionBoundsAndOrigin() {
        PixelCanvas canvas = numbered(5, 5);
        ClipboardManager clipboard = new ClipboardManager();

        clipboard.copy(canvas, new RectangularSelection(1, 2, 3, 3));

        assertTrue(clipboard.hasData());
        PixelCanvas data = clipboard.getClipboardCanvas();
        assertEquals(3, data.getWidth());
        assertEquals(2, data.getHeight());
        assertEquals(1, clipboard.getSourceX());
        assertEquals(2, clipboard.getSourceY());
        assertEquals(canvas.getPixel(1, 2), data.getPixel(0, 0));
        assertEquals(canvas.getPixel(3, 3), data.getPixel(2, 1));
    }

    @Test
    void copyIsASnapshotNotAView() {
        PixelCanvas canvas = numbered(3, 3);
        ClipboardManager clipboard = new ClipboardManager();
        clipboard.copy(canvas, new RectangularSelection(0, 0, 0, 0));
        int copied = clipboard.getClipboardCanvas().getPixel(0, 0);
        canvas.setPixel(0, 0, 0);
        assertEquals(copied, clipboard.getClipboardCanvas().getPixel(0, 0));
    }

    @Test
    void irregularSelectionsCopyOnlySelectedPixels() {
        PixelCanvas canvas = numbered(4, 4);
        ClipboardManager clipboard = new ClipboardManager();
        clipboard.copy(canvas, MaskSelectionRegion.fromEncodedPixels(List.of(
                MaskSelectionRegion.encode(0, 0), MaskSelectionRegion.encode(2, 2))));

        PixelCanvas data = clipboard.getClipboardCanvas();
        assertEquals(3, data.getWidth());
        assertEquals(canvas.getPixel(0, 0), data.getPixel(0, 0));
        assertEquals(canvas.getPixel(2, 2), data.getPixel(2, 2));
        assertEquals(0, data.getPixel(1, 1), "unselected pixels inside the bounds stay transparent");
    }

    @Test
    void selectionHangingOffTheCanvasCopiesOnlyTheOverlap() {
        PixelCanvas canvas = numbered(4, 4);
        ClipboardManager clipboard = new ClipboardManager();
        clipboard.copy(canvas, new RectangularSelection(-2, -2, 1, 1));

        PixelCanvas data = clipboard.getClipboardCanvas();
        assertEquals(4, data.getWidth(), "clipboard keeps the full selection bounds");
        assertEquals(0, data.getPixel(0, 0), "off-canvas part is transparent");
        assertEquals(canvas.getPixel(0, 0), data.getPixel(2, 2));
        assertEquals(-2, clipboard.getSourceX());
    }

    @Test
    void copyWithoutCanvasOrSelectionLeavesTheClipboardUntouched() {
        ClipboardManager clipboard = new ClipboardManager();
        clipboard.copy(null, new RectangularSelection(0, 0, 1, 1));
        clipboard.copy(numbered(2, 2), null);
        clipboard.copy(numbered(2, 2), MaskSelectionRegion.empty());
        assertFalse(clipboard.hasData());

        clipboard.copy(numbered(2, 2), new RectangularSelection(0, 0, 0, 0));
        clipboard.copy(numbered(2, 2), null); // a failed copy keeps the old data
        assertTrue(clipboard.hasData());

        clipboard.clear();
        assertFalse(clipboard.hasData());
        assertNull(clipboard.getClipboardCanvas());
        assertEquals(0, clipboard.getSourceX());
    }

    @Test
    @Tag("regression")
    void cutClearsTheSelectionAndItsCommandUndoesExactly() {
        PixelCanvas canvas = numbered(4, 4);
        int[] before = canvas.getPixels().clone();
        ClipboardManager clipboard = new ClipboardManager();
        DrawCommand cut = new DrawCommand(canvas, "Cut");

        clipboard.cut(canvas, new RectangularSelection(1, 1, 2, 2), cut);

        assertEquals(0, canvas.getPixel(1, 1));
        assertEquals(0, canvas.getPixel(2, 2));
        assertEquals(before[0], canvas.getPixel(0, 0));
        assertEquals(before[1 * 4 + 1], clipboard.getClipboardCanvas().getPixel(0, 0));
        assertTrue(cut.hasChanges());

        cut.undo();
        assertArrayEquals(before, canvas.getPixels());
        cut.execute();
        assertEquals(0, canvas.getPixel(1, 1));
    }

    @Test
    void cutWithoutASelectionChangesNothing() {
        PixelCanvas canvas = numbered(2, 2);
        int[] before = canvas.getPixels().clone();
        DrawCommand cut = new DrawCommand(canvas, "Cut");
        new ClipboardManager().cut(canvas, null, cut);
        assertArrayEquals(before, canvas.getPixels());
        assertFalse(cut.hasChanges());
    }

    @Test
    @Tag("integration")
    void cutThenPasteElsewhereThroughHistoryRoundTrips() {
        // Clipboard + paste + undo history working together, as Ctrl+X / Ctrl+V do.
        PixelCanvas canvas = numbered(6, 6);
        SelectionManager selections = new SelectionManager();
        canvas.setSelectionManager(selections);
        int[] original = canvas.getPixels().clone();
        ClipboardManager clipboard = new ClipboardManager();
        CommandHistory history = new CommandHistory();

        RectangularSelection selection = new RectangularSelection(0, 0, 1, 1);
        selections.setActiveSelection(selection);
        DrawCommand cut = new DrawCommand(canvas, "Cut");
        clipboard.cut(canvas, selection, cut);
        history.pushCompleted(cut);

        selections.clearSelection(); // a paste lands outside the old selection
        history.executeCommand(new PasteCommand(canvas, clipboard.getClipboardCanvas(), 4, 4, null, true));

        assertEquals(original[0], canvas.getPixel(4, 4));
        assertEquals(original[7], canvas.getPixel(5, 5));
        assertEquals(0, canvas.getPixel(0, 0));

        history.undo();
        history.undo();
        assertArrayEquals(original, canvas.getPixels());
    }
}
