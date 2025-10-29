package com.openmason.ui.components.textureCreator.tools.move.modules;

import com.openmason.ui.components.textureCreator.canvas.CanvasState;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import imgui.ImDrawList;
import imgui.ImGui;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Renders the selection with transformation handles and visual feedback.
 * Styled to match Photoshop's transform controls with hollow handles,
 * marching ants, and professional visual feedback.
 */
public class SelectionTransformRenderer {

    // Handle sizing
    private static final int HANDLE_SIZE = 8;
    private static final int HANDLE_BORDER_THICKNESS = 1;
    private static final int ROTATION_HANDLE_RADIUS = 5;
    private static final int CENTER_PIVOT_SIZE = 6;

    // Line thicknesses
    private static final float SELECTION_OUTLINE_THICKNESS = 1.0f;
    private static final float ROTATION_LINE_THICKNESS = 1.0f;
    private static final float HANDLE_BORDER_WIDTH = 1.5f;

    // Colors - Photoshop-style grayscale with subtle blue accents
    private static final int COLOR_BLACK = ImGui.getColorU32(0, 0, 0, 255);
    private static final int COLOR_WHITE = ImGui.getColorU32(255, 255, 255, 255);
    private static final int COLOR_GRAY_DARK = ImGui.getColorU32(60, 60, 60, 255);
    private static final int COLOR_GRAY_LIGHT = ImGui.getColorU32(200, 200, 200, 255);
    private static final int COLOR_BLUE_ACCENT = ImGui.getColorU32(0, 120, 215, 255);
    private static final int COLOR_BLUE_HOVER = ImGui.getColorU32(80, 160, 235, 255);
    private static final int COLOR_PREVIEW_DASH = ImGui.getColorU32(255, 200, 0, 255);

    // Marching ants animation
    private static final int MARCHING_ANTS_DASH_LENGTH = 4;
    private static final int MARCHING_ANTS_GAP_LENGTH = 4;
    private float marchingAntsOffset = 0.0f;

    private final HandleDetector handleDetector;

    public SelectionTransformRenderer() {
        this.handleDetector = new HandleDetector();
    }

    /**
     * Renders the selection with transformation handles.
     * Features Photoshop-style marching ants animation and hollow handles.
     *
     * @param drawList ImGui draw list
     * @param selection The selection region
     * @param canvasState Canvas state for coordinate conversion
     * @param transform Current transformation state
     * @param showHandles Whether to show transformation handles
     * @param hoveredHandle The handle currently under cursor (can be null)
     * @param canvasDisplayX Canvas display area X offset
     * @param canvasDisplayY Canvas display area Y offset
     */
    public void render(ImDrawList drawList,
                      SelectionRegion selection,
                      CanvasState canvasState,
                      TransformState transform,
                      boolean showHandles,
                      HandleType hoveredHandle,
                      float canvasDisplayX,
                      float canvasDisplayY) {

        if (selection == null || selection.isEmpty()) {
            return;
        }

        // Update marching ants animation
        updateMarchingAntsAnimation();

        // Get transformed bounds and screen coordinates
        Rectangle canvasBounds = selection.getBounds();
        Rectangle transformedBounds = applyTransformToBounds(canvasBounds, transform);

        Point topLeft = canvasToScreen(canvasState, transformedBounds.x, transformedBounds.y,
                                       canvasDisplayX, canvasDisplayY);
        Point topRight = canvasToScreen(canvasState, transformedBounds.x + transformedBounds.width,
                                        transformedBounds.y, canvasDisplayX, canvasDisplayY);
        Point bottomLeft = canvasToScreen(canvasState, transformedBounds.x,
                                          transformedBounds.y + transformedBounds.height,
                                          canvasDisplayX, canvasDisplayY);
        Point bottomRight = canvasToScreen(canvasState, transformedBounds.x + transformedBounds.width,
                                           transformedBounds.y + transformedBounds.height,
                                           canvasDisplayX, canvasDisplayY);

        // Draw marching ants border (Photoshop-style animated dashed line)
        drawMarchingAnts(drawList, topLeft, topRight, bottomRight, bottomLeft);

        // Draw handles if requested
        if (showHandles) {
            renderHandles(drawList, selection, canvasState, transform, hoveredHandle,
                    canvasDisplayX, canvasDisplayY, topLeft, topRight, bottomLeft, bottomRight);

            // Draw center pivot point
            drawCenterPivot(drawList, transform, canvasState, canvasDisplayX, canvasDisplayY);
        }
    }

    /**
     * Updates the marching ants animation offset for smooth scrolling effect.
     */
    private void updateMarchingAntsAnimation() {
        marchingAntsOffset += 0.5f; // Adjust speed as needed
        if (marchingAntsOffset >= MARCHING_ANTS_DASH_LENGTH + MARCHING_ANTS_GAP_LENGTH) {
            marchingAntsOffset = 0.0f;
        }
    }

