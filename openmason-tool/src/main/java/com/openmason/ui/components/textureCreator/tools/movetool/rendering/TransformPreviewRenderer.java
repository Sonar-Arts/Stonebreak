package com.openmason.ui.components.textureCreator.tools.moveTool.rendering;

import com.openmason.ui.components.textureCreator.selection.FreeSelection;
import com.openmason.ui.components.textureCreator.tools.moveTool.state.DragState;
import com.openmason.ui.components.textureCreator.tools.moveTool.state.TransformState;
import com.openmason.ui.components.textureCreator.transform.TransformHandle;
import com.openmason.ui.components.textureCreator.transform.TransformHandleRenderer;
import imgui.ImDrawList;

import java.util.List;

/**
 * Renders transform handles and preview during drag operations.
 * Wraps TransformHandleRenderer and adds preview rendering logic.
 * Follows Single Responsibility Principle - only handles rendering.
 *
 * @author Open Mason Team
 */
public class TransformPreviewRenderer {

    private final TransformHandleRenderer handleRenderer = new TransformHandleRenderer();

    /**
     * Renders transform handles and preview based on current drag state.
     *
     * @param drawList ImGui draw list for rendering
     * @param handles List of transform handles
     * @param hoveredHandle Currently hovered handle (can be null)
     * @param dragState Current drag state
     * @param state Transform state containing preview coordinates
     * @param canvasX Canvas X offset in screen space
     * @param canvasY Canvas Y offset in screen space
     * @param zoom Current zoom level
     */
    public void render(ImDrawList drawList, List<TransformHandle> handles, TransformHandle hoveredHandle,
                       DragState dragState, TransformState state, float canvasX, float canvasY, float zoom) {
        if (handles.isEmpty()) {
            return;
        }

        // Special rendering during rotation
        if (dragState == DragState.ROTATING && state.getRotatedCorners() != null && state.getRotatedHandles() != null) {
            renderRotationMode(drawList, state, hoveredHandle, canvasX, canvasY, zoom);
        } else {
            // Normal rendering
            handleRenderer.render(drawList, handles, hoveredHandle, canvasX, canvasY, zoom);

            // Render preview bounds during non-rotation drag
            if (dragState != DragState.IDLE) {
                renderPreviewBounds(drawList, state, canvasX, canvasY, zoom);
            }
        }
    }

    /**
     * Renders the rotation mode visualization including rotated selection outline,
     * rotated handles, and angle indicator.
     */
    private void renderRotationMode(ImDrawList drawList, TransformState state, TransformHandle hoveredHandle,
                                    float canvasX, float canvasY, float zoom) {
        // Render rotated selection outline
        handleRenderer.renderRotatedSelection(drawList, state.getRotatedCorners(), canvasX, canvasY, zoom);

        // Render rotated free selection preview if available
        if (state.getRotatedFreeSelectionPreview() != null) {
            renderRotatedFreeSelectionPreview(drawList, state.getRotatedFreeSelectionPreview(),
                canvasX, canvasY, zoom);
        }

        // Render rotated handles
        handleRenderer.render(drawList, state.getRotatedHandles(), hoveredHandle, canvasX, canvasY, zoom);

        // Render angle indicator (using visual box center)
        double boxRight = state.getOriginalX2() + 1;
        double boxBottom = state.getOriginalY2() + 1;
        double centerX = (state.getOriginalX1() + boxRight) / 2.0;
        double centerY = (state.getOriginalY1() + boxBottom) / 2.0;
        handleRenderer.renderAngleIndicator(drawList, state.getRotationAngleDegrees(),
            centerX, centerY, canvasX, canvasY, zoom);
    }

    /**
     * Renders preview bounds during transform operations (non-rotation).
     */
    private void renderPreviewBounds(ImDrawList drawList, TransformState state,
                                     float canvasX, float canvasY, float zoom) {
        int color = imgui.ImColor.rgba(255, 200, 0, 150); // Orange semi-transparent

        // Render preview box matching selection box coordinate system
        float x1 = canvasX + state.getPreviewX1() * zoom;
        float y1 = canvasY + state.getPreviewY1() * zoom;
        float x2 = canvasX + (state.getPreviewX2() + 1) * zoom;  // +1 to match selection rendering
        float y2 = canvasY + (state.getPreviewY2() + 1) * zoom;  // +1 to match selection rendering

        drawList.addRect(x1, y1, x2, y2, color, 0.0f, 0, 2.0f);
    }

    /**
     * Renders the rotated free selection preview during rotation.
     */
    private void renderRotatedFreeSelectionPreview(ImDrawList drawList, FreeSelection rotatedPreview,
                                                   float canvasX, float canvasY, float zoom) {
        int previewColor = imgui.ImColor.rgba(100, 150, 255, 100); // Blue semi-transparent

        // Render each pixel in the rotated selection
        for (FreeSelection.Pixel pixel : rotatedPreview.getPixels()) {
            float x1 = canvasX + pixel.x * zoom;
            float y1 = canvasY + pixel.y * zoom;
            float x2 = x1 + zoom;
            float y2 = y1 + zoom;

            drawList.addRectFilled(x1, y1, x2, y2, previewColor);
        }
    }
}
