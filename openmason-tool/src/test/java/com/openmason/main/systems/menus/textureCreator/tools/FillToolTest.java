package com.openmason.main.systems.menus.textureCreator.tools;

import com.openmason.main.systems.menus.textureCreator.canvas.CanvasShapeMask;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Flood fill: 4-connected contiguity, exact-color matching (the fill has no
 * tolerance), selection and shape-mask boundaries, and undo.
 */
class FillToolTest {

    private static final int RED = 0xFF0000FF;
    private static final int BLUE = 0xFFFF0000;
    private static final int WHITE = 0xFFFFFFFF;

    @Test
    void fillsTheWholeContiguousRegionAndStopsAtWalls() {
        // 7x5 canvas with a vertical wall at x = 3.
        PixelCanvas canvas = new PixelCanvas(7, 5);
        canvas.fill(WHITE);
        for (int y = 0; y < 5; y++) {
            canvas.setPixel(3, y, BLUE);
        }

        new FillTool().onMouseDown(0, 0, RED, canvas, null);

        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 7; x++) {
                int expected = x < 3 ? RED : (x == 3 ? BLUE : WHITE);
                assertEquals(expected, canvas.getPixel(x, y), "(" + x + "," + y + ")");
            }
        }
    }

    @Test
    void diagonalNeighboursAreNotConnected() {
        // Checkerboard: from (0,0) only the start pixel matches with an edge path.
        PixelCanvas canvas = new PixelCanvas(4, 4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                canvas.setPixel(x, y, ((x + y) & 1) == 0 ? WHITE : BLUE);
            }
        }

        new FillTool().onMouseDown(0, 0, RED, canvas, null);

        assertEquals(RED, canvas.getPixel(0, 0));
        assertEquals(WHITE, canvas.getPixel(1, 1), "a diagonal neighbour must not be filled");
        assertEquals(1, count(canvas, RED));
    }

    @Test
    void matchingIsExactSoANearlyEqualColorIsABoundary() {
        PixelCanvas canvas = new PixelCanvas(4, 1);
        canvas.fill(WHITE);
        int almostWhite = 0xFFFFFFFE;
        canvas.setPixel(2, 0, almostWhite);

        new FillTool().onMouseDown(0, 0, RED, canvas, null);

        assertEquals(RED, canvas.getPixel(0, 0));
        assertEquals(RED, canvas.getPixel(1, 0));
        assertEquals(almostWhite, canvas.getPixel(2, 0));
        assertEquals(WHITE, canvas.getPixel(3, 0));
    }

    @Test
    void alphaIsPartOfTheMatch() {
        PixelCanvas canvas = new PixelCanvas(3, 1);
        canvas.setPixel(1, 0, 0x00FFFFFF); // transparent white differs from transparent black

        new FillTool().onMouseDown(0, 0, RED, canvas, null);

        assertEquals(RED, canvas.getPixel(0, 0));
        assertEquals(0x00FFFFFF, canvas.getPixel(1, 0));
        assertEquals(0x00000000, canvas.getPixel(2, 0));
    }

    @Test
    void fillingWithTheSameColorRecordsNothing() {
        PixelCanvas canvas = new PixelCanvas(4, 4);
        canvas.fill(RED);
        DrawCommand command = new DrawCommand(canvas, "Fill");

        new FillTool().onMouseDown(1, 1, RED, canvas, command);

        assertFalse(command.hasChanges());
    }

    @Test
    void fillStaysInsideTheActiveSelection() {
        PixelCanvas canvas = new PixelCanvas(6, 6);
        canvas.fill(WHITE);
        SelectionManager selection = new SelectionManager();
        selection.setActiveSelection(new RectangularSelection(1, 1, 3, 2));
        canvas.setSelectionManager(selection);

        new FillTool().onMouseDown(2, 2, RED, canvas, null);

        assertEquals(6, count(canvas, RED)); // 3x2 selection
        assertEquals(RED, canvas.getPixel(1, 1));
        assertEquals(RED, canvas.getPixel(3, 2));
        assertEquals(WHITE, canvas.getPixel(0, 0));
        assertEquals(WHITE, canvas.getPixel(4, 2));
    }

    @Test
    void shapeMaskBlocksTheFloodLikeAWall() {
        PixelCanvas canvas = new PixelCanvas(5, 1);
        canvas.fill(WHITE);
        canvas.setShapeMask(onlyEditableWhere(5, 1, (x, y) -> x != 2));

        new FillTool().onMouseDown(0, 0, RED, canvas, null);

        assertEquals(RED, canvas.getPixel(0, 0));
        assertEquals(RED, canvas.getPixel(1, 0));
        assertEquals(WHITE, canvas.getPixel(2, 0));
        assertEquals(WHITE, canvas.getPixel(3, 0), "the region past the masked pixel is not reached");
    }

    @Test
    void undoRestoresEveryFilledPixel() {
        PixelCanvas canvas = new PixelCanvas(8, 8);
        canvas.fill(WHITE);
        DrawCommand command = new DrawCommand(canvas, "Fill");

        new FillTool().onMouseDown(4, 4, RED, canvas, command);
        assertEquals(64, count(canvas, RED));

        command.undo();
        assertEquals(64, count(canvas, WHITE));
    }

    @Test
    void dragAndReleaseDoNothing() {
        PixelCanvas canvas = new PixelCanvas(3, 3);
        FillTool fill = new FillTool();

        fill.onMouseDrag(1, 1, RED, canvas, null);
        fill.onMouseUp(RED, canvas, null);

        assertEquals(0, count(canvas, RED));
    }

    private interface Editable {
        boolean at(int x, int y);
    }

    private static CanvasShapeMask onlyEditableWhere(int width, int height, Editable editable) {
        return new CanvasShapeMask() {
            @Override
            public boolean isEditable(int x, int y) {
                return editable.at(x, y);
            }

            @Override
            public int getWidth() {
                return width;
            }

            @Override
            public int getHeight() {
                return height;
            }
        };
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
}