    /**
     * Converts canvas coordinates to screen coordinates.
     */
    private Point canvasToScreen(CanvasState canvasState, int canvasX, int canvasY,
                                 float canvasDisplayX, float canvasDisplayY) {
        float[] screenCoords = new float[2];
        canvasState.canvasToScreenCoords(canvasX, canvasY, canvasDisplayX, canvasDisplayY, screenCoords);
        return new Point((int)screenCoords[0], (int)screenCoords[1]);
    }

    /**
     * Draws animated marching ants border around the selection (Photoshop-style).
     */
    private void drawMarchingAnts(ImDrawList drawList, Point p1, Point p2, Point p3, Point p4) {
        // Draw black outline first (background)
        drawDashedLine(drawList, p1, p2, COLOR_BLACK,
                      MARCHING_ANTS_DASH_LENGTH, MARCHING_ANTS_GAP_LENGTH, 0);
        drawDashedLine(drawList, p2, p3, COLOR_BLACK,
                      MARCHING_ANTS_DASH_LENGTH, MARCHING_ANTS_GAP_LENGTH, 0);
        drawDashedLine(drawList, p3, p4, COLOR_BLACK,
                      MARCHING_ANTS_DASH_LENGTH, MARCHING_ANTS_GAP_LENGTH, 0);
        drawDashedLine(drawList, p4, p1, COLOR_BLACK,
                      MARCHING_ANTS_DASH_LENGTH, MARCHING_ANTS_GAP_LENGTH, 0);

        // Draw white dashes on top (animated)
        drawDashedLine(drawList, p1, p2, COLOR_WHITE,
                      MARCHING_ANTS_DASH_LENGTH, MARCHING_ANTS_GAP_LENGTH, marchingAntsOffset);
        drawDashedLine(drawList, p2, p3, COLOR_WHITE,
                      MARCHING_ANTS_DASH_LENGTH, MARCHING_ANTS_GAP_LENGTH, marchingAntsOffset);
        drawDashedLine(drawList, p3, p4, COLOR_WHITE,
                      MARCHING_ANTS_DASH_LENGTH, MARCHING_ANTS_GAP_LENGTH, marchingAntsOffset);
        drawDashedLine(drawList, p4, p1, COLOR_WHITE,
                      MARCHING_ANTS_DASH_LENGTH, MARCHING_ANTS_GAP_LENGTH, marchingAntsOffset);
    }

    /**
     * Draws the center pivot point crosshair.
     */
    private void drawCenterPivot(ImDrawList drawList, TransformState transform,
                                 CanvasState canvasState, float canvasDisplayX, float canvasDisplayY) {
        Point pivot = transform.getPivot();
        Point screenPivot = canvasToScreen(canvasState, pivot.x, pivot.y, canvasDisplayX, canvasDisplayY);

        int halfSize = CENTER_PIVOT_SIZE / 2;

        // Draw outer circle
        drawList.addCircle(screenPivot.x, screenPivot.y, halfSize + 1, COLOR_BLACK, 12, 1.5f);
        drawList.addCircle(screenPivot.x, screenPivot.y, halfSize, COLOR_WHITE, 12, 1.0f);

        // Draw crosshair
        drawList.addLine(screenPivot.x - halfSize, screenPivot.y,
                        screenPivot.x + halfSize, screenPivot.y, COLOR_BLACK, 1.5f);
        drawList.addLine(screenPivot.x, screenPivot.y - halfSize,
                        screenPivot.x, screenPivot.y + halfSize, COLOR_BLACK, 1.5f);
        drawList.addLine(screenPivot.x - halfSize + 1, screenPivot.y,
                        screenPivot.x + halfSize - 1, screenPivot.y, COLOR_WHITE, 1.0f);
        drawList.addLine(screenPivot.x, screenPivot.y - halfSize + 1,
                        screenPivot.x, screenPivot.y + halfSize - 1, COLOR_WHITE, 1.0f);
    }

