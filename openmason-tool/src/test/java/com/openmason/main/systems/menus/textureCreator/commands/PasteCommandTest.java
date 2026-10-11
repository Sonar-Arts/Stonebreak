package com.openmason.main.systems.menus.textureCreator.commands;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pasting clipboard pixels onto a canvas: clipping at the canvas edge,
 * transparent-pixel handling and exact undo/redo.
 */
class PasteCommandTest {

    private static final int RED = PixelCanvas.packRGBA(255, 0, 0, 255);
    private static final int BLUE = PixelCanvas.packRGBA(0, 0, 255, 255);

    private static PixelCanvas clip(int w, int h, int color) {
        PixelCanvas clip = new PixelCanvas(w, h);
        clip.fill(color);
        return clip;
    }

    @Test
    void pasteWritesTheClipboardAtTheOffset() {
        PixelCanvas target = new PixelCanvas(6, 6);
        new PasteCommand(target, clip(2, 2, RED), 3, 1, null, false).execute();
        assertEquals(RED, target.getPixel(3, 1));
        assertEquals(RED, target.getPixel(4, 2));
        assertEquals(0, target.getPixel(5, 2));
        assertEquals(0, target.getPixel(3, 3));
    }

    @Test
    @Tag("regression")
    void pastePartlyOutsideTheCanvasClipsAndUndoesExactly() {
        PixelCanvas target = new PixelCanvas(4, 4);
        target.fill(BLUE);
        int[] before = target.getPixels().clone();

        // 3x3 clip hanging off the bottom-right and the top-left corners.
        PasteCommand bottomRight = new PasteCommand(target, clip(3, 3, RED), 2, 2, null, false);
        bottomRight.execute();
        int red = 0;
        for (int p : target.getPixels()) if (p == RED) red++;
        assertEquals(4, red, "only the 2x2 overlap lands on the canvas");

        PasteCommand topLeft = new PasteCommand(target, clip(3, 3, RED), -2, -2, null, false);
        topLeft.execute();
        assertEquals(RED, target.getPixel(0, 0));
        assertEquals(BLUE, target.getPixel(1, 0));

        topLeft.undo();
        bottomRight.undo();
        assertArrayEquals(before, target.getPixels());
    }

    @Test
    void pasteEntirelyOutsideTheCanvasChangesNothing() {
        PixelCanvas target = new PixelCanvas(4, 4);
        target.fill(BLUE);
        int[] before = target.getPixels().clone();
        PasteCommand paste = new PasteCommand(target, clip(2, 2, RED), 10, -7, null, false);
        paste.execute();
        assertArrayEquals(before, target.getPixels());
        paste.undo();
        assertArrayEquals(before, target.getPixels());
    }

    @Test
    void transparentClipboardPixelsAreSkippedOnlyWhenRequested() {
        PixelCanvas clip = new PixelCanvas(2, 1);
        clip.setPixel(0, 0, RED); // (1,0) stays transparent

        PixelCanvas keep = new PixelCanvas(2, 1);
        keep.fill(BLUE);
        new PasteCommand(keep, clip, 0, 0, null, true).execute();
        assertEquals(BLUE, keep.getPixel(1, 0), "skipTransparentPixels preserves what is underneath");

        PixelCanvas replace = new PixelCanvas(2, 1);
        replace.fill(BLUE);
        new PasteCommand(replace, clip, 0, 0, null, false).execute();
        assertEquals(0, replace.getPixel(1, 0), "otherwise transparency is pasted too");
    }

    @Test
    void undoBeforeExecuteIsANoOp() {
        PixelCanvas target = new PixelCanvas(2, 2);
        target.fill(BLUE);
        new PasteCommand(target, clip(1, 1, RED), 0, 0, null, false).undo();
        assertEquals(BLUE, target.getPixel(0, 0));
    }

    @Test
    @Tag("regression")
    void randomPastesThroughHistoryRoundTrip() {
        Random random = new Random(77);
        PixelCanvas target = new PixelCanvas(10, 8);
        for (int i = 0; i < target.getPixels().length; i++) target.getPixels()[i] = random.nextInt();
        int[] original = target.getPixels().clone();

        CommandHistory history = new CommandHistory();
        for (int i = 0; i < 15; i++) {
            PixelCanvas clipboard = new PixelCanvas(1 + random.nextInt(6), 1 + random.nextInt(6));
            for (int p = 0; p < clipboard.getPixels().length; p++) clipboard.getPixels()[p] = random.nextInt();
            history.executeCommand(new PasteCommand(target, clipboard,
                    random.nextInt(16) - 4, random.nextInt(14) - 4, null, random.nextBoolean()));
        }
        int[] pasted = target.getPixels().clone();

        while (history.undo()) { }
        assertArrayEquals(original, target.getPixels());
        while (history.redo()) { }
        assertArrayEquals(pasted, target.getPixels());
        assertEquals("Paste at (1, 2)", new PasteCommand(target, clip(1, 1, RED), 1, 2, null, false).getDescription());
    }

    /** Undo/redo used to go through the current selection, so they skipped whatever it excluded. */
    @Tag("regression")
    @Test
    void undoAndRedoAreNotClippedByTheCurrentSelection() {
        PixelCanvas target = new PixelCanvas(4, 1);
        SelectionManager selection = new SelectionManager();
        target.setSelectionManager(selection);
        selection.setActiveSelection(new RectangularSelection(1, 0, 2, 0));
        PasteCommand paste = new PasteCommand(target, clip(4, 1, RED), 0, 0, null, false);

        paste.execute();
        int[] pasted = {0, RED, RED, 0};
        assertArrayEquals(pasted, target.getPixels(), "the paste itself honours the selection");

        selection.setActiveSelection(new RectangularSelection(3, 0, 3, 0));
        paste.undo();
        assertArrayEquals(new int[4], target.getPixels());
        paste.execute();
        assertArrayEquals(pasted, target.getPixels(), "redo replays what was pasted, nothing more");
    }
}
