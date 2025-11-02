package com.openmason.ui.components.textureCreator.panels;

import com.openmason.ui.components.textureCreator.canvas.CanvasRenderer;
import com.openmason.ui.components.textureCreator.canvas.CanvasState;
import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.CommandHistory;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.selection.SelectionRenderer;
import com.openmason.ui.components.textureCreator.tools.ColorPickerTool;
import com.openmason.ui.components.textureCreator.tools.DrawingTool;
import com.openmason.ui.components.textureCreator.tools.grabber.GrabberTool;
import com.openmason.ui.components.textureCreator.tools.selection.SelectionTool;
import com.openmason.ui.components.textureCreator.tools.selection.SelectionPreview;
import com.openmason.ui.components.textureCreator.tools.selection.RectanglePreview;
import com.openmason.ui.components.textureCreator.tools.move.MoveToolController;
import com.openmason.ui.components.textureCreator.tools.SelectionBrushTool;
import com.openmason.ui.components.textureCreator.commands.move.MoveSelectionCommand;
import imgui.ImGui;
import imgui.ImVec2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canvas panel UI component.
 *
 * Handles rendering the canvas and processing mouse input for drawing.
 * Follows SOLID principles - Single Responsibility: canvas display and interaction.
 *
 * @author Open Mason Team
 */
public class CanvasPanel {

    private static final Logger logger = LoggerFactory.getLogger(CanvasPanel.class);

    private final CanvasRenderer renderer;
    private final SelectionRenderer selectionRenderer;
    private boolean isMouseDown = false;
    private boolean wasMouseDown = false;

    // Callback for when drawing occurs
    private Runnable onDrawCallback = null;

    // Callback for when color is picked (receives picked color)
    private java.util.function.IntConsumer onColorPickedCallback = null;

    // Callback for when color is used on canvas (receives color that was used)
    private java.util.function.IntConsumer onColorUsedCallback = null;

    // Callback for when selection is created (receives SelectionRegion or null to clear)
    private java.util.function.Consumer<SelectionRegion> onSelectionCreatedCallback = null;

    // Current draw command (active during a drawing operation)
    private DrawCommand currentDrawCommand = null;

    /**
     * Create canvas panel.
     */
    public CanvasPanel() {
        this.renderer = new CanvasRenderer();
        this.selectionRenderer = new SelectionRenderer();
        logger.debug("Canvas panel created");
    }

