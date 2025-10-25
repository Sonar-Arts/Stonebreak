package com.openmason.ui.components.textureCreator.transform;

import imgui.ImColor;
import imgui.ImDrawList;

import java.util.List;

/**
 * Renders transform handles for selection manipulation.
 * Uses ImGui's ImDrawList for consistency with the rest of the UI.
 * <p>
 * SOLID principles:
 * - Single Responsibility: Only handles visual rendering of transform handles
 * - KISS: Simple rectangle and circle drawing
 */
public class TransformHandleRenderer {

    // Visual scaling factor - multiplies hit radius for better visibility
    private static final float VISUAL_SCALE = 8.0f; // Makes 1px radius appear as 8px visual size
    // NOTE: Rotation handle offset is calculated in MoveTool (40 screen pixels / zoom) for consistent screen-space distance

    // Handle colors
    private static final int HANDLE_FILL_COLOR = ImColor.rgba(255, 255, 255, 255);      // White fill
    private static final int HANDLE_BORDER_COLOR = ImColor.rgba(51, 153, 255, 255);     // Blue border
    private static final int HANDLE_HOVER_COLOR = ImColor.rgba(255, 200, 0, 255);       // Orange when hovered
    private static final int ROTATION_HANDLE_COLOR = ImColor.rgba(51, 153, 255, 255);   // Blue
    private static final int ROTATION_HOVER_COLOR = ImColor.rgba(255, 200, 0, 255);     // Orange when hovered

    // Handle connection line (from top edge to rotation handle)
    private static final int CONNECTION_LINE_COLOR = ImColor.rgba(51, 153, 255, 180);
    private static final float CONNECTION_LINE_THICKNESS = 1.5f;

    /**
     * Renders all transform handles for a selection
     *
     * @param drawList     ImGui draw list to render to
     * @param handles      List of transform handles to render
     * @param hoveredHandle The currently hovered handle (null if none)
     * @param canvasX      Canvas x-position in screen coordinates
     * @param canvasY      Canvas y-position in screen coordinates
     * @param zoom         Current zoom level
     */
    public void render(ImDrawList drawList, List<TransformHandle> handles,
                      TransformHandle hoveredHandle, float canvasX, float canvasY, float zoom) {
        if (drawList == null || handles == null || handles.isEmpty()) {
            return;
        }

        // First pass: render connection line to rotation handle
        renderRotationConnectionLine(drawList, handles, canvasX, canvasY, zoom);

        // Second pass: render all handles
        for (TransformHandle handle : handles) {
            boolean isHovered = handle == hoveredHandle;

            if (handle.isRotation()) {
                renderRotationHandle(drawList, handle, isHovered, canvasX, canvasY, zoom);
            } else if (handle.isCenter()) {
                // Don't render center handle visually (it's the entire interior)
                continue;
            } else {
                renderRectangularHandle(drawList, handle, isHovered, canvasX, canvasY, zoom);
            }
        }
    }

    /**
     * Renders the connection line from top edge to rotation handle
     */
    private void renderRotationConnectionLine(ImDrawList drawList, List<TransformHandle> handles,
                                              float canvasX, float canvasY, float zoom) {
        // Find rotation handle and top edge center
        TransformHandle rotationHandle = null;
        double topEdgeCenterX = 0;
        double topEdgeCenterY = 0;

        for (TransformHandle handle : handles) {
            if (handle.isRotation()) {
                rotationHandle = handle;
            } else if (handle.getType() == TransformHandle.Type.EDGE_TOP) {
                topEdgeCenterX = handle.getX();
                topEdgeCenterY = handle.getY();
            }
        }

        if (rotationHandle == null) {
            return;
        }

        // Convert to screen coordinates
        float x1 = canvasX + (float) topEdgeCenterX * zoom;
        float y1 = canvasY + (float) topEdgeCenterY * zoom;
        float x2 = canvasX + (float) rotationHandle.getX() * zoom;
        float y2 = canvasY + (float) rotationHandle.getY() * zoom;

        // Draw connection line
        drawList.addLine(x1, y1, x2, y2, CONNECTION_LINE_COLOR, CONNECTION_LINE_THICKNESS);
    }

    /**
     * Renders a rectangular handle (corner or edge)
     */
    private void renderRectangularHandle(ImDrawList drawList, TransformHandle handle,
                                        boolean isHovered, float canvasX, float canvasY, float zoom) {
        // Convert handle position to screen coordinates
        float screenX = canvasX + (float) handle.getX() * zoom;
        float screenY = canvasY + (float) handle.getY() * zoom;

        // Calculate visual size based on hit radius (scaled for visibility)
        float visualSize = (float) handle.getHitRadius() * VISUAL_SCALE;
        float halfSize = visualSize / 2.0f;
        float x1 = screenX - halfSize;
        float y1 = screenY - halfSize;
        float x2 = screenX + halfSize;
        float y2 = screenY + halfSize;

        // Draw filled rectangle
        drawList.addRectFilled(x1, y1, x2, y2, HANDLE_FILL_COLOR);

        // Draw border (different color if hovered)
        int borderColor = isHovered ? HANDLE_HOVER_COLOR : HANDLE_BORDER_COLOR;
        drawList.addRect(x1, y1, x2, y2, borderColor, 0.0f, 0, 2.0f);
    }

