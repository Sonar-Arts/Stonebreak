package com.openmason.main.systems.menus.textureCreator.tools;

import com.openmason.main.systems.menus.textureCreator.SymmetryState;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pencil and eraser strokes on a headless {@link PixelCanvas}: which pixels a
 * click/drag touches, undo through the recorded {@link DrawCommand}, the
 * selection constraint and mirror (symmetry) strokes.
 */
class PencilEraserToolTest {

    private static final int RED = 0xFF0000FF;   // packed ABGR: A=FF, R=FF
    private static final int BLUE = 0xFFFF0000;
    private static final int CLEAR = 0x00000000;

    @Test
    void sizeOneClickPaintsExactlyOnePixelAndIsUndoable() {
        PixelCanvas canvas = new PixelCanvas(8, 8);
        DrawCommand command = new DrawCommand(canvas, "Pencil");
        PencilTool pencil = new PencilTool();

        pencil.onMouseDown(3, 4, RED, canvas, command);
        pencil.onMouseUp(RED, canvas, command);

        assertEquals(RED, canvas.getPixel(3, 4));
        assertEquals(1, countNot(canvas, CLEAR));
        assertTrue(command.hasChanges());

        command.undo();
        assertEquals(0, countNot(canvas, CLEAR));
        command.execute();
        assertEquals(RED, canvas.getPixel(3, 4));
    }

    @Test
    void dragFillsEveryPixelBetweenSamplesWithoutGaps() {
        PixelCanvas canvas = new PixelCanvas(16, 16);
        PencilTool pencil = new PencilTool();

        pencil.onMouseDown(1, 1, RED, canvas, null);
        pencil.onMouseDrag(9, 5, RED, canvas, null); // fast mouse: one event far away
        pencil.onMouseUp(RED, canvas, null);

        // A connected Bresenham run: 9 columns, each painted at least once.
        for (int x = 1; x <= 9; x++) {
            boolean painted = false;
            for (int y = 0; y < 16; y++) {
                painted |= canvas.getPixel(x, y) == RED;
            }
            assertTrue(painted, "column " + x + " has a gap");
        }
        assertEquals(RED, canvas.getPixel(1, 1));
        assertEquals(RED, canvas.getPixel(9, 5));
        assertEquals(9, countNot(canvas, CLEAR), "a 1px Bresenham line touches one pixel per major-axis step");
    }

