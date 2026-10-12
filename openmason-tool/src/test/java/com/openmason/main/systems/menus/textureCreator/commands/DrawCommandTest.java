package com.openmason.main.systems.menus.textureCreator.commands;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-pixel stroke recording: the first old colour and the last new colour of
 * each pixel are what undo/redo restore.
 */
class DrawCommandTest {

    private static final int RED = PixelCanvas.packRGBA(255, 0, 0, 255);
    private static final int GREEN = PixelCanvas.packRGBA(0, 255, 0, 255);
    private static final int BLUE = PixelCanvas.packRGBA(0, 0, 255, 255);

    @Test
    void overlappingWritesUndoToTheOriginalAndRedoToTheFinalColour() {
        PixelCanvas canvas = new PixelCanvas(4, 4);
        canvas.setPixel(1, 1, BLUE);
        DrawCommand stroke = new DrawCommand(canvas, "Pencil");

        // The same pixel is painted twice inside one stroke.
        paint(canvas, stroke, 1, 1, RED);
        paint(canvas, stroke, 1, 1, GREEN);
        paint(canvas, stroke, 2, 3, RED);

        stroke.undo();
        assertEquals(BLUE, canvas.getPixel(1, 1), "undo restores the colour before the FIRST write");
        assertEquals(0, canvas.getPixel(2, 3));

        stroke.execute();
        assertEquals(GREEN, canvas.getPixel(1, 1), "redo applies the colour of the LAST write");
        assertEquals(RED, canvas.getPixel(2, 3));
        assertEquals("Pencil (2 pixels)", stroke.getDescription());
    }

    @Test
    void anEmptyCommandReportsNoChanges() {
        DrawCommand command = new DrawCommand(new PixelCanvas(2, 2), "Noop");
        assertFalse(command.hasChanges());
        command.recordPixelChange(0, 0, 0, RED);
        assertTrue(command.hasChanges());
    }

    @Test
    @Tag("regression")
    void randomStrokesUndoAndRedoExactly() {
        Random random = new Random(31337);
        for (int trial = 0; trial < 25; trial++) {
            PixelCanvas canvas = new PixelCanvas(9, 7);
            for (int i = 0; i < canvas.getPixels().length; i++) {
                canvas.getPixels()[i] = random.nextInt();
            }
            int[] original = canvas.getPixels().clone();

            DrawCommand stroke = new DrawCommand(canvas, "Random");
            int writes = 1 + random.nextInt(120);
            for (int i = 0; i < writes; i++) {
                paint(canvas, stroke, random.nextInt(9), random.nextInt(7), random.nextInt());
            }
            int[] painted = canvas.getPixels().clone();

            for (int cycle = 0; cycle < 3; cycle++) {
                stroke.undo();
                assertArrayEquals(original, canvas.getPixels(), "trial " + trial + " undo " + cycle);
                stroke.execute();
                assertArrayEquals(painted, canvas.getPixels(), "trial " + trial + " redo " + cycle);
            }
        }
    }

    /** How the tools paint: read old, record, then write. */
    static void paint(PixelCanvas canvas, DrawCommand command, int x, int y, int color) {
        command.recordPixelChange(x, y, canvas.getPixel(x, y), color);
        canvas.setPixel(x, y, color);
    }

    /** Undo used to write through the selection made after the edit, leaving the rest of the stroke behind. */
    @Tag("regression")
    @Test
    void undoAndRedoIgnoreASelectionMadeAfterTheEdit() {
        PixelCanvas canvas = new PixelCanvas(6, 1);
        SelectionManager selection = new SelectionManager();
        canvas.setSelectionManager(selection);
        DrawCommand command = new DrawCommand(canvas, "Pencil");
        for (int x = 0; x < 6; x++) {
            paint(canvas, command, x, 0, RED);
        }

        selection.setActiveSelection(new RectangularSelection(0, 0, 1, 0));
        command.undo();
        assertArrayEquals(new int[6], canvas.getPixels());
        command.execute();
        assertArrayEquals(new int[]{RED, RED, RED, RED, RED, RED}, canvas.getPixels());
    }

    /** Tools record before the canvas rejects a write; replaying those without the selection would paint them. */
    @Tag("regression")
    @Test
    void writesTheSelectionRejectedAreNeverReplayed() {
        PixelCanvas canvas = new PixelCanvas(6, 1);
        SelectionManager selection = new SelectionManager();
        canvas.setSelectionManager(selection);
        selection.setActiveSelection(new RectangularSelection(2, 0, 3, 0));
        DrawCommand command = new DrawCommand(canvas, "Fill");
        for (int x = 0; x < 6; x++) {
            paint(canvas, command, x, 0, RED);
        }
        int[] painted = {0, 0, RED, RED, 0, 0};
        assertArrayEquals(painted, canvas.getPixels());

        selection.clearSelection();
        command.undo();
        assertArrayEquals(new int[6], canvas.getPixels());
        command.execute();
        assertArrayEquals(painted, canvas.getPixels());
    }

    /** The executeCommand path: the first execute is the edit and still honours the selection. */
    @Tag("regression")
    @Test
    void firstExecuteRespectsTheSelectionAndLaterRedosReplayIt() {
        PixelCanvas canvas = new PixelCanvas(4, 1);
        SelectionManager selection = new SelectionManager();
        canvas.setSelectionManager(selection);
        selection.setActiveSelection(new RectangularSelection(1, 0, 1, 0));
        DrawCommand command = new DrawCommand(canvas, "Delete");
        for (int x = 0; x < 4; x++) {
            command.recordPixelChange(x, 0, 0, GREEN);
        }

        command.execute();
        int[] edited = {0, GREEN, 0, 0};
        assertArrayEquals(edited, canvas.getPixels());

        selection.setActiveSelection(new RectangularSelection(3, 0, 3, 0));
        command.undo();
        assertArrayEquals(new int[4], canvas.getPixels());
        command.execute();
        assertArrayEquals(edited, canvas.getPixels());
    }
}
