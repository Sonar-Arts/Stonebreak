package com.openmason.ui.components.textureCreator.panels;

import com.openmason.ui.components.textureCreator.canvas.CanvasRenderer;
import com.openmason.ui.components.textureCreator.canvas.CanvasState;
import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.CommandHistory;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.tools.ColorPickerTool;
import com.openmason.ui.components.textureCreator.tools.DrawingTool;
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
    private boolean isMouseDown = false;
    private boolean wasMouseDown = false;

    // Callback for when drawing occurs
    private Runnable onDrawCallback = null;

    // Callback for when color is picked (receives picked color)
    private java.util.function.IntConsumer onColorPickedCallback = null;

    // Current draw command (active during a drawing operation)
    private DrawCommand currentDrawCommand = null;

    /**
     * Create canvas panel.
     */
    public CanvasPanel() {
        this.renderer = new CanvasRenderer();
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
     * @param showGrid whether to show grid overlay
     * @param commandHistory command history for undo/redo
     * @param onDrawCallback callback invoked when drawing occurs (can be null)
     * @param onColorPickedCallback callback invoked when color is picked (can be null)
     */
    public void render(PixelCanvas displayCanvas, PixelCanvas drawingCanvas, CanvasState canvasState,
                      DrawingTool currentTool, int currentColor, boolean showGrid,
                      CommandHistory commandHistory, Runnable onDrawCallback,
                      java.util.function.IntConsumer onColorPickedCallback) {

        this.onDrawCallback = onDrawCallback;
        this.onColorPickedCallback = onColorPickedCallback;

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

        // Render the display canvas (composited view)
        renderer.render(displayCanvas, canvasState, showGrid);

        // Handle mouse input for drawing and navigation on the drawing canvas (active layer)
        handleInput(drawingCanvas != null ? drawingCanvas : displayCanvas, canvasState,
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

        // Get mouse position
        ImVec2 mousePos = ImGui.getMousePos();
        boolean leftMouseDown = ImGui.isMouseDown(0);   // Left button for drawing
        boolean middleMouseDown = ImGui.isMouseDown(2); // Middle button for panning
        float mouseWheel = ImGui.getIO().getMouseWheel();

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
            } else {
                // Execute the command if it has changes (non-color-picker tools)
                if (currentDrawCommand != null && currentDrawCommand.hasChanges() && commandHistory != null) {
                    commandHistory.executeCommand(currentDrawCommand);
                    logger.debug("Executed draw command: {}", currentDrawCommand.getDescription());
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
