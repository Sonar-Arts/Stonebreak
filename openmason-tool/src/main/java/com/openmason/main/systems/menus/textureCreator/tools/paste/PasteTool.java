package com.openmason.main.systems.menus.textureCreator.tools.paste;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import com.openmason.main.systems.menus.textureCreator.tools.DrawingTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Paste tool with floating preview.
 */
public class PasteTool implements DrawingTool {

    private static final Logger logger = LoggerFactory.getLogger(PasteTool.class);

    // Clipboard data
    private PixelCanvas clipboardCanvas;

    // Current paste state
    private int currentPasteX;
    private int currentPasteY;
    private boolean isDragging = false;
    private int dragStartMouseX;
    private int dragStartMouseY;
    private int dragStartPasteX;
    private int dragStartPasteY;

    // Tool active state
    private final boolean isActive = false;

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        if (!isActive || clipboardCanvas == null) {
            return;
        }

        // Check if clicking inside paste preview bounds
        if (isInsidePasteRegion(x, y)) {
            // Start dragging to reposition
            isDragging = true;
            dragStartMouseX = x;
            dragStartMouseY = y;
            dragStartPasteX = currentPasteX;
            dragStartPasteY = currentPasteY;
            logger.debug("Started dragging paste preview");
        }
        // Note: Don't auto-commit on clicking outside
        // User must explicitly press Enter to commit or ESC to cancel
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        if (!isDragging) {
            return;
        }

        // Update paste position based on drag delta
        int deltaX = x - dragStartMouseX;
        int deltaY = y - dragStartMouseY;
        currentPasteX = dragStartPasteX + deltaX;
        currentPasteY = dragStartPasteY + deltaY;
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        if (isDragging) {
            isDragging = false;
            logger.debug("Stopped dragging paste preview at ({}, {})", currentPasteX, currentPasteY);
        }
    }

    @Override
    public String getName() {
        return "Paste";
    }

    @Override
    public String getDescription() {
        return "Paste clipboard content (drag to position, Enter to commit, ESC to cancel)";
    }

    @Override
    public void reset() {
        // Don't reset if paste is active - user must explicitly commit or cancel
        if (isActive) {
            logger.debug("Reset called but paste is active - ignoring (use cancelPaste or commitPaste)");
            return;
        }

        isDragging = false;
        // Pending command to execute
    }

    /**
     * Check if tool is currently active (showing paste preview).
     */
    public boolean isActive() {
        return isActive;
    }

    /**
     * Check if coordinates are inside the paste region.
     *
     * @param x canvas X coordinate
     * @param y canvas Y coordinate
     * @return true if inside paste bounds
     */
    private boolean isInsidePasteRegion(int x, int y) {
        if (clipboardCanvas == null) {
            return false;
        }

        return x >= currentPasteX && x < currentPasteX + clipboardCanvas.getWidth() &&
               y >= currentPasteY && y < currentPasteY + clipboardCanvas.getHeight();
    }
}
