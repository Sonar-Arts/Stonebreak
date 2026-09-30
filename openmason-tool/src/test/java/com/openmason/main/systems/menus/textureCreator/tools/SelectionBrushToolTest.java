package com.openmason.main.systems.menus.textureCreator.tools;

import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionManager;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Right-click deselect on the Selection Brush (#315): a forced-subtract stroke
 * removes exactly the pixels under the brush from the active selection.
 */
class SelectionBrushToolTest {

    private SelectionManager selectionManager;
    private SelectionBrushTool brush;

    @BeforeEach
    void setUp() {
        selectionManager = new SelectionManager();
        brush = new SelectionBrushTool();
        brush.setSelectionManager(selectionManager);
    }

    @Test
    void subtractClickWithSizeOneRemovesExactlyOnePixel() {
        selectionManager.setActiveSelection(new RectangularSelection(0, 0, 3, 3)); // 4x4 = 16 px

        brush.useSubtractStroke();
        SelectionRegion result = stroke(1, 1);

        assertFalse(result.contains(1, 1));
        assertEquals(15, countPixels(result, 4, 4));
    }

    @Test
    void subtractDragRemovesEveryPixelAlongThePath() {
        selectionManager.setActiveSelection(new RectangularSelection(0, 0, 3, 3));

        brush.useSubtractStroke();
        brush.onMouseDown(0, 2, 0, null, null);
        brush.onMouseDrag(3, 2, 0, null, null);
        brush.onMouseUp(0, null, null);
        SelectionRegion result = brush.getSelection();

        for (int x = 0; x < 4; x++) {
            assertFalse(result.contains(x, 2), "row 2 should be deselected at x=" + x);
        }
        assertEquals(12, countPixels(result, 4, 4));
    }

    @Test
    void subtractingTheLastPixelClearsTheSelection() {
        selectionManager.setActiveSelection(new RectangularSelection(2, 2, 2, 2));

        brush.useSubtractStroke();
        brush.onMouseDown(2, 2, 0, null, null);
        brush.onMouseUp(0, null, null);

        assertTrue(brush.hasSelection(), "the stroke must report a change so the canvas applies it");
        assertNull(brush.getSelection());
    }

    @Test
    void subtractOutsideTheSelectionLeavesItUnchanged() {
        selectionManager.setActiveSelection(new RectangularSelection(0, 0, 1, 1));

        brush.useSubtractStroke();
        SelectionRegion result = stroke(5, 5);

        assertEquals(4, countPixels(result, 6, 6));
    }

    @Test
    void modifierStateAfterASubtractStrokeRestoresAdd() {
        selectionManager.setActiveSelection(new RectangularSelection(0, 0, 0, 0));

        brush.useSubtractStroke();
        selectionManager.setActiveSelection(stroke(0, 0));
        assertFalse(selectionManager.hasActiveSelection());

        brush.updateModifierState(false, false);
        SelectionRegion result = stroke(1, 1);

        assertTrue(result.contains(1, 1), "a plain left stroke after a right-click must add again");
    }

    private SelectionRegion stroke(int x, int y) {
        brush.onMouseDown(x, y, 0, null, null);
        brush.onMouseUp(0, null, null);
        return brush.getSelection();
    }

    private static int countPixels(SelectionRegion region, int width, int height) {
        int count = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (region.contains(x, y)) {
                    count++;
                }
            }
        }
        return count;
    }
}