    /**
     * Render canvas panel.
     *
     * @param displayCanvas pixel canvas to display (composited view)
     * @param drawingCanvas pixel canvas for drawing operations (active layer)
     * @param backgroundCanvas pixel canvas with all layers except active (for multi-layer preview, can be null)
     * @param canvasState view state (zoom, pan)
     * @param currentTool currently selected drawing tool (can be null)
     * @param currentColor current drawing color
     * @param currentSelection current active selection region (can be null)
     * @param showGrid whether to show grid overlay
     * @param gridOpacity opacity for grid lines (0.0 to 1.0)
     * @param cubeNetOverlayOpacity opacity for cube net overlay (0.0 to 1.0)
     * @param commandHistory command history for undo/redo
     * @param onDrawCallback callback invoked when drawing occurs (can be null)
     * @param onColorPickedCallback callback invoked when color is picked (can be null)
     * @param onColorUsedCallback callback invoked when color is used on canvas (can be null)
     * @param onSelectionCreatedCallback callback invoked when selection is created/cleared (can be null)
     */
    public void render(PixelCanvas displayCanvas, PixelCanvas drawingCanvas, PixelCanvas backgroundCanvas,
                      CanvasState canvasState,
                      DrawingTool currentTool, int currentColor, SelectionRegion currentSelection,
                      boolean showGrid, float gridOpacity, float cubeNetOverlayOpacity,
                      CommandHistory commandHistory, Runnable onDrawCallback,
                      java.util.function.IntConsumer onColorPickedCallback,
                      java.util.function.IntConsumer onColorUsedCallback,
                      java.util.function.Consumer<SelectionRegion> onSelectionCreatedCallback,
                      com.openmason.ui.components.textureCreator.SymmetryState symmetryState) {

        this.onDrawCallback = onDrawCallback;
        this.onColorPickedCallback = onColorPickedCallback;
        this.onColorUsedCallback = onColorUsedCallback;
        this.onSelectionCreatedCallback = onSelectionCreatedCallback;

        if (displayCanvas == null || canvasState == null) {
            ImGui.text("No canvas available");
            return;
        }

        // Begin canvas child window for clipping and scrolling
        ImGui.beginChild("##canvas_area", 0, 0, false);

        // Calculate canvas display size and centering offset
        float zoom = canvasState.getZoomLevel();
        float displayWidth = displayCanvas.getWidth() * zoom;
        float displayHeight = displayCanvas.getHeight() * zoom;

        ImVec2 availableRegion = ImGui.getContentRegionAvail();
        float centerOffsetX = Math.max(0, (availableRegion.x - displayWidth) / 2.0f);
        float centerOffsetY = Math.max(0, (availableRegion.y - displayHeight) / 2.0f);

        // Store canvas display region start position (with centering)
        ImVec2 cursorPos = ImGui.getCursorScreenPos();
        ImVec2 canvasRegionMin = new ImVec2(
            cursorPos.x + centerOffsetX,
            cursorPos.y + centerOffsetY
        );

        // Get selection preview if using selection tool
        int[] selectionPreviewBounds = null;
        SelectionRegion pixelBasedPreview = null;
        if (currentTool instanceof SelectionTool) {
            SelectionTool selectionTool = (SelectionTool) currentTool;
            SelectionPreview preview = selectionTool.getPreview();

            // Handle different preview types
            if (preview instanceof RectanglePreview) {
                // Rectangle preview: pass as bounds array for efficient rectangle rendering
                RectanglePreview rectPreview = (RectanglePreview) preview;
                selectionPreviewBounds = new int[]{
                    rectPreview.getStartX(),
                    rectPreview.getStartY(),
                    rectPreview.getEndX(),
                    rectPreview.getEndY()
                };
            } else if (preview instanceof com.openmason.ui.components.textureCreator.tools.selection.PixelsPreview) {
                // Pixels preview: convert to MaskSelectionRegion for freeform rendering
                com.openmason.ui.components.textureCreator.tools.selection.PixelsPreview pixelsPreview =
                    (com.openmason.ui.components.textureCreator.tools.selection.PixelsPreview) preview;
                if (!pixelsPreview.isEmpty()) {
                    pixelBasedPreview = com.openmason.ui.components.textureCreator.selection.MaskSelectionRegion
                        .fromEncodedPixels(pixelsPreview.getEncodedPixels());
                }
            }
        }

        // Check if move tool has an active transform layer for preview
        PixelCanvas canvasToRender = displayCanvas;
        boolean moveToolActive = currentTool instanceof MoveToolController;
        if (moveToolActive) {
            MoveToolController moveTool = (MoveToolController) currentTool;
            if (moveTool.hasPreviewLayer() && drawingCanvas != null) {
                // Create preview canvas with transformed layer composited
                if (backgroundCanvas != null) {
                    // Multi-layer preview: composite background + transformed active layer
                    // This shows the transformation in full context with all other layers
                    canvasToRender = moveTool.getPreviewLayer()
                            .createMultiLayerPreviewCanvas(backgroundCanvas, drawingCanvas);
                } else {
                    // Single-layer preview: just the transformed active layer
                    canvasToRender = moveTool.getPreviewLayer().createPreviewCanvas(drawingCanvas);
                }
            }
        }

        // Note: Paste now uses move tool, so no separate paste tool preview needed

        // Determine which selection to render:
        // 1. Hide selection overlay when move tool is active (matches Photoshop/GIMP behavior)
        //    The move tool's own transform overlay (handles, bounding box) will be rendered instead
        // 2. Show pixel-based preview (selection brush) if actively painting
        // 3. Otherwise show the current selection
        SelectionRegion selectionToRender;
        if (moveToolActive && currentSelection != null) {
            selectionToRender = null; // Hide overlay during move operations
        } else if (pixelBasedPreview != null) {
            selectionToRender = pixelBasedPreview; // Show real-time brush preview
        } else {
            selectionToRender = currentSelection; // Show normal selection
        }

        // Render the display canvas (composited view) with opacity settings
        renderer.render(canvasToRender, canvasState, showGrid, gridOpacity, cubeNetOverlayOpacity,
                       selectionToRender, selectionPreviewBounds, symmetryState);

        // Render move tool overlay if using move tool
        if (currentTool instanceof MoveToolController) {
            MoveToolController moveTool = (MoveToolController) currentTool;
            imgui.ImDrawList drawList = imgui.ImGui.getWindowDrawList();

            // Update hovered handle for cursor feedback
            ImVec2 mousePos = ImGui.getMousePos();

            // Update mouse delta for rotation tracking (works with captured mouse)
            // Use horizontal delta (X) for rotation
            ImVec2 mouseDelta = ImGui.getIO().getMouseDelta();
            moveTool.updateMouseDelta(mouseDelta.x);

            moveTool.updateHoveredHandle(mousePos.x, mousePos.y, currentSelection, canvasState,
                    canvasRegionMin.x, canvasRegionMin.y);

            // Render move tool overlay (handles, preview, etc.)
            moveTool.renderOverlay(drawList, currentSelection, canvasState,
                    canvasRegionMin.x, canvasRegionMin.y);
        }

        // Note: Paste now uses move tool, so move tool overlay handles paste rendering

        // Note: Selection is now automatically available to canvases through SelectionManager
        // which is wired up in TextureCreatorImGui.renderCanvasPanel()
        // No need to manually sync selection here - canvases get it from SelectionManager
        PixelCanvas targetCanvas = drawingCanvas != null ? drawingCanvas : displayCanvas;

        // Note: Enter/ESC keyboard handling for move tool is in TextureCreatorImGui
        // (needs access to floating paste layer state)

        // Handle mouse input for drawing and navigation on the drawing canvas (active layer)
        handleInput(targetCanvas, canvasState,
                   currentTool, currentColor, canvasRegionMin, commandHistory);

        ImGui.endChild();
    }