    /**
     * Renders the rotation handle (circle above top edge)
     */
    private void renderRotationHandle(ImDrawList drawList, TransformHandle handle,
                                     boolean isHovered, float canvasX, float canvasY, float zoom) {
        // Convert handle position to screen coordinates
        float screenX = canvasX + (float) handle.getX() * zoom;
        float screenY = canvasY + (float) handle.getY() * zoom;

        // Determine color based on hover state
        int fillColor = isHovered ? ROTATION_HOVER_COLOR : ROTATION_HANDLE_COLOR;

        // Calculate visual radius based on hit radius (scaled for visibility)
        float visualRadius = (float) handle.getHitRadius() * VISUAL_SCALE / 2.0f;

        // Draw filled circle
        drawList.addCircleFilled(screenX, screenY, visualRadius, fillColor);

        // Draw white border for better visibility
        drawList.addCircle(screenX, screenY, visualRadius, HANDLE_FILL_COLOR, 0, 2.0f);
    }

    /**
     * Renders a rotated selection outline (quadrilateral)
     *
     * @param drawList       ImGui draw list to render to
     * @param rotatedCorners Array of 8 values [x1,y1, x2,y2, x3,y3, x4,y4] representing 4 corners
     * @param canvasX        Canvas x-position in screen coordinates
     * @param canvasY        Canvas y-position in screen coordinates
     * @param zoom           Current zoom level
     */
    public void renderRotatedSelection(ImDrawList drawList, double[] rotatedCorners,
                                      float canvasX, float canvasY, float zoom) {
        if (drawList == null || rotatedCorners == null || rotatedCorners.length != 8) {
            return;
        }

        // Convert corners to screen coordinates
        float[] screenCorners = new float[8];
        for (int i = 0; i < 4; i++) {
            screenCorners[i * 2] = canvasX + (float) rotatedCorners[i * 2] * zoom;
            screenCorners[i * 2 + 1] = canvasY + (float) rotatedCorners[i * 2 + 1] * zoom;
        }

        // Draw 4 lines connecting the corners (closed quadrilateral)
        int color = ImColor.rgba(255, 200, 0, 200); // Orange for rotation preview
        float thickness = 2.5f;

        // Draw edges
        drawList.addLine(screenCorners[0], screenCorners[1], screenCorners[2], screenCorners[3], color, thickness); // Top edge
        drawList.addLine(screenCorners[2], screenCorners[3], screenCorners[4], screenCorners[5], color, thickness); // Right edge
        drawList.addLine(screenCorners[4], screenCorners[5], screenCorners[6], screenCorners[7], color, thickness); // Bottom edge
        drawList.addLine(screenCorners[6], screenCorners[7], screenCorners[0], screenCorners[1], color, thickness); // Left edge
    }

    /**
     * Renders angle indicator text during rotation
     *
     * @param drawList       ImGui draw list to render to
     * @param angleDegrees   Rotation angle in degrees
     * @param centerX        Selection center x in canvas coordinates
     * @param centerY        Selection center y in canvas coordinates
     * @param canvasX        Canvas x-position in screen coordinates
     * @param canvasY        Canvas y-position in screen coordinates
     * @param zoom           Current zoom level
     */
    public void renderAngleIndicator(ImDrawList drawList, double angleDegrees,
                                     double centerX, double centerY,
                                     float canvasX, float canvasY, float zoom) {
        if (drawList == null) {
            return;
        }

        // Format angle text
        String angleText = String.format("%.1f°", angleDegrees);

        // Calculate screen position (slightly below center)
        float screenX = canvasX + (float) centerX * zoom;
        float screenY = canvasY + (float) centerY * zoom + 20.0f; // 20px below center

        // Calculate text size for background box
        imgui.ImVec2 textSize = imgui.ImGui.calcTextSize(angleText);
        float padding = 4.0f;

        // Draw semi-transparent background box
        int bgColor = ImColor.rgba(0, 0, 0, 180);
        drawList.addRectFilled(
            screenX - textSize.x / 2 - padding,
            screenY - textSize.y / 2 - padding,
            screenX + textSize.x / 2 + padding,
            screenY + textSize.y / 2 + padding,
            bgColor,
            3.0f // Rounded corners
        );

        // Draw white text
        int textColor = ImColor.rgba(255, 255, 255, 255);
        drawList.addText(
            screenX - textSize.x / 2,
            screenY - textSize.y / 2,
            textColor,
            angleText
        );
    }

}
