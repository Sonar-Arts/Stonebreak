package com.openmason.main.systems.menus.textureCreator.tools;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorPreferences;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Shape tool: rectangle / ellipse / triangle in outline vs filled mode (the
 * mode comes from the preferences), corner order independence, and the
 * drag preview that must not leave pixels behind.
 */
class ShapeToolTest {

    private static final int RED = 0xFF0000FF;
    private static final int GREEN = 0xFF00FF00;
    private static final int CLEAR = 0x00000000;

    @Test
    void rectangleOutlineIsTheOnePixelBorder() {
        PixelCanvas canvas = drawShape(ShapeTool.ShapeType.RECTANGLE, false, 8, 8, 1, 1, 5, 4);

        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                boolean inBox = x >= 1 && x <= 5 && y >= 1 && y <= 4;
                boolean border = inBox && (x == 1 || x == 5 || y == 1 || y == 4);
                assertEquals(border ? RED : CLEAR, canvas.getPixel(x, y), "(" + x + "," + y + ")");
            }
        }
        assertEquals(14, count(canvas, RED));
    }

    @Test
    void filledRectangleCoversTheWholeBox() {
        PixelCanvas canvas = drawShape(ShapeTool.ShapeType.RECTANGLE, true, 8, 8, 1, 1, 5, 4);

        assertEquals(20, count(canvas, RED));
        assertEquals(20, canvas.getPixels().length - count(canvas, CLEAR));
    }

    @Test
    void rectangleCornerOrderDoesNotMatter() {
        PixelCanvas a = drawShape(ShapeTool.ShapeType.RECTANGLE, false, 8, 8, 1, 1, 6, 5);
        PixelCanvas b = drawShape(ShapeTool.ShapeType.RECTANGLE, false, 8, 8, 6, 5, 1, 1);
        PixelCanvas c = drawShape(ShapeTool.ShapeType.RECTANGLE, false, 8, 8, 6, 1, 1, 5);

        assertArrayEquals(a.getPixels(), b.getPixels());
        assertArrayEquals(a.getPixels(), c.getPixels());
    }

    @Test
    void withoutPreferencesShapesAreOutlines() {
        PixelCanvas canvas = new PixelCanvas(8, 8);
        ShapeTool tool = new ShapeTool();
        tool.setCurrentShape(ShapeTool.ShapeType.RECTANGLE);
        tool.onMouseDown(0, 0, RED, canvas, null);
        tool.onMouseDrag(4, 4, RED, canvas, null);
        tool.onMouseUp(RED, canvas, null);

        assertEquals(CLEAR, canvas.getPixel(2, 2));
        assertEquals(16, count(canvas, RED));
    }

    @Test
    void ellipseOutlineLeavesTheCentreEmptyAndFilledCoversIt() {
        PixelCanvas outline = drawShape(ShapeTool.ShapeType.ELLIPSE, false, 9, 9, 0, 0, 8, 8);
        PixelCanvas filled = drawShape(ShapeTool.ShapeType.ELLIPSE, true, 9, 9, 0, 0, 8, 8);

        assertEquals(CLEAR, outline.getPixel(4, 4));
        assertEquals(RED, outline.getPixel(8, 4), "the extreme points lie on the outline");
        assertEquals(RED, outline.getPixel(4, 0));

        assertEquals(RED, filled.getPixel(4, 4));
        assertEquals(RED, filled.getPixel(6, 4));
        assertEquals(CLEAR, filled.getPixel(0, 0), "bounding-box corners are outside the ellipse");
        assertEquals(CLEAR, filled.getPixel(8, 8));
    }

    @Test
    void ellipseIsSymmetricAboutItsCentre() {
        PixelCanvas canvas = drawShape(ShapeTool.ShapeType.ELLIPSE, false, 11, 9, 0, 0, 10, 8);

        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 11; x++) {
                assertEquals(canvas.getPixel(x, y), canvas.getPixel(10 - x, y), "mirror x at (" + x + "," + y + ")");
                assertEquals(canvas.getPixel(x, y), canvas.getPixel(x, 8 - y), "mirror y at (" + x + "," + y + ")");
            }
        }
    }

    @Test
    void zeroSizeEllipseIsASinglePixel() {
        PixelCanvas canvas = drawShape(ShapeTool.ShapeType.ELLIPSE, true, 5, 5, 2, 3, 2, 3);

        assertEquals(RED, canvas.getPixel(2, 3));
        assertEquals(1, canvas.getPixels().length - count(canvas, CLEAR));
    }

    @Test
    void triangleOutlineTracesTheEdgesAndFilledCoversTheInterior() {
        // Apex at the top-middle (4,0), base along y = 8.
        PixelCanvas outline = drawShape(ShapeTool.ShapeType.TRIANGLE, false, 9, 9, 0, 0, 8, 8);
        PixelCanvas filled = drawShape(ShapeTool.ShapeType.TRIANGLE, true, 9, 9, 0, 0, 8, 8);

        assertEquals(RED, outline.getPixel(4, 0), "apex");
        assertEquals(RED, outline.getPixel(4, 8), "base");
        assertEquals(RED, outline.getPixel(0, 8), "base corner");
        assertEquals(CLEAR, outline.getPixel(4, 5), "interior of an outline");

        assertEquals(RED, filled.getPixel(4, 5));
        assertEquals(CLEAR, filled.getPixel(0, 0), "outside the triangle");
        assertEquals(CLEAR, filled.getPixel(8, 0));
    }

    @Test
    void shrinkingTheDragRemovesTheLargerPreview() {
        PixelCanvas canvas = new PixelCanvas(10, 10);
        canvas.setPixel(9, 9, GREEN);
        ShapeTool tool = toolFor(ShapeTool.ShapeType.RECTANGLE, false);
        DrawCommand command = new DrawCommand(canvas, "Shape");

        tool.onMouseDown(0, 0, RED, canvas, command);
        tool.onMouseDrag(9, 9, RED, canvas, command);
        assertEquals(RED, canvas.getPixel(9, 9), "preview overwrites the corner");
        tool.onMouseDrag(2, 2, RED, canvas, command);

        assertEquals(GREEN, canvas.getPixel(9, 9));
        assertEquals(CLEAR, canvas.getPixel(5, 0));
        tool.onMouseUp(RED, canvas, command);

        assertEquals(8, count(canvas, RED)); // 3x3 outline
        assertEquals(GREEN, canvas.getPixel(9, 9));
    }

    @Test
    void undoRestoresTheCanvasExactly() {
        PixelCanvas canvas = new PixelCanvas(8, 8);
        canvas.setPixel(3, 3, GREEN);
        int[] before = canvas.getPixels().clone();
        ShapeTool tool = toolFor(ShapeTool.ShapeType.ELLIPSE, true);
        DrawCommand command = new DrawCommand(canvas, "Shape");

        tool.onMouseDown(0, 0, RED, canvas, command);
        tool.onMouseDrag(7, 6, RED, canvas, command);
        tool.onMouseUp(RED, canvas, command);
        assertNotEquals(GREEN, canvas.getPixel(3, 3));

        command.undo();
        assertArrayEquals(before, canvas.getPixels());
    }

    private static PixelCanvas drawShape(ShapeTool.ShapeType type, boolean filled, int w, int h,
                                         int x0, int y0, int x1, int y1) {
        PixelCanvas canvas = new PixelCanvas(w, h);
        ShapeTool tool = toolFor(type, filled);
        DrawCommand command = new DrawCommand(canvas, "Shape");
        tool.onMouseDown(x0, y0, RED, canvas, command);
        tool.onMouseDrag(x1, y1, RED, canvas, command);
        tool.onMouseUp(RED, canvas, command);
        return canvas;
    }

    private static ShapeTool toolFor(ShapeTool.ShapeType type, boolean filled) {
        ShapeTool tool = new ShapeTool();
        tool.setCurrentShape(type);
        tool.setPreferences(new FillModePreferences(filled));
        return tool;
    }

    /** Preferences fake: only the fill-mode flag is read by the shape tool. */
    private static final class FillModePreferences extends TextureCreatorPreferences {
        private final boolean filled;

        FillModePreferences(boolean filled) {
            super(null);
            this.filled = filled;
        }

        @Override
        public boolean isShapeToolFillMode() {
            return filled;
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
}
