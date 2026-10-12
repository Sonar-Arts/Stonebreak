package com.openmason.main.systems.menus.textureCreator.tools;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Line tool rasterisation (axis-aligned exactness, full-strength endpoints,
 * direction independence, connectivity) and its live preview, which must
 * never leave pixels behind or leak into the undo record.
 */
class LineToolTest {

    private static final int RED = 0xFF0000FF;
    private static final int GREEN = 0xFF00FF00;
    private static final int CLEAR = 0x00000000;

    @Test
    void horizontalLineCoversExactlyTheSpan() {
        PixelCanvas canvas = draw(10, 5, 1, 2, 7, 2);

        for (int x = 0; x < 10; x++) {
            assertEquals(x >= 1 && x <= 7 ? RED : CLEAR, canvas.getPixel(x, 2), "x=" + x);
        }
        assertEquals(7, count(canvas, RED));
        assertEquals(7, countNot(canvas, CLEAR), "an axis-aligned line has no soft edge pixels");
    }

    @Test
    void verticalLineCoversExactlyTheSpan() {
        PixelCanvas canvas = draw(5, 10, 3, 8, 3, 1); // drawn bottom-up

        for (int y = 0; y < 10; y++) {
            assertEquals(y >= 1 && y <= 8 ? RED : CLEAR, canvas.getPixel(3, y), "y=" + y);
        }
        assertEquals(8, countNot(canvas, CLEAR));
    }

    @Test
    void endpointsAreAlwaysFullStrength() {
        int[][] lines = {{0, 0, 9, 3}, {1, 8, 6, 0}, {9, 9, 0, 4}, {2, 2, 3, 9}};
        for (int[] l : lines) {
            PixelCanvas canvas = draw(10, 10, l[0], l[1], l[2], l[3]);
            assertEquals(RED, canvas.getPixel(l[0], l[1]), "start of " + java.util.Arrays.toString(l));
            assertEquals(RED, canvas.getPixel(l[2], l[3]), "end of " + java.util.Arrays.toString(l));
        }
    }

    @Test
    void lineDirectionDoesNotChangeTheResult() {
        PixelCanvas forward = draw(12, 12, 1, 2, 10, 7);
        PixelCanvas backward = draw(12, 12, 10, 7, 1, 2);

        assertArrayEquals(forward.getPixels(), backward.getPixels());
    }

    @Test
    void slopedLineIsConnectedFromStartToEnd() {
        PixelCanvas canvas = draw(16, 16, 0, 0, 15, 6);

        boolean[][] reached = new boolean[16][16];
        floodPainted(canvas, 0, 0, reached);
        assertTrue(reached[15][6], "painted line pixels must form a connected path");
    }

    @Test
    void singleClickDrawsOnePixel() {
        PixelCanvas canvas = new PixelCanvas(4, 4);
        DrawCommand command = new DrawCommand(canvas, "Line");
        LineTool line = new LineTool();

        line.onMouseDown(2, 1, RED, canvas, command);
        line.onMouseUp(RED, canvas, command);

        assertEquals(RED, canvas.getPixel(2, 1));
        assertEquals(1, countNot(canvas, CLEAR));
        assertTrue(command.hasChanges());
    }

    @Test
    void previewIsReplacedAsTheMouseMovesAndRestoresUnderlyingPixels() {
        PixelCanvas canvas = new PixelCanvas(8, 8);
        canvas.setPixel(4, 0, GREEN); // existing artwork under the first preview
        LineTool line = new LineTool();

        line.onMouseDown(0, 0, RED, canvas, null);
        line.onMouseDrag(7, 0, RED, canvas, null);
        assertEquals(RED, canvas.getPixel(4, 0), "preview is drawn onto the canvas");

        line.onMouseDrag(0, 7, RED, canvas, null);

        assertEquals(GREEN, canvas.getPixel(4, 0), "the old preview must be undone");
        assertEquals(CLEAR, canvas.getPixel(7, 0));
        for (int y = 0; y < 8; y++) {
            assertEquals(RED, canvas.getPixel(0, y));
        }
    }

    @Test
    void commitRecordsOnlyTheFinalLineAndUndoRestoresTheOriginal() {
        PixelCanvas canvas = new PixelCanvas(8, 8);
        canvas.setPixel(4, 0, GREEN);
        int[] before = canvas.getPixels().clone();
        DrawCommand command = new DrawCommand(canvas, "Line");
        LineTool line = new LineTool();

        line.onMouseDown(0, 0, RED, canvas, command);
        line.onMouseDrag(7, 0, RED, canvas, command);
        line.onMouseDrag(0, 7, RED, canvas, command);
        line.onMouseUp(RED, canvas, command);

        assertEquals(GREEN, canvas.getPixel(4, 0));
        assertEquals(8, count(canvas, RED));
        assertTrue(command.getDescription().contains("(8 pixels)"), command.getDescription());

        command.undo();
        assertArrayEquals(before, canvas.getPixels());
    }

    @Test
    void releaseWithoutPressDoesNothing() {
        PixelCanvas canvas = new PixelCanvas(4, 4);
        DrawCommand command = new DrawCommand(canvas, "Line");

        new LineTool().onMouseUp(RED, canvas, command);

        assertFalse(command.hasChanges());
        assertEquals(0, countNot(canvas, CLEAR));
    }

    private static PixelCanvas draw(int w, int h, int x0, int y0, int x1, int y1) {
        PixelCanvas canvas = new PixelCanvas(w, h);
        DrawCommand command = new DrawCommand(canvas, "Line");
        LineTool line = new LineTool();
        line.onMouseDown(x0, y0, RED, canvas, command);
        line.onMouseDrag(x1, y1, RED, canvas, command);
        line.onMouseUp(RED, canvas, command);
        return canvas;
    }

    /** 8-connected walk over painted (non-transparent) pixels. */
    private static void floodPainted(PixelCanvas canvas, int x, int y, boolean[][] reached) {
        if (!canvas.isValidCoordinate(x, y) || reached[x][y] || canvas.getPixel(x, y) == CLEAR) {
            return;
        }
        reached[x][y] = true;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 || dy != 0) {
                    floodPainted(canvas, x + dx, y + dy, reached);
                }
            }
        }
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
