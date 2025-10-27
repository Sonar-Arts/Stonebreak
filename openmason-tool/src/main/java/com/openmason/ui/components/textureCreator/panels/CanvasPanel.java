package com.openmason.ui.components.textureCreator.panels;

import com.openmason.ui.components.textureCreator.canvas.CanvasRenderer;
import com.openmason.ui.components.textureCreator.canvas.CanvasState;
import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.Command;
import com.openmason.ui.components.textureCreator.commands.CommandHistory;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.commands.TranslateSelectionCommand;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.selection.SelectionRenderer;
import com.openmason.ui.components.textureCreator.tools.ColorPickerTool;
import com.openmason.ui.components.textureCreator.tools.DrawingTool;
import com.openmason.ui.components.textureCreator.tools.FreeSelectionTool;
import com.openmason.ui.components.textureCreator.tools.MoveTool;
import com.openmason.ui.components.textureCreator.tools.RectangleSelectionTool;
import com.openmason.ui.components.textureCreator.tools.selection.PixelPreview;
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
    public void render(PixelCanvas displayCanvas, PixelCanvas drawingCanvas, CanvasState canvasState,
                      DrawingTool currentTool, int currentColor, SelectionRegion currentSelection,
                      boolean showGrid, float gridOpacity, float cubeNetOverlayOpacity,
                      CommandHistory commandHistory, Runnable onDrawCallback,
                      java.util.function.IntConsumer onColorPickedCallback,
                      java.util.function.IntConsumer onColorUsedCallback,
                      java.util.function.Consumer<SelectionRegion> onSelectionCreatedCallback) {

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

        // Get selection preview bounds if using rectangle selection tool
        int[] selectionPreviewBounds = null;
        if (currentTool instanceof RectangleSelectionTool) {
            RectangleSelectionTool selectionTool = (RectangleSelectionTool) currentTool;
            selectionPreviewBounds = selectionTool.getSelectionPreviewBounds();
        }
        // Note: MoveTool and FreeSelectionTool render their own previews below

        // Render the display canvas (composited view) with opacity settings
        renderer.render(displayCanvas, canvasState, showGrid, gridOpacity, cubeNetOverlayOpacity,
                       currentSelection, selectionPreviewBounds);

        // Render free selection pixel preview if using free selection tool
        if (currentTool instanceof FreeSelectionTool) {
            FreeSelectionTool freeSelectionTool = (FreeSelectionTool) currentTool;
            PixelPreview pixelPreview = freeSelectionTool.getPixelPreview();
            if (pixelPreview != null) {
                imgui.ImDrawList drawList = imgui.ImGui.getWindowDrawList();
                float canvasX = canvasRegionMin.x + canvasState.getPanOffsetX();
                float canvasY = canvasRegionMin.y + canvasState.getPanOffsetY();
                selectionRenderer.renderPixelPreview(drawList, pixelPreview.getPixels(), canvasX, canvasY, zoom);
            }
        }

        // Render transform handles if using move tool with active selection
        if (currentTool instanceof MoveTool && currentSelection != null && !currentSelection.isEmpty()) {
            MoveTool moveTool = (MoveTool) currentTool;
            imgui.ImDrawList drawList = imgui.ImGui.getWindowDrawList();
            float canvasX = canvasRegionMin.x + canvasState.getPanOffsetX();
            float canvasY = canvasRegionMin.y + canvasState.getPanOffsetY();
            moveTool.renderTransformHandles(drawList, canvasX, canvasY, zoom);
        }

        // Sync selection to canvas instances for constraint checking
        PixelCanvas targetCanvas = drawingCanvas != null ? drawingCanvas : displayCanvas;
        targetCanvas.setActiveSelection(currentSelection);
        if (displayCanvas != targetCanvas) {
            displayCanvas.setActiveSelection(currentSelection);
        }

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

        // Handle zoom with mouse wheel
        if (mouseWheel != 0.0f) {
            if (mouseWheel > 0) {
                canvasState.zoomIn(1.2f);
            } else {
                canvasState.zoomOut(1.2f);
            }
        }

        // Handle panning with middle mouse button
        if (middleMouseDown) {
            if (!canvasState.isPanning()) {
                canvasState.startPanning(mousePos.x, mousePos.y);
            } else {
                canvasState.updatePanning(mousePos.x, mousePos.y);
            }
        } else if (canvasState.isPanning()) {
            canvasState.stopPanning();
        }

        // Update MoveTool with current selection, shift key state, and hovered handle
        if (currentTool instanceof MoveTool && !canvasState.isPanning()) {
            MoveTool moveTool = (MoveTool) currentTool;

            // Update selection (generates handles if needed)
            SelectionRegion activeSelection = canvas.getActiveSelection();
            float zoom = canvasState.getZoomLevel();
            moveTool.updateSelection(activeSelection, zoom);

            moveTool.setShiftHeld(shiftHeld);

            // Update hovered handle for cursor feedback
            int[] canvasCoords = new int[2];
            boolean validCoords = canvasState.screenToCanvasCoords(
                mousePos.x, mousePos.y,
                canvasRegionMin.x, canvasRegionMin.y,
                canvasCoords
            );
            if (validCoords && canvas.isValidCoordinate(canvasCoords[0], canvasCoords[1])) {
                moveTool.updateHoveredHandle(canvasCoords[0], canvasCoords[1]);
            }
        }

        // Handle drawing with left mouse button
        if (leftMouseDown && currentTool != null && !canvasState.isPanning()) {
            // Convert screen coordinates to canvas pixel coordinates
            int[] canvasCoords = new int[2];
            boolean validCoords = canvasState.screenToCanvasCoords(
                mousePos.x, mousePos.y,
                canvasRegionMin.x, canvasRegionMin.y,
                canvasCoords
            );

            if (validCoords && canvas.isValidCoordinate(canvasCoords[0], canvasCoords[1])) {
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
            // Handle rectangle selection tool
            else if (currentTool instanceof RectangleSelectionTool) {
                RectangleSelectionTool selectionTool = (RectangleSelectionTool) currentTool;

                // Handle selection creation/update
                if (selectionTool.wasSelectionCreated()) {
                    SelectionRegion createdSelection = selectionTool.getCreatedSelection();
                    if (onSelectionCreatedCallback != null) {
                        onSelectionCreatedCallback.accept(createdSelection);
                        if (createdSelection != null) {
                            logger.debug("Rectangle selection created: {}", createdSelection.getBounds());
                        } else {
                            logger.debug("Selection cleared");
                        }
                    }
                    selectionTool.clearSelectionCreatedFlag();
                }
            }
            // Handle free selection tool
            else if (currentTool instanceof FreeSelectionTool) {
                FreeSelectionTool freeSelectionTool = (FreeSelectionTool) currentTool;

                // Handle selection creation/update
                if (freeSelectionTool.wasSelectionCreated()) {
                    SelectionRegion createdSelection = freeSelectionTool.getCreatedSelection();
                    if (onSelectionCreatedCallback != null) {
                        onSelectionCreatedCallback.accept(createdSelection);
                        if (createdSelection != null) {
                            logger.debug("Free selection created: {}", createdSelection.getBounds());
                        } else {
                            logger.debug("Selection cleared");
                        }
                    }
                    freeSelectionTool.clearSelectionCreatedFlag();
                }
            }
            // Handle move tool
            else if (currentTool instanceof MoveTool) {
                MoveTool moveTool = (MoveTool) currentTool;

                // Handle transform command (move, scale, stretch, or rotate)
                if (moveTool.wasTransformPerformed()) {
                    Command transformCommand = moveTool.getCompletedCommand();
                    if (transformCommand != null && commandHistory != null) {
                        commandHistory.executeCommand(transformCommand);
                        logger.debug("Executed transform command: {}", transformCommand.getDescription());
                    }

                    // Update selection to new position/size
                    SelectionRegion updatedSelection = moveTool.getUpdatedSelection();
                    if (updatedSelection != null && onSelectionCreatedCallback != null) {
                        onSelectionCreatedCallback.accept(updatedSelection);
                        logger.debug("Selection transformed to: {}", updatedSelection.getBounds());
                    }

                    moveTool.clearTransformPerformedFlag();
                }
            } else {
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