    /**
     * Renders all transformation handles (Photoshop-style hollow squares and rotation handle).
     */
    private void renderHandles(ImDrawList drawList,
                               SelectionRegion selection,
                               CanvasState canvasState,
                               TransformState transform,
                               HandleType hoveredHandle,
                               float canvasDisplayX,
                               float canvasDisplayY,
                               Point topLeft,
                               Point topRight,
                               Point bottomLeft,
                               Point bottomRight) {

        HandleDetector.HandlePositions positions = handleDetector.calculateHandlePositions(
                selection, canvasState, transform, canvasDisplayX, canvasDisplayY);

        if (positions == null) {
            return;
        }

        // Draw transform bounds outline (clean, thin line)
        drawList.addLine(topLeft.x, topLeft.y, topRight.x, topRight.y,
                        COLOR_GRAY_DARK, SELECTION_OUTLINE_THICKNESS);
        drawList.addLine(topRight.x, topRight.y, bottomRight.x, bottomRight.y,
                        COLOR_GRAY_DARK, SELECTION_OUTLINE_THICKNESS);
        drawList.addLine(bottomRight.x, bottomRight.y, bottomLeft.x, bottomLeft.y,
                        COLOR_GRAY_DARK, SELECTION_OUTLINE_THICKNESS);
        drawList.addLine(bottomLeft.x, bottomLeft.y, topLeft.x, topLeft.y,
                        COLOR_GRAY_DARK, SELECTION_OUTLINE_THICKNESS);

        // Draw rotation line (connecting top-center to rotation handle)
        Point topCenter = positions.topCenter;
        Point rotation = positions.rotation;
        drawList.addLine(topCenter.x, topCenter.y, rotation.x, rotation.y,
                        COLOR_GRAY_LIGHT, ROTATION_LINE_THICKNESS);

        // Draw resize handles (hollow squares - Photoshop style)
        drawHollowHandle(drawList, positions.topLeft, hoveredHandle == HandleType.TOP_LEFT);
        drawHollowHandle(drawList, positions.topRight, hoveredHandle == HandleType.TOP_RIGHT);
        drawHollowHandle(drawList, positions.bottomLeft, hoveredHandle == HandleType.BOTTOM_LEFT);
        drawHollowHandle(drawList, positions.bottomRight, hoveredHandle == HandleType.BOTTOM_RIGHT);
        drawHollowHandle(drawList, positions.topCenter, hoveredHandle == HandleType.TOP_CENTER);
        drawHollowHandle(drawList, positions.bottomCenter, hoveredHandle == HandleType.BOTTOM_CENTER);
        drawHollowHandle(drawList, positions.middleLeft, hoveredHandle == HandleType.MIDDLE_LEFT);
        drawHollowHandle(drawList, positions.middleRight, hoveredHandle == HandleType.MIDDLE_RIGHT);

        // Draw rotation handle (hollow circle with icon)
        drawRotationHandle(drawList, rotation, hoveredHandle == HandleType.ROTATION);
    }

    /**
     * Draws a hollow square handle (Photoshop-style).
     * Handles are white with black borders, highlighted with blue when hovered.
     */
    private void drawHollowHandle(ImDrawList drawList, Point position, boolean isHovered) {
        int halfSize = HANDLE_SIZE / 2;

        float x1 = position.x - halfSize;
        float y1 = position.y - halfSize;
        float x2 = position.x + halfSize;
        float y2 = position.y + halfSize;

        // Draw white background fill
        drawList.addRectFilled(x1, y1, x2, y2, COLOR_WHITE);

        // Draw outer border (black)
        drawList.addRect(x1, y1, x2, y2, COLOR_BLACK, 0.0f, 0, HANDLE_BORDER_WIDTH);

        // Draw highlight if hovered (subtle blue inner border)
        if (isHovered) {
            drawList.addRect(x1 + 1, y1 + 1, x2 - 1, y2 - 1,
                           COLOR_BLUE_HOVER, 0.0f, 0, 1.0f);
        }
    }

    /**
     * Draws a hollow circular rotation handle (Photoshop-style).
     * Features a rotation icon inside the circle.
     */
    private void drawRotationHandle(ImDrawList drawList, Point position, boolean isHovered) {
        // Draw white fill
        drawList.addCircleFilled(position.x, position.y, ROTATION_HANDLE_RADIUS, COLOR_WHITE, 16);

        // Draw outer border (black)
        drawList.addCircle(position.x, position.y, ROTATION_HANDLE_RADIUS,
                          COLOR_BLACK, 16, HANDLE_BORDER_WIDTH);

        // Draw highlight if hovered (subtle blue inner circle)
        if (isHovered) {
            drawList.addCircle(position.x, position.y, ROTATION_HANDLE_RADIUS - 1,
                             COLOR_BLUE_HOVER, 16, 1.0f);
        }

        // Draw rotation icon (curved arrow suggestion)
        int iconSize = 3;
        int iconColor = isHovered ? COLOR_BLUE_ACCENT : COLOR_GRAY_DARK;

        // Simple crosshair-like rotation indicator
        drawList.addLine(position.x - iconSize, position.y,
                        position.x + iconSize, position.y, iconColor, 1.0f);
        drawList.addLine(position.x, position.y - iconSize,
                        position.x, position.y + iconSize, iconColor, 1.0f);
    }

