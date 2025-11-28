package com.openmason.ui.textureCreator.tools;

import com.openmason.ui.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.textureCreator.commands.DrawCommand;

/**
 * Drawing tool interface following Strategy pattern.
 */
public interface DrawingTool {

    /**
     * Called when mouse button is pressed down on canvas.
     */
    void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command);

    /**
     * Called when mouse is dragged on canvas.
     */
    void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command);

    /**
     * Called when mouse button is released.
     */
    void onMouseUp(int color, PixelCanvas canvas, DrawCommand command);

    /**
     * Get tool name.
     */
    String getName();

    /**
     * Get tool description.
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
     */
    default boolean supportsBrushSize() {
        return false; // Default: no brush size support
    }

    /**
     * Get current brush size for this tool.
     */
    default int getBrushSize() {
        return 1; // Default single pixel
    }

    /**
     * Set brush size for this tool.
     */
    default void setBrushSize(int size) {
        // Default implementation does nothing
    }
}
