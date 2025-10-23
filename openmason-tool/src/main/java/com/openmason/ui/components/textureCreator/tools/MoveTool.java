package com.openmason.ui.components.textureCreator.tools;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.commands.TranslateSelectionCommand;
import com.openmason.ui.components.textureCreator.selection.RectangularSelection;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;

/**
 * Move tool - translates (moves) active selections.
 * Only works when a selection is active. Detects clicks on/near selection border.
 *
 * Pattern: Similar to other tools - stores translation command internally,
 * CanvasPanel reads it and executes in command history.
 *
 * SOLID: Single responsibility - handles selection translation only
 * KISS: Simple border detection and drag movement
 *
 * @author Open Mason Team
 */
public class MoveTool implements DrawingTool {

    // Border detection threshold (pixels)
    private static final int BORDER_THRESHOLD = 5;

    // Translation state
    private RectangularSelection translatingSelection = null;
    private int translateStartX = -1;
    private int translateStartY = -1;
    private int translateCurrentX = -1;
    private int translateCurrentY = -1;
    private TranslateSelectionCommand translationCommand = null;

    // Flags for CanvasPanel to read
    private SelectionRegion updatedSelection = null;
    private boolean translationPerformed = false;
    private boolean isTranslating = false;

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Check if there's an active selection
        SelectionRegion activeSelection = canvas.getActiveSelection();
        if (activeSelection == null || activeSelection.isEmpty()) {
            // No selection - do nothing
            return;
        }

        // Check if clicked on or near the selection border
        if (activeSelection instanceof RectangularSelection && isNearBorder(x, y, (RectangularSelection) activeSelection)) {
            // Start translation
            isTranslating = true;
            translatingSelection = (RectangularSelection) activeSelection;
            translateStartX = x;
            translateStartY = y;
            translateCurrentX = x;
            translateCurrentY = y;
            translationPerformed = false;
            translationCommand = null;
            updatedSelection = null;
        }
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        if (isTranslating) {
            // Update translation position
            translateCurrentX = x;
            translateCurrentY = y;
        }
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        if (isTranslating && translatingSelection != null) {
            // Finalize translation
            int deltaX = translateCurrentX - translateStartX;
            int deltaY = translateCurrentY - translateStartY;

            if (deltaX != 0 || deltaY != 0) {
                // Create translation command
                translationCommand = new TranslateSelectionCommand(
                        canvas, translatingSelection, deltaX, deltaY
                );
                translationPerformed = true;

                // Update selection to new position
                updatedSelection = translatingSelection.translate(deltaX, deltaY);
            }

            isTranslating = false;
        }
    }

    /**
     * Check if a point is on or near the selection border.
     * Uses a threshold to make the border easier to click.
     *
     * @param x Mouse x-coordinate
     * @param y Mouse y-coordinate
     * @param selection The rectangular selection
     * @return true if point is near the border
     */
    private boolean isNearBorder(int x, int y, RectangularSelection selection) {
        int x1 = selection.getX1();
        int y1 = selection.getY1();
        int x2 = selection.getX2();
        int y2 = selection.getY2();

        // Check if point is within the outer boundary (selection + threshold)
        boolean inOuterBounds = x >= x1 - BORDER_THRESHOLD && x <= x2 + BORDER_THRESHOLD &&
                                y >= y1 - BORDER_THRESHOLD && y <= y2 + BORDER_THRESHOLD;

        if (!inOuterBounds) {
            return false; // Too far from selection
        }

        // Check if point is within the inner boundary (selection - threshold)
        boolean inInnerBounds = x >= x1 + BORDER_THRESHOLD && x <= x2 - BORDER_THRESHOLD &&
                                y >= y1 + BORDER_THRESHOLD && y <= y2 - BORDER_THRESHOLD;

        // If in outer bounds but not in inner bounds, we're on/near the border
        return !inInnerBounds;
    }

    /**
     * Get the translation preview bounds for rendering.
     * Used by CanvasPanel to render selection preview during drag.
     *
     * @return array [startX, startY, endX, endY] or null if not translating
     */
    public int[] getTranslationPreviewBounds() {
        if (isTranslating && translatingSelection != null) {
            int deltaX = translateCurrentX - translateStartX;
            int deltaY = translateCurrentY - translateStartY;
            return new int[]{
                    translatingSelection.getX1() + deltaX,
                    translatingSelection.getY1() + deltaY,
                    translatingSelection.getX2() + deltaX,
                    translatingSelection.getY2() + deltaY
            };
        }
        return null;
    }

    /**
     * Check if a translation was performed.
     *
     * @return true if translation command is ready
     */
    public boolean wasTranslationPerformed() {
        return translationPerformed;
    }

    /**
     * Get the translation command.
     * CanvasPanel should read this and execute it in command history.
     *
     * @return The translation command, or null
     */
    public TranslateSelectionCommand getTranslationCommand() {
        return translationCommand;
    }

    /**
     * Get the updated selection after translation.
     *
     * @return The updated selection region, or null
     */
    public SelectionRegion getUpdatedSelection() {
        return updatedSelection;
    }

    /**
     * Clear the translation performed flag.
     * Should be called by CanvasPanel after reading the command.
     */
    public void clearTranslationPerformedFlag() {
        translationPerformed = false;
        translationCommand = null;
        updatedSelection = null;
    }

    /**
     * Check if currently translating.
     *
     * @return true if actively moving selection
     */
    public boolean isTranslating() {
        return isTranslating;
    }

    @Override
    public void reset() {
        isTranslating = false;
        translatingSelection = null;
        translateStartX = -1;
        translateStartY = -1;
        translateCurrentX = -1;
        translateCurrentY = -1;
        translationCommand = null;
        updatedSelection = null;
        translationPerformed = false;
    }

    @Override
    public String getName() {
        return "Move";
    }

    @Override
    public String getDescription() {
        return "Move selected region";
    }
}