    private Rectangle applyTransformToBounds(Rectangle bounds, TransformState transform) {
        // Apply scale to dimensions
        int scaledWidth = (int) Math.round(bounds.width * transform.getScaleX());
        int scaledHeight = (int) Math.round(bounds.height * transform.getScaleY());

        // Calculate position from pivot point
        // The pivot is the fixed point that doesn't move during scaling
        Point absolutePivot = transform.getPivot();
        int relativePivotX = absolutePivot.x - bounds.x;
        int relativePivotY = absolutePivot.y - bounds.y;

        // The top-left corner position after scaling from the pivot
        int translatedX = absolutePivot.x - (int) Math.round(relativePivotX * transform.getScaleX()) + transform.getTranslateX();
        int translatedY = absolutePivot.y - (int) Math.round(relativePivotY * transform.getScaleY()) + transform.getTranslateY();

        // Note: Rotation is not applied to bounds rectangle (would need oriented bounding box)
        // Rotation is applied to individual pixels during transformation
        return new Rectangle(translatedX, translatedY, scaledWidth, scaledHeight);
    }

    /**
     * Renders a preview outline showing where the selection will be after transformation.
     * Used during active dragging.
     */
    public void renderPreview(ImDrawList drawList,
                             SelectionRegion selection,
                             CanvasState canvasState,
                             TransformState transform,
                             float canvasDisplayX,
                             float canvasDisplayY) {
        if (selection == null || selection.isEmpty()) {
            return;
        }

        Rectangle canvasBounds = selection.getBounds();
        Rectangle transformedBounds = applyTransformToBounds(canvasBounds, transform);

        float[] screenCoords = new float[2];

        canvasState.canvasToScreenCoords(transformedBounds.x, transformedBounds.y,
                canvasDisplayX, canvasDisplayY, screenCoords);
        Point topLeft = new Point((int)screenCoords[0], (int)screenCoords[1]);

        canvasState.canvasToScreenCoords(transformedBounds.x + transformedBounds.width,
                transformedBounds.y, canvasDisplayX, canvasDisplayY, screenCoords);
        Point topRight = new Point((int)screenCoords[0], (int)screenCoords[1]);

        canvasState.canvasToScreenCoords(transformedBounds.x,
                transformedBounds.y + transformedBounds.height,
                canvasDisplayX, canvasDisplayY, screenCoords);
        Point bottomLeft = new Point((int)screenCoords[0], (int)screenCoords[1]);

        canvasState.canvasToScreenCoords(transformedBounds.x + transformedBounds.width,
                transformedBounds.y + transformedBounds.height,
                canvasDisplayX, canvasDisplayY, screenCoords);
        Point bottomRight = new Point((int)screenCoords[0], (int)screenCoords[1]);

        // Draw dashed outline
        drawDashedQuad(drawList, topLeft, topRight, bottomRight, bottomLeft);
    }

    /**
     * Draws a dashed quadrilateral for preview visualization.
     */
    private void drawDashedQuad(ImDrawList drawList, Point p1, Point p2, Point p3, Point p4) {
        // Draw preview outline with contrasting color
        drawDashedLine(drawList, p1, p2, COLOR_PREVIEW_DASH, 5, 3, 0);
        drawDashedLine(drawList, p2, p3, COLOR_PREVIEW_DASH, 5, 3, 0);
        drawDashedLine(drawList, p3, p4, COLOR_PREVIEW_DASH, 5, 3, 0);
        drawDashedLine(drawList, p4, p1, COLOR_PREVIEW_DASH, 5, 3, 0);
    }

    /**
     * Draws a dashed line with optional animation offset for marching ants effect.
     *
     * @param drawList ImGui draw list
     * @param start Start point
     * @param end End point
     * @param color Line color
     * @param dashLength Length of each dash segment
     * @param gapLength Length of each gap segment
     * @param offset Animation offset for marching ants (0 for static lines)
     */
    private void drawDashedLine(ImDrawList drawList, Point start, Point end,
                               int color, int dashLength, int gapLength, float offset) {
        int dx = end.x - start.x;
        int dy = end.y - start.y;
        double length = Math.sqrt(dx * dx + dy * dy);

        if (length == 0) return;

        double segmentLength = dashLength + gapLength;
        int numSegments = (int) Math.ceil((length + offset) / segmentLength);

        for (int i = 0; i <= numSegments; i++) {
            double t1 = (i * segmentLength - offset) / length;
            double t2 = Math.min(1.0, (i * segmentLength - offset + dashLength) / length);

            // Skip segments that are completely outside the line
            if (t2 <= 0.0 || t1 >= 1.0) continue;

            // Clamp to line bounds
            t1 = Math.max(0.0, t1);
            t2 = Math.min(1.0, t2);

            float x1 = start.x + (float) (dx * t1);
            float y1 = start.y + (float) (dy * t1);
            float x2 = start.x + (float) (dx * t2);
            float y2 = start.y + (float) (dy * t2);

            drawList.addLine(x1, y1, x2, y2, color, SELECTION_OUTLINE_THICKNESS);
        }
    }
}
