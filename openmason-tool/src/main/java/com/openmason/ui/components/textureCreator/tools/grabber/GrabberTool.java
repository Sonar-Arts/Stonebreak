package com.openmason.ui.components.textureCreator.tools.grabber;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.tools.DrawingTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grabber (Hand) tool for panning the canvas.
 *
 * Similar to Photoshop's hand tool, allows dragging the canvas view
 * with left-click instead of middle-click. The tool itself is simple
 * and delegates actual panning logic to CanvasState via CanvasPanel.
 *
 * Follows SOLID principles:
 * - Single Responsibility: Only signals panning intent
 * - Open/Closed: Extends tool system without modification
 * - Interface Segregation: Implements minimal DrawingTool interface
 *
 * Follows KISS: Simple flag-based state, no complex logic
 * Follows YAGNI: Only implements what's needed for canvas panning
 * Follows DRY: Reuses existing CanvasState panning functionality
 *
 * @author Open Mason Team
 */
public class GrabberTool implements DrawingTool {

    private static final Logger logger = LoggerFactory.getLogger(GrabberTool.class);

    // State flag indicating if the tool is actively being used
    private boolean isActive = false;

    /**
     * Called when mouse button is pressed down on canvas.
     * Activates the grabber tool to start panning.
     *
     * @param x canvas pixel X coordinate (unused - panning uses screen coords)
     * @param y canvas pixel Y coordinate (unused - panning uses screen coords)
     * @param color current drawing color (unused)
     * @param canvas canvas to draw on (unused - no drawing occurs)
     * @param command command to record changes (unused - no pixel changes)
     */
    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        isActive = true;
        logger.debug("Grabber tool activated at canvas coords ({}, {})", x, y);
    }

    /**
     * Called when mouse is dragged on canvas.
     * Maintains active state during drag. Actual panning is handled
     * by CanvasPanel which checks isActive() and updates CanvasState.
     *
     * @param x canvas pixel X coordinate (unused)
     * @param y canvas pixel Y coordinate (unused)
     * @param color current drawing color (unused)
     * @param canvas canvas to draw on (unused)
     * @param command command to record changes (unused)
     */
    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Active state maintained - panning handled by CanvasPanel
        // This method is called to maintain tool protocol consistency
    }

    /**
     * Called when mouse button is released.
     * Deactivates the grabber tool and stops panning.
     *
     * @param color current drawing color (unused)
     * @param canvas canvas to draw on (unused)
     * @param command command to record changes (unused)
     */
    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        isActive = false;
        logger.debug("Grabber tool deactivated");
    }

    /**
     * Get tool name.
     * @return tool display name
     */
    @Override
    public String getName() {
        return "Grabber";
    }

    /**
     * Get tool description.
     * @return tool description for tooltips
     */
    @Override
    public String getDescription() {
        return "Pan the canvas by dragging";
    }

    /**
     * Reset tool state.
     * Called when switching tools or canceling operations.
     */
    @Override
    public void reset() {
        isActive = false;
        logger.debug("Grabber tool reset");
    }

    /**
     * Check if the grabber tool is currently active.
     * CanvasPanel uses this to determine if panning should be applied.
     *
     * @return true if the tool is actively being used
     */
    public boolean isActive() {
        return isActive;
    }
}
