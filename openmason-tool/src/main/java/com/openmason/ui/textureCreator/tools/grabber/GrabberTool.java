package com.openmason.ui.textureCreator.tools.grabber;

import com.openmason.ui.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.textureCreator.commands.DrawCommand;
import com.openmason.ui.textureCreator.tools.DrawingTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grabber (Hand) tool for panning the canvas.
 */
public class GrabberTool implements DrawingTool {

    private static final Logger logger = LoggerFactory.getLogger(GrabberTool.class);

    // State flag indicating if the tool is actively being used
    private boolean isActive = false;

    /**
     * Called when mouse button is pressed down on canvas.
     */
    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        isActive = true;
        logger.debug("Grabber tool activated at canvas coords ({}, {})", x, y);
    }

    /**
     * Called when mouse is dragged on canvas.
     */
    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Active state maintained - panning handled by CanvasPanel
        // This method is called to maintain tool protocol consistency
    }

    /**
     * Called when mouse button is released.
     */
    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        isActive = false;
        logger.debug("Grabber tool deactivated");
    }

    /**
     * Get tool name.
     */
    @Override
    public String getName() {
        return "Grabber";
    }

    /**
     * Get tool description.
     */
    @Override
    public String getDescription() {
        return "Pan the canvas by dragging";
    }

    /**
     * Reset tool state.
     */
    @Override
    public void reset() {
        isActive = false;
        logger.debug("Grabber tool reset");
    }

    /**
     * Check if the grabber tool is currently active.
     */
    public boolean isActive() {
        return isActive;
    }
}
