package com.openmason.main.systems.menus.textureCreator.tools.move;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.move.MoveSelectionCommand;
import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move tool translation gestures driven through the {@link com.openmason.main.systems.menus.textureCreator.tools.DrawingTool}
 * mouse protocol (no window: rotation's mouse capture is never reached), and the
 * commit/undo of the resulting {@link MoveSelectionCommand}.
 */
class MoveToolControllerTest {

    private static final int RED = 0xFF0000FF;
    private static final int GREEN = 0xFF00FF00;
    private static final int CLEAR = 0x00000000;

    private PixelCanvas canvas;
    private SelectionManager selection;
    private MoveToolController move;

    @BeforeEach
    void setUp() {
        canvas = new PixelCanvas(10, 10);
        // 2x2 red block at (1,1)
        canvas.setPixel(1, 1, RED);
        canvas.setPixel(2, 1, RED);
        canvas.setPixel(1, 2, RED);
        canvas.setPixel(2, 2, RED);
        canvas.setPixel(6, 6, GREEN); // artwork under the drop target
        selection = new SelectionManager();
        selection.setActiveSelection(new RectangularSelection(1, 1, 2, 2));
        canvas.setSelectionManager(selection);
        move = new MoveToolController();
        move.setSelectionManager(selection);
    }

    @Test
    void dragInsideTheSelectionPreviewsWithoutTouchingTheCanvas() {
        int[] before = canvas.getPixels().clone();

        move.onMouseDown(1, 1, 0, canvas, null);
        move.onMouseDrag(4, 2, 0, canvas, null);

        assertTrue(move.isActive());
        assertTrue(move.hasPreviewLayer());
        assertEquals(3.0, move.getLiveTransform().translateX(), 1e-9);
        assertEquals(1.0, move.getLiveTransform().translateY(), 1e-9);
        assertArrayEquals(before, canvas.getPixels(), "the preview is non-destructive");
    }

    @Test
    void releaseProducesACommandThatMovesThePixelsAndLeavesAHole() {
        move.onMouseDown(2, 2, 0, canvas, null);
        move.onMouseDrag(7, 7, 0, canvas, null); // +5, +5 -> block lands on (6..7, 6..7)
        move.onMouseUp(0, canvas, null);

        MoveSelectionCommand command = move.getPendingCommand();
        assertNotNull(command);
        assertTrue(command.hasChanges());

        command.execute();

        assertEquals(CLEAR, canvas.getPixel(1, 1), "hole at the source");
        assertEquals(CLEAR, canvas.getPixel(2, 2));
        assertEquals(RED, canvas.getPixel(6, 6), "moved pixels overwrite the target");
        assertEquals(RED, canvas.getPixel(7, 7));
        assertEquals(new Rectangle(6, 6, 2, 2), selection.getActiveSelection().getBounds());

        command.undo();

        assertEquals(RED, canvas.getPixel(1, 1));
        assertEquals(GREEN, canvas.getPixel(6, 6));
        assertEquals(CLEAR, canvas.getPixel(7, 7));
        assertEquals(new Rectangle(1, 1, 2, 2), selection.getActiveSelection().getBounds());
    }

    @Test
    void consecutiveDragsAccumulateUntilCommitted() {
        move.onMouseDown(1, 1, 0, canvas, null);
        move.onMouseDrag(3, 1, 0, canvas, null); // +2, 0
        move.onMouseUp(0, canvas, null);

        // Second drag grabs the moved preview (now at x 3..4).
        move.onMouseDown(3, 1, 0, canvas, null);
        move.onMouseDrag(3, 4, 0, canvas, null); // +0, +3
        move.onMouseUp(0, canvas, null);

        assertEquals(2.0, move.getLiveTransform().translateX(), 1e-9);
        assertEquals(3.0, move.getLiveTransform().translateY(), 1e-9);

        move.getPendingCommand().execute();
        assertEquals(RED, canvas.getPixel(3, 4));
        assertEquals(RED, canvas.getPixel(4, 5));
        assertEquals(CLEAR, canvas.getPixel(1, 1));
    }

    @Test
    void shiftLocksTheDragToTheDominantAxis() {
        move.setModifierKeys(true);

        move.onMouseDown(1, 1, 0, canvas, null);
        move.onMouseDrag(5, 2, 0, canvas, null);

        assertEquals(4.0, move.getLiveTransform().translateX(), 1e-9);
        assertEquals(0.0, move.getLiveTransform().translateY(), 1e-9);
    }

    @Test
    void pressingOutsideTheSelectionDoesNotStartADrag() {
        move.onMouseDown(8, 8, 0, canvas, null);
        move.onMouseDrag(9, 9, 0, canvas, null);
        move.onMouseUp(0, canvas, null);

        assertFalse(move.isActive());
        assertNull(move.getPendingCommand());
    }

    @Test
    void noSelectionMeansNoGesture() {
        selection.clearSelection();

        move.onMouseDown(1, 1, 0, canvas, null);
        move.onMouseDrag(5, 5, 0, canvas, null);
        move.onMouseUp(0, canvas, null);

        assertFalse(move.isActive());
        assertNull(move.getPendingCommand());
    }

    @Test
    void draggingBackToTheStartLeavesNothingToCommit() {
        move.onMouseDown(1, 1, 0, canvas, null);
        move.onMouseDrag(4, 4, 0, canvas, null);
        move.onMouseDrag(1, 1, 0, canvas, null);
        move.onMouseUp(0, canvas, null);

        assertTrue(move.getLiveTransform().isIdentity());
        assertFalse(move.hasPreviewLayer());
        assertNull(move.getPendingCommand());
    }

    @Test
    void cancelDropsTheSessionAndThePendingCommand() {
        move.onMouseDown(1, 1, 0, canvas, null);
        move.onMouseDrag(4, 4, 0, canvas, null);
        move.onMouseUp(0, canvas, null);
        assertNotNull(move.getPendingCommand());

        move.cancelAndReset(canvas);

        assertFalse(move.isActive());
        assertNull(move.getPendingCommand());
        assertTrue(move.getLiveTransform().isIdentity());
        assertEquals(RED, canvas.getPixel(1, 1), "cancel never touches the canvas");
    }

    @Test
    void changingTheSelectionAbandonsThePreview() {
        move.onMouseDown(1, 1, 0, canvas, null);
        move.onMouseDrag(4, 4, 0, canvas, null);
        assertTrue(move.hasPreviewLayer());

        selection.setActiveSelection(new RectangularSelection(5, 5, 6, 6));

        assertFalse(move.hasPreviewLayer());
        assertTrue(move.getLiveTransform().isIdentity());
    }

    @Test
    void pasteSessionsNeverCutAHole() {
        move.setupPasteSession(canvas, selection.getActiveSelection());

        move.onMouseDown(1, 1, 0, canvas, null);
        move.onMouseDrag(5, 1, 0, canvas, null);
        move.onMouseUp(0, canvas, null);
        move.getPendingCommand().execute();

        assertEquals(RED, canvas.getPixel(1, 1), "pasted content is added, the source stays");
        assertEquals(RED, canvas.getPixel(5, 1));
        assertEquals(RED, canvas.getPixel(6, 2));
    }
}
