package com.openmason.main.systems.menus.textureCreator.tools;

import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;
import com.openmason.main.systems.menus.textureCreator.tools.selection.AbstractSelectionTool;
import com.openmason.main.systems.menus.textureCreator.tools.selection.RectanglePreview;
import com.openmason.main.systems.menus.textureCreator.tools.selection.SelectionPreview;

/**
 * Rectangle selection tool - creates rectangular selection regions.
 */
public class RectangleSelectionTool extends AbstractSelectionTool {

    @Override
    protected SelectionRegion createSelectionFromDrag(int startX, int startY, int endX, int endY) {
        // Create rectangular selection from drag bounds
        return new RectangularSelection(startX, startY, endX, endY);
    }

    @Override
    protected SelectionPreview createPreview(int startX, int startY, int endX, int endY) {
        // Create rectangle preview for rendering during drag
        return new RectanglePreview(startX, startY, endX, endY);
    }

    @Override
    protected String getToolName() {
        return "Rectangle Selection";
    }

    @Override
    protected String getToolDescription() {
        return "Click and drag to select rectangular regions";
    }
}
