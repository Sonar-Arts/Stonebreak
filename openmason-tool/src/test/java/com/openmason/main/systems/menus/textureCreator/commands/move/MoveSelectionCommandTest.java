package com.openmason.main.systems.menus.textureCreator.commands.move;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionManager;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;
import com.openmason.main.systems.menus.textureCreator.tools.move.SelectionSnapshot;
import com.openmason.main.systems.menus.textureCreator.tools.move.TransformationState;
import com.openmason.main.systems.menus.textureCreator.tools.move.TransformedImage;
import com.openmason.main.systems.menus.textureCreator.tools.move.TransformedSelectionRegion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Committing a move/transform of the selection: source clearing, destination
 * write, selection hand-off and exact undo (pure translations built by hand,
 * so no transform maths is involved).
 */
class MoveSelectionCommandTest {

    private static final int RED = PixelCanvas.packRGBA(255, 0, 0, 255);

    private PixelCanvas canvas;
    private SelectionManager selections;
    private int[] original;

    @BeforeEach
    void setUp() {
        canvas = new PixelCanvas(8, 8);
        for (int i = 0; i < canvas.getPixels().length; i++) {
            canvas.getPixels()[i] = PixelCanvas.packRGBA(i, 255 - i, i * 3 % 256, 255);
        }
        original = canvas.getPixels().clone();
        selections = new SelectionManager();
        canvas.setSelectionManager(selections);
    }

    /** Builds the command for translating {@code selection} by (dx, dy). */
    private MoveSelectionCommand translate(SelectionRegion selection, int dx, int dy, boolean paste) {
        selections.setActiveSelection(selection);
        SelectionSnapshot snapshot = SelectionSnapshot.capture(canvas, selection);
        Rectangle src = snapshot.bounds();
        Rectangle dst = new Rectangle(src.x + dx, src.y + dy, src.width, src.height);
        boolean[] mask = snapshot.mask().clone();
        int count = 0;
        for (boolean b : mask) if (b) count++;
        TransformedImage image = new TransformedImage(dst, snapshot.pixels().clone(), mask, count);
        return MoveSelectionCommand.create(canvas, selections, selection, snapshot,
                new TransformationState(dx, dy, 1, 1, 0), image,
                TransformedSelectionRegion.fromImage(image), false, paste);
    }

    @Test
    @Tag("regression")
    void moveClearsSourceWritesDestinationAndUndoesExactly() {
        RectangularSelection selection = new RectangularSelection(1, 1, 2, 2);
        int movedColor = canvas.getPixel(1, 1);
        MoveSelectionCommand move = translate(selection, 4, 3, false);
        assertNotNull(move);
        assertTrue(move.hasChanges());

        move.execute();
        assertEquals(0, canvas.getPixel(1, 1), "source is cleared");
        assertEquals(movedColor, canvas.getPixel(5, 4), "pixel lands at its translated position");
        assertSame(move.getTransformedSelection(), selections.getActiveSelection());

        move.undo();
        assertArrayEquals(original, canvas.getPixels());
        assertSame(selection, selections.getActiveSelection());

        move.execute(); // redo
        assertEquals(movedColor, canvas.getPixel(5, 4));
        move.undo();
        assertArrayEquals(original, canvas.getPixels());
    }

    @Test
    @Tag("regression")
    void overlappingMoveUndoesExactly() {
        // Moving by one pixel overlaps source and destination.
        MoveSelectionCommand move = translate(new RectangularSelection(2, 2, 5, 5), 1, 1, false);
        int corner = canvas.getPixel(2, 2);
        move.execute();
        assertEquals(corner, canvas.getPixel(3, 3));
        assertEquals(0, canvas.getPixel(2, 2));
        move.undo();
        assertArrayEquals(original, canvas.getPixels());
    }

    @Test
    void writesOutsideTheOldSelectionAreAllowedOnlyDuringTheCommand() {
        MoveSelectionCommand move = translate(new RectangularSelection(0, 0, 1, 1), 5, 5, false);
        move.execute();
        // Destination is outside the ORIGINAL selection, yet it was written.
        assertEquals(original[0], canvas.getPixel(5, 5));

        // The bypass is switched off again: plain writes respect the selection.
        selections.setActiveSelection(new RectangularSelection(0, 0, 0, 0));
        canvas.setPixel(7, 7, RED);
        assertEquals(original[7 * 8 + 7], canvas.getPixel(7, 7));
    }

    @Test
    @Tag("regression")
    void pasteModeLeavesTheSourceAloneAndUndoesExactly() {
        MoveSelectionCommand paste = translate(new RectangularSelection(0, 0, 1, 1), 6, 6, true);
        paste.execute();
        assertEquals(original[0], canvas.getPixel(0, 0), "paste never clears a source");
        assertEquals(original[0], canvas.getPixel(6, 6));
        paste.undo();
        assertArrayEquals(original, canvas.getPixels());
    }

    @Test
    void identityTransformProducesNoCommand() {
        RectangularSelection selection = new RectangularSelection(0, 0, 1, 1);
        SelectionSnapshot snapshot = SelectionSnapshot.capture(canvas, selection);
        boolean[] mask = new boolean[4];
        Arrays.fill(mask, true);
        TransformedImage image = new TransformedImage(snapshot.bounds(), snapshot.pixels(), mask, 4);
        assertNull(MoveSelectionCommand.create(canvas, selections, selection, snapshot,
                TransformationState.identity(), image, TransformedSelectionRegion.fromImage(image), false, false));
    }
}