    /**
     * Handle mouse input for drawing and canvas navigation.
     *
     * @param canvas pixel canvas
     * @param canvasState view state
     * @param currentTool current drawing tool
     * @param currentColor current color
     * @param canvasRegionMin canvas display region start position
     * @param commandHistory command history for undo/redo
     */
    private void handleInput(PixelCanvas canvas, CanvasState canvasState,
                            DrawingTool currentTool, int currentColor,
                            ImVec2 canvasRegionMin, CommandHistory commandHistory) {

        // Check if mouse is hovering over canvas area
        if (!ImGui.isWindowHovered()) {
            return;
        }

        // Get mouse position and keyboard state
        ImVec2 mousePos = ImGui.getMousePos();
        boolean leftMouseDown = ImGui.isMouseDown(0);   // Left button for drawing
        boolean middleMouseDown = ImGui.isMouseDown(2); // Middle button for panning
        float mouseWheel = ImGui.getIO().getMouseWheel();
        boolean shiftHeld = ImGui.getIO().getKeyShift(); // Shift key for constrained operations
        boolean ctrlHeld = ImGui.getIO().getKeyCtrl();
        boolean altHeld = ImGui.getIO().getKeyAlt();

        // Handle zoom with mouse wheel
        if (mouseWheel != 0.0f) {
            if (mouseWheel > 0) {
                canvasState.zoomIn(1.2f);
            } else {
                canvasState.zoomOut(1.2f);
            }
        }

        // Handle panning with middle mouse button OR grabber tool with left mouse button
        boolean grabberToolActive = (currentTool instanceof GrabberTool) && ((GrabberTool) currentTool).isActive();
        boolean shouldPan = middleMouseDown || (leftMouseDown && grabberToolActive);

        if (shouldPan) {
            if (!canvasState.isPanning()) {
                canvasState.startPanning(mousePos.x, mousePos.y);
            } else {
                canvasState.updatePanning(mousePos.x, mousePos.y);
            }
        } else if (canvasState.isPanning()) {
            canvasState.stopPanning();
        }

        // Handle drawing with left mouse button
        if (leftMouseDown && currentTool != null && !canvasState.isPanning()) {
            // Update modifier keys for move tool
            if (currentTool instanceof MoveToolController) {
                ((MoveToolController) currentTool).setModifierKeys(shiftHeld);
            } else if (currentTool instanceof SelectionBrushTool) {
                ((SelectionBrushTool) currentTool).updateModifierState(ctrlHeld, altHeld);
            }

            // Convert screen coordinates to canvas pixel coordinates
            int[] canvasCoords = new int[2];
            boolean validCoords = canvasState.screenToCanvasCoords(
                mousePos.x, mousePos.y,
                canvasRegionMin.x, canvasRegionMin.y,
                canvasCoords
            );

            // For move tool, allow coordinates outside canvas bounds (for scaling to edges)
            // For other tools, constrain to canvas bounds
            boolean allowOutOfBounds = currentTool instanceof MoveToolController;
            boolean coordsValid = validCoords && (allowOutOfBounds || canvas.isValidCoordinate(canvasCoords[0], canvasCoords[1]));

            if (coordsValid) {
                // Create new draw command when starting a drawing operation
                if (!wasMouseDown) {
                    currentDrawCommand = new DrawCommand(canvas, currentTool.getName());
                    currentTool.onMouseDown(canvasCoords[0], canvasCoords[1], currentColor, canvas, currentDrawCommand);
                } else {
                    currentTool.onMouseDrag(canvasCoords[0], canvasCoords[1], currentColor, canvas, currentDrawCommand);
                }

                // Notify that drawing occurred
                if (onDrawCallback != null) {
                    onDrawCallback.run();
                }
            }
        }

        // Mouse button released
        if (wasMouseDown && !leftMouseDown && currentTool != null) {
            currentTool.onMouseUp(currentColor, canvas, currentDrawCommand);

            // Handle color picker tool
            if (currentTool instanceof ColorPickerTool) {
                ColorPickerTool colorPicker = (ColorPickerTool) currentTool;
                int pickedColor = colorPicker.getPickedColor();
                if (onColorPickedCallback != null) {
                    onColorPickedCallback.accept(pickedColor);
                    logger.debug("Color picked: 0x{}", Integer.toHexString(pickedColor));
                }
            }
            // Handle selection tools (rectangle, ellipse, lasso, etc.)
            else if (currentTool instanceof SelectionTool) {
                SelectionTool selectionTool = (SelectionTool) currentTool;

                // Handle selection creation/update
                if (selectionTool.hasSelection()) {
                    SelectionRegion createdSelection = selectionTool.getSelection();
                    if (onSelectionCreatedCallback != null) {
                        onSelectionCreatedCallback.accept(createdSelection);
                        if (createdSelection != null) {
                            logger.debug("Selection created: {}", createdSelection.getBounds());
                        } else {
                            logger.debug("Selection cleared");
                        }
                    }
                    selectionTool.clearSelection();
                }
            }
            // Move tool: Do NOT auto-execute on mouse up
            // The pending command remains as a preview-only operation until the user
            // explicitly commits with Enter key (handled in TextureCreatorImGui) or
            // cancels with ESC key. This allows multiple drag adjustments before committing.
            // Auto-executing on every mouse up would create multiple holes at intermediate
            // positions, which is incorrect behavior.
            // Note: Paste now uses move tool, so no separate paste command handling needed
            else {
                // Execute the command if it has changes (non-color-picker/selection tools)
                if (currentDrawCommand != null && currentDrawCommand.hasChanges() && commandHistory != null) {
                    commandHistory.executeCommand(currentDrawCommand);
                    logger.debug("Executed draw command: {}", currentDrawCommand.getDescription());

                    // Notify that a color was used on the canvas
                    if (onColorUsedCallback != null) {
                        onColorUsedCallback.accept(currentColor);
                        logger.debug("Color used on canvas: 0x{}", Integer.toHexString(currentColor));
                    }
                }
            }
            currentDrawCommand = null;

            // Notify that drawing occurred
            if (onDrawCallback != null) {
                onDrawCallback.run();
            }
        }

        // Update mouse state
        wasMouseDown = leftMouseDown;
        isMouseDown = leftMouseDown;
    }

    /**
     * Get the canvas renderer.
     * @return canvas renderer instance
     */
    public CanvasRenderer getRenderer() {
        return renderer;
    }

    /**
     * Cleanup resources.
     */
    public void dispose() {
        renderer.dispose();
        logger.debug("Canvas panel disposed");
    }
}
