package com.openmason.ui.textureCreator.tools;

import com.openmason.ui.textureCreator.selection.RectangularSelection;
import com.openmason.ui.textureCreator.selection.SelectionRegion;
import com.openmason.ui.textureCreator.tools.selection.AbstractSelectionTool;
import com.openmason.ui.textureCreator.tools.selection.RectanglePreview;
import com.openmason.ui.textureCreator.tools.selection.SelectionPreview;

/**
 * Rectangle selection tool - creates rectangular selection regions.
 * Click and drag to create a new rectangular selection.
 *
 * REFACTORED: Now extends AbstractSelectionTool for common selection logic.
 * Implements only rectangle-specific behavior.
 *
 * Architecture:
 * - Base: AbstractSelectionTool (handles common selection state and lifecycle)
 * - This: Rectangle-specific selection creation and preview
 *
 * SOLID Principles:
 * - Single Responsibility: Handles only rectangular selection creation
 * - Open/Closed: Extensible via AbstractSelectionTool template methods
 * - Liskov Substitution: Can be used anywhere SelectionTool is expected
 *
 * KISS: Simple, focused on rectangle logic only
 * DRY: Reuses common selection logic from AbstractSelectionTool
 * YAGNI: No unnecessary features like unused aspect ratio constraints
 *
 * @author Open Mason Team
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