    @Test
    void largeBrushPaintsASolidSymmetricDiscWithSoftEdges() {
        PixelCanvas canvas = new PixelCanvas(15, 15);
        PencilTool pencil = new PencilTool();
        pencil.setBrushSize(5);

        pencil.onMouseDown(7, 7, RED, canvas, null);

        // Radius 2: every pixel within distance 1.5 is full coverage.
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                assertEquals(RED, canvas.getPixel(7 + dx, 7 + dy));
            }
        }
        // Far corner of the bounding box is untouched.
        assertEquals(CLEAR, canvas.getPixel(4, 4));
        assertEquals(CLEAR, canvas.getPixel(10, 10));
        // Edge pixels are blended, and the disc is 4-way symmetric.
        int edge = canvas.getPixel(9, 7);
        assertNotEquals(CLEAR, edge);
        assertNotEquals(RED, edge);
        assertEquals(edge, canvas.getPixel(5, 7));
        assertEquals(edge, canvas.getPixel(7, 9));
        assertEquals(edge, canvas.getPixel(7, 5));
    }

    @Test
    void brushSizeIsClampedToOneThroughTwenty() {
        PencilTool pencil = new PencilTool();
        pencil.setBrushSize(0);
        assertEquals(1, pencil.getBrushSize());
        pencil.setBrushSize(99);
        assertEquals(20, pencil.getBrushSize());

        EraserTool eraser = new EraserTool();
        eraser.setBrushSize(-4);
        assertEquals(1, eraser.getBrushSize());
        eraser.setBrushSize(21);
        assertEquals(20, eraser.getBrushSize());
    }

    @Test
    void eraserClearsToFullyTransparentWhateverColorIsPassed() {
        PixelCanvas canvas = new PixelCanvas(6, 6);
        canvas.fill(BLUE);
        DrawCommand command = new DrawCommand(canvas, "Erase");
        EraserTool eraser = new EraserTool();

        eraser.onMouseDown(0, 2, RED, canvas, command);
        eraser.onMouseDrag(5, 2, RED, canvas, command);
        eraser.onMouseUp(RED, canvas, command);

        for (int x = 0; x < 6; x++) {
            assertEquals(CLEAR, canvas.getPixel(x, 2), "row 2 should be erased at x=" + x);
        }
        assertEquals(30, count(canvas, BLUE));

        command.undo();
        assertEquals(36, count(canvas, BLUE));
    }

    @Test
    void largeEraserClearsItsFullCoverageDisc() {
        PixelCanvas canvas = new PixelCanvas(15, 15);
        canvas.fill(BLUE);
        EraserTool eraser = new EraserTool();
        eraser.setBrushSize(5);

        eraser.onMouseDown(7, 7, 0, canvas, null);

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                assertEquals(CLEAR, canvas.getPixel(7 + dx, 7 + dy));
            }
        }
        assertEquals(BLUE, canvas.getPixel(4, 4));
    }

    @Test
    void strokesStayInsideTheActiveSelection() {
        PixelCanvas canvas = new PixelCanvas(8, 8);
        SelectionManager selection = new SelectionManager();
        selection.setActiveSelection(new RectangularSelection(2, 0, 4, 7));
        canvas.setSelectionManager(selection);
        PencilTool pencil = new PencilTool();

        pencil.onMouseDown(0, 3, RED, canvas, null);
        pencil.onMouseDrag(7, 3, RED, canvas, null);

        for (int x = 0; x < 8; x++) {
            int expected = (x >= 2 && x <= 4) ? RED : CLEAR;
            assertEquals(expected, canvas.getPixel(x, 3), "x=" + x);
        }
    }

    @Test
    void outOfCanvasStrokesAreIgnored() {
        PixelCanvas canvas = new PixelCanvas(4, 4);
        DrawCommand command = new DrawCommand(canvas, "Pencil");
        PencilTool pencil = new PencilTool();

        pencil.onMouseDown(-3, 10, RED, canvas, command);

        assertFalse(command.hasChanges());
        assertEquals(0, countNot(canvas, CLEAR));
    }

    @Test
    void verticalSymmetryMirrorsAClickAcrossTheCentre() {
        PixelCanvas canvas = new PixelCanvas(8, 4);
        SymmetryState symmetry = new SymmetryState();
        symmetry.setMode(SymmetryState.SymmetryMode.VERTICAL);
        PencilTool pencil = new PencilTool();
        pencil.setSymmetryState(symmetry);

        pencil.onMouseDown(1, 2, RED, canvas, null);

        assertEquals(RED, canvas.getPixel(1, 2));
        assertEquals(RED, canvas.getPixel(6, 2)); // axis at x = 3.5
        assertEquals(2, countNot(canvas, CLEAR));
    }

    @Test
    void symmetryIsSkippedForToolsThatOptOut() {
        PixelCanvas canvas = new PixelCanvas(8, 4);
        SymmetryState symmetry = new SymmetryState();
        symmetry.setMode(SymmetryState.SymmetryMode.VERTICAL);
        symmetry.setEnabledForTool("PencilTool", false);
        PencilTool pencil = new PencilTool();
        pencil.setSymmetryState(symmetry);

        pencil.onMouseDown(1, 2, RED, canvas, null);

        assertEquals(1, countNot(canvas, CLEAR));
    }

    /**
     * A drag that starts on the mirror axis used to lose its mirrored copy: the
     * per-point mirror lists drop on-axis points, so the start list had no entry
     * to pair with the end's mirror and nothing was drawn on the other side.
     */
    @Test
    @Tag("regression")
    void dragStartingOnTheAxisIsStillMirrored() {
        PixelCanvas canvas = new PixelCanvas(5, 3); // vertical axis at x = 2
        SymmetryState symmetry = new SymmetryState();
        symmetry.setMode(SymmetryState.SymmetryMode.VERTICAL);
        PencilTool pencil = new PencilTool();
        pencil.setSymmetryState(symmetry);

        pencil.onMouseDown(2, 1, RED, canvas, null);
        pencil.onMouseDrag(4, 1, RED, canvas, null);

        for (int x = 0; x < 5; x++) {
            assertEquals(RED, canvas.getPixel(x, 1), "row 1 should be painted at x=" + x);
        }
        assertEquals(5, countNot(canvas, CLEAR));
    }

    /**
     * In quadrant mode a drag starting on the vertical axis paired the start's
     * horizontal-axis mirror with the end's vertical-axis mirror, drawing a
     * stray diagonal across the canvas.
     */
    @Test
    @Tag("regression")
    void quadrantDragFromTheAxisDrawsOnlyTheFourMirrorImages() {
        PixelCanvas canvas = new PixelCanvas(9, 9); // axes at x = 4, y = 4
        SymmetryState symmetry = new SymmetryState();
        symmetry.setMode(SymmetryState.SymmetryMode.QUADRANT);
        PencilTool pencil = new PencilTool();
        pencil.setSymmetryState(symmetry);

        pencil.onMouseDown(4, 1, RED, canvas, null);
        pencil.onMouseDrag(7, 1, RED, canvas, null);

        for (int x = 1; x <= 7; x++) {
            assertEquals(RED, canvas.getPixel(x, 1), "top row x=" + x);
            assertEquals(RED, canvas.getPixel(x, 7), "bottom row x=" + x);
        }
        assertEquals(14, countNot(canvas, CLEAR), "nothing may be painted off the two mirrored rows");
    }

    private static int count(PixelCanvas canvas, int color) {
        int n = 0;
        for (int p : canvas.getPixels()) {
            if (p == color) {
                n++;
            }
        }
        return n;
    }

    private static int countNot(PixelCanvas canvas, int color) {
        return canvas.getPixels().length - count(canvas, color);
    }
}
