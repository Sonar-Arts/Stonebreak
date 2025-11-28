package com.openmason.ui.textureCreator.coordinators;

import com.openmason.ui.textureCreator.TextureCreatorController;
import com.openmason.ui.textureCreator.TextureCreatorState;
import com.openmason.ui.toolbars.TextureEditorToolbarRenderer;
import com.openmason.ui.textureCreator.tools.DrawingTool;
import com.openmason.ui.textureCreator.tools.move.MoveToolController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates tool state transitions and management.
 */
public class ToolCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(ToolCoordinator.class);

    private final TextureCreatorState state;
    private final TextureEditorToolbarRenderer toolbarPanel;
    private TextureCreatorController controller;  // Set after construction to avoid circular dependency
    private PasteCoordinator pasteCoordinator;    // Set after construction to avoid circular dependency

    /**
     * Create tool coordinator.
     */
    public ToolCoordinator(TextureCreatorState state, TextureEditorToolbarRenderer toolbarPanel) {
        this.state = state;
        this.toolbarPanel = toolbarPanel;
    }

    /**
     * Set controller and paste coordinator.
     */
    public void setDependencies(TextureCreatorController controller, PasteCoordinator pasteCoordinator) {
        this.controller = controller;
        this.pasteCoordinator = pasteCoordinator;
    }

    /**
     * Switch to a specific tool.
     */
    public void switchToTool(DrawingTool tool) {
        if (tool == null) {
            logger.warn("Attempted to switch to null tool");
            return;
        }

        state.setCurrentTool(tool);
        toolbarPanel.setCurrentTool(tool);
        logger.debug("Switched to tool: {}", tool.getClass().getSimpleName());
    }

    /**
     * Switch to the grabber tool (default navigation tool).
     */
    public void switchToGrabberTool() {
        toolbarPanel.getTools().stream()
            .filter(t -> t.getClass().getSimpleName().equals("GrabberTool"))
            .findFirst()
            .ifPresentOrElse(
                this::switchToTool,
                () -> logger.warn("Grabber tool not found in toolbar")
            );
    }

    /**
     * Switch to move tool.
     * Used for paste operations and manual selection movement.
     */
    public void switchToMoveTool() {
        toolbarPanel.getTools().stream()
            .filter(t -> t.getClass().getSimpleName().equals("MoveToolController"))
            .findFirst()
            .ifPresentOrElse(
                this::switchToTool,
                () -> logger.warn("Move tool not found in toolbar")
            );
    }

    /**
     * Get the move tool instance from toolbar.
     *
     * @return move tool or null if not found
     */
    public MoveToolController getMoveTool() {
        return toolbarPanel.getMoveToolInstance();
    }

    /**
     * Get current tool from toolbar.
     */
    public DrawingTool getCurrentTool() {
        return toolbarPanel.getCurrentTool();
    }

    /**
     * Sync tool state from toolbar to state object.
     * Call this after toolbar rendering to ensure state is up-to-date.
     */
    public void syncToolState() {
        DrawingTool toolbarTool = toolbarPanel.getCurrentTool();
        if (toolbarTool != state.getCurrentTool()) {
            state.setCurrentTool(toolbarTool);
        }
    }

    /**
     * Handle Enter key for tool commit.
     * Extracted from main UI to keep tool-specific logic in tool coordinator.
     */
    public void handleEnterKey() {
        if (!(getCurrentTool() instanceof MoveToolController moveTool)) return;

        if (!moveTool.isActive() || moveTool.getPendingCommand() == null) return;

        var pendingCmd = moveTool.getPendingCommand();
        if (!pendingCmd.hasChanges()) return;

        controller.getCommandHistory().executeCommand(pendingCmd);

        var transformedSelection = pendingCmd.getTransformedSelection();
        if (transformedSelection != null) {
            state.setCurrentSelection(transformedSelection);
        }

        moveTool.clearPendingCommand();
        moveTool.reset();
        controller.notifyLayerModified();

        if (pasteCoordinator.isPasteSessionActive()) {
            pasteCoordinator.commitPaste();
        } else {
            switchToGrabberTool();
        }
    }

    /**
     * Handle Escape key for tool cancel.
     * Extracted from main UI to keep tool-specific logic in tool coordinator.
     */
    public void handleEscapeKey() {
        if (getCurrentTool() instanceof MoveToolController moveTool) {

            if (moveTool.isMouseCaptured()) {
                moveTool.forceReleaseMouse();
                return;
            }

            if (moveTool.isActive()) {
                moveTool.cancelAndReset(controller.getActiveLayerCanvas());

                if (pasteCoordinator.isPasteSessionActive()) {
                    pasteCoordinator.cancelPaste();
                } else {
                    state.clearSelection();
                }
                return;
            }
        }

        state.clearSelection();
    }
}
