package com.openmason.ui.textureCreator.coordinators;

import com.openmason.ui.textureCreator.TextureCreatorController;
import com.openmason.ui.textureCreator.TextureCreatorPreferences;
import com.openmason.ui.textureCreator.TextureCreatorState;
import com.openmason.ui.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.textureCreator.clipboard.ClipboardManager;
import com.openmason.ui.textureCreator.commands.PasteCommand;
import com.openmason.ui.textureCreator.selection.RectangularSelection;
import com.openmason.ui.textureCreator.tools.DrawingTool;
import com.openmason.ui.textureCreator.tools.move.MoveToolController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates paste operations with transformation workflow.
 */
public class PasteCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(PasteCoordinator.class);

    private final TextureCreatorState state;
    private final TextureCreatorController controller;
    private final ToolCoordinator toolCoordinator;
    private final TextureCreatorPreferences preferences;

    // State tracking for paste session
    private DrawingTool toolBeforePaste;
    private boolean pasteSessionActive;

    /**
     * Create paste coordinator.
     */
    public PasteCoordinator(TextureCreatorState state,
                           TextureCreatorController controller,
                           ToolCoordinator toolCoordinator,
                           TextureCreatorPreferences preferences) {
        this.state = state;
        this.controller = controller;
        this.toolCoordinator = toolCoordinator;
        this.preferences = preferences;
    }

    /**
     * Check if paste operation can be initiated.
     */
    public boolean canPaste() {
        return controller.canPaste();
    }

    /**
     * Initiate paste operation.
     */
    public void initiatePaste() {
        if (!canPaste()) {
            logger.warn("Cannot paste - clipboard is empty");
            return;
        }

        // Get clipboard data
        ClipboardManager clipboard = controller.getClipboard();
        PixelCanvas clipboardCanvas = clipboard.getClipboardCanvas();

        if (clipboardCanvas == null) {
            logger.warn("Cannot paste - clipboard canvas is null");
            return;
        }

        // Get move tool for transformation
        MoveToolController moveTool = toolCoordinator.getMoveTool();
        if (moveTool == null) {
            logger.error("Cannot paste - move tool not available");
            return;
        }

        // Get active layer canvas
        PixelCanvas activeCanvas = controller.getActiveLayerCanvas();
        if (activeCanvas == null) {
            logger.error("Cannot paste - no active layer");
            return;
        }

        // Create paste selection region (rectangular region for pasted content)
        RectangularSelection pasteSelection = new RectangularSelection(
            clipboard.getSourceX(),
            clipboard.getSourceY(),
            clipboard.getSourceX() + clipboardCanvas.getWidth() - 1,
            clipboard.getSourceY() + clipboardCanvas.getHeight() - 1
        );

        // Create and execute paste command (pastes to active layer with undo support)
        PasteCommand pasteCommand = new PasteCommand(
            activeCanvas,
            clipboardCanvas,
            clipboard.getSourceX(),
            clipboard.getSourceY(),
            pasteSelection,
            preferences.isSkipTransparentPixelsOnPaste()
        );

        controller.getCommandHistory().executeCommand(pasteCommand);
        controller.notifyLayerModified();

        // Set selection to pasted region
        state.setCurrentSelection(pasteSelection);

        // Remember current tool so we can restore it after paste commit/cancel
        toolBeforePaste = state.getCurrentTool();
        pasteSessionActive = true;

        // Activate move tool with the pasted selection
        // User can now drag to reposition, Enter to commit, ESC to cancel (which will undo the paste)
        toolCoordinator.switchToTool(moveTool);

        // Set up paste session (prevents hole creation during transformation)
        moveTool.setupPasteSession(activeCanvas, pasteSelection);

        logger.info("Paste activated at ({}, {})", clipboard.getSourceX(), clipboard.getSourceY());
    }

    /**
     * Commit paste operation.
     * Finalizes the transformation and restores previous tool.
     */
    public void commitPaste() {
        if (!pasteSessionActive) {
            logger.debug("No active paste session to commit");
            return;
        }

        MoveToolController moveTool = toolCoordinator.getMoveTool();
        if (moveTool != null && moveTool.isActive() && moveTool.getPendingCommand() != null) {
            var pendingCmd = moveTool.getPendingCommand();

            if (pendingCmd.hasChanges()) {
                // Execute transformation command
                controller.getCommandHistory().executeCommand(pendingCmd);

                // Update selection to transformed region
                var transformedSelection = pendingCmd.getTransformedSelection();
                if (transformedSelection != null) {
                    state.setCurrentSelection(transformedSelection);
                }

                // Clear move tool state
                moveTool.clearPendingCommand();
                moveTool.reset();

                // Trigger visual update
                controller.notifyLayerModified();

                logger.info("Paste committed with transformation");
            } else {
                // No transformation - just clear tool state
                moveTool.clearPendingCommand();
                moveTool.reset();
                logger.info("Paste committed without transformation");
            }
        }

        // Restore previous tool
        if (toolBeforePaste != null) {
            toolCoordinator.switchToTool(toolBeforePaste);
            toolBeforePaste = null;
        }

        pasteSessionActive = false;
    }

    /**
     * Cancel paste operation.
     * Undoes the paste and restores previous tool.
     */
    public void cancelPaste() {
        if (!pasteSessionActive) {
            logger.debug("No active paste session to cancel");
            return;
        }

        MoveToolController moveTool = toolCoordinator.getMoveTool();
        if (moveTool != null) {
            // Cancel any pending transformation
            if (moveTool.isMouseCaptured()) {
                moveTool.forceReleaseMouse();
            }

            if (moveTool.isActive()) {
                moveTool.cancelAndReset(controller.getActiveLayerCanvas());
            }
        }

        // Undo the paste command
        controller.undo();

        // Clear selection
        state.clearSelection();

        // Restore previous tool
        if (toolBeforePaste != null) {
            toolCoordinator.switchToTool(toolBeforePaste);
            toolBeforePaste = null;
        }

        pasteSessionActive = false;
        logger.info("Paste cancelled and undone");
    }

    /**
     * Check if a paste session is currently active.
     *
     * @return true if user is in the middle of a paste operation
     */
    public boolean isPasteSessionActive() {
        return pasteSessionActive;
    }

}
