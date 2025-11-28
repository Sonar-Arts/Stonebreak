package com.openmason.ui.textureCreator.tools.paste;

import com.openmason.ui.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.textureCreator.commands.DrawCommand;
import com.openmason.ui.textureCreator.commands.PasteCommand;
import com.openmason.ui.textureCreator.selection.RectangularSelection;
import com.openmason.ui.textureCreator.selection.SelectionRegion;
import com.openmason.ui.textureCreator.tools.DrawingTool;
import imgui.ImDrawList;
import imgui.ImColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Paste tool with floating preview.
 *
 * Allows user to drag pasted content to desired position before committing.
 * Similar to MoveToolController but simpler (no transformation, just position).
 *
 * Interaction:
 * - Shows floating preview of clipboard content
 * - Drag to reposition
 * - Enter key or click outside: commit paste
 * - ESC key: cancel paste
 *
 * @author Open Mason Team
 */
public class PasteTool implements DrawingTool {

    private static final Logger logger = LoggerFactory.getLogger(PasteTool.class);

    // Clipboard data
    private PixelCanvas clipboardCanvas;
    private int initialPasteX;
    private int initialPasteY;

    // Current paste state
    private int currentPasteX;
    private int currentPasteY;
    private boolean isDragging = false;
    private int dragStartMouseX;
    private int dragStartMouseY;
    private int dragStartPasteX;
    private int dragStartPasteY;

    // Preview layer
    private PastePreviewLayer previewLayer;

    // Pending command to execute
    private PasteCommand pendingCommand;

    // Tool active state
    private boolean isActive = false;

    /**
     * Initialize paste tool with clipboard content.
     *
     * @param clipboardCanvas clipboard content to paste
     * @param pasteX initial paste X position
     * @param pasteY initial paste Y position
     */
    public void initializePaste(PixelCanvas clipboardCanvas, int pasteX, int pasteY) {
        this.clipboardCanvas = clipboardCanvas;
        this.initialPasteX = pasteX;
        this.initialPasteY = pasteY;
        this.currentPasteX = pasteX;
        this.currentPasteY = pasteY;
        this.isActive = true;

        // Create initial preview
        updatePreviewLayer();

        logger.info("Paste tool activated at ({}, {}) with clipboard size {}x{}",
                   pasteX, pasteY, clipboardCanvas.getWidth(), clipboardCanvas.getHeight());
    }

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

        // Update preview layer
        updatePreviewLayer();
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
        pendingCommand = null;
    }

    /**
     * Commit paste operation to canvas.
     * Creates command for undo/redo support.
     *
     * @param canvas target canvas
     */
    public void commitPaste(PixelCanvas canvas) {
        if (!isActive || clipboardCanvas == null) {
            logger.warn("Cannot commit paste - tool not active or no clipboard data");
            return;
        }

        // Create selection region for pasted area
        SelectionRegion pastedSelection = new RectangularSelection(
            currentPasteX,
            currentPasteY,
            currentPasteX + clipboardCanvas.getWidth() - 1,
            currentPasteY + clipboardCanvas.getHeight() - 1
        );

        // Create paste command
        // Note: This method appears to be legacy code (paste now uses move tool).
        // Using default value of true to skip transparent pixels (maintains previous behavior).
        pendingCommand = new PasteCommand(canvas, clipboardCanvas,
                                         currentPasteX, currentPasteY, pastedSelection, true);

        logger.info("Paste committed at ({}, {})", currentPasteX, currentPasteY);

        // Deactivate tool
        isActive = false;
    }

    /**
     * Cancel paste operation.
     */
    public void cancelPaste() {
        if (!isActive) {
            return;
        }

        logger.info("Paste canceled");
        isActive = false;
        pendingCommand = null;
        clipboardCanvas = null;
        previewLayer = null;
    }

    /**
     * Check if tool is currently active (showing paste preview).
     * @return true if active
     */
    public boolean isActive() {
        return isActive;
    }

    /**
     * Check if there's a pending paste command to execute.
     * @return true if command is pending
     */
    public boolean hasPendingCommand() {
        return pendingCommand != null;
    }

    /**
     * Get the pending paste command.
     * @return pending command, or null
     */
    public PasteCommand getPendingCommand() {
        return pendingCommand;
    }

    /**
     * Clear pending command (after execution).
     */
    public void clearPendingCommand() {
        pendingCommand = null;
    }

    /**
     * Check if there's an active preview layer.
     * @return true if preview exists
     */
    public boolean hasPreviewLayer() {
        return previewLayer != null && isActive;
    }

    /**
     * Get the preview layer.
     * @return preview layer, or null
     */
    public PastePreviewLayer getPreviewLayer() {
        return previewLayer;
    }

    /**
     * Render paste preview overlay (bounding box).
     *
     * @param drawList ImGui draw list
     * @param canvasX canvas display X position
     * @param canvasY canvas display Y position
     * @param zoom current zoom level
     */
    public void renderOverlay(ImDrawList drawList, float canvasX, float canvasY, float zoom) {
        if (!hasPreviewLayer()) {
            return;
        }

        // Calculate preview bounds in screen space
        float x1 = canvasX + currentPasteX * zoom;
        float y1 = canvasY + currentPasteY * zoom;
        float x2 = x1 + clipboardCanvas.getWidth() * zoom;
        float y2 = y1 + clipboardCanvas.getHeight() * zoom;

        // Draw bounding box
        int borderColor = ImColor.rgba(0, 255, 255, 200); // Cyan border
        drawList.addRect(x1, y1, x2, y2, borderColor, 0.0f, 0, 2.0f);

        // Draw corner handles (small squares)
        float handleSize = 6.0f;
        int handleColor = ImColor.rgba(0, 255, 255, 255);
        drawList.addRectFilled(x1 - handleSize/2, y1 - handleSize/2,
                              x1 + handleSize/2, y1 + handleSize/2, handleColor);
        drawList.addRectFilled(x2 - handleSize/2, y1 - handleSize/2,
                              x2 + handleSize/2, y1 + handleSize/2, handleColor);
        drawList.addRectFilled(x1 - handleSize/2, y2 - handleSize/2,
                              x1 + handleSize/2, y2 + handleSize/2, handleColor);
        drawList.addRectFilled(x2 - handleSize/2, y2 - handleSize/2,
                              x2 + handleSize/2, y2 + handleSize/2, handleColor);
    }

    /**
     * Update preview layer with current paste position.
     */
    private void updatePreviewLayer() {
        if (clipboardCanvas != null) {
            previewLayer = new PastePreviewLayer(clipboardCanvas, currentPasteX, currentPasteY);
        }
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
