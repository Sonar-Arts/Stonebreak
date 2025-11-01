package com.openmason.ui.components.textureCreator.tools;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;

/**
 * Drawing tool interface following Strategy pattern.
 *
 * Each tool implements its own drawing behavior.
 * Follows SOLID principles - Open/Closed: extensible for new tools without modification.
 *
 * @author Open Mason Team
 */
public interface DrawingTool {

    /**
     * Called when mouse button is pressed down on canvas.
     * Starts a new drawing operation.
     *
     * @param x canvas pixel X coordinate
     * @param y canvas pixel Y coordinate
     * @param color current drawing color (RGBA packed)
     * @param canvas canvas to draw on
     * @param command command to record changes (for undo/redo)
     */
    void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command);

    /**
     * Called when mouse is dragged on canvas.
     *
     * @param x canvas pixel X coordinate
     * @param y canvas pixel Y coordinate
     * @param color current drawing color (RGBA packed)
     * @param canvas canvas to draw on
     * @param command command to record changes (for undo/redo)
     */
    void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command);

    /**
     * Called when mouse button is released.
     * Ends the current drawing operation.
     *
     * @param color current drawing color (RGBA packed)
     * @param canvas canvas to draw on
     * @param command command to record changes (for undo/redo)
     */
    void onMouseUp(int color, PixelCanvas canvas, DrawCommand command);

    /**
     * Get tool name.
     * @return tool display name
     */
    String getName();

    /**
     * Get tool description.
     * @return tool description for tooltips
     */
    String getDescription();

    /**
     * Reset tool state.
     * Called when switching tools or canceling operations.
     */
    default void reset() {
        // Default implementation does nothing
    }

    /**
     * Check if this tool supports variable brush size.
     * Tools that support brush size should override this to return true.
     *
     * @return true if tool supports brush size, false otherwise
     */
    default boolean supportsBrushSize() {
        return false; // Default: no brush size support
    }

    /**
     * Get current brush size for this tool.
     * Only meaningful if supportsBrushSize() returns true.
     *
     * @return brush size in pixels (diameter)
     */
    default int getBrushSize() {
        return 1; // Default single pixel
    }

    /**
     * Set brush size for this tool.
     * Only meaningful if supportsBrushSize() returns true.
     * Per-tool memory: each tool remembers its own brush size.
     *
     * @param size brush size in pixels (diameter)
     */
    default void setBrushSize(int size) {
        // Default implementation does nothing
    }
}
