package com.openmason.ui.components.textureCreator.tools.move.modules;

import com.openmason.ui.components.textureCreator.canvas.CanvasState;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import imgui.ImDrawList;
import imgui.ImGui;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Renders the selection with transformation handles and visual feedback.
 * Provides visual cues for the transform state.
 */
public class SelectionTransformRenderer {

    private static final int HANDLE_SIZE = 8;
    private static final int ROTATION_HANDLE_SIZE = 10;
    private static final int HANDLE_COLOR_NORMAL = ImGui.getColorU32(255, 255, 255, 255);
    private static final int HANDLE_COLOR_BORDER = ImGui.getColorU32(0, 0, 0, 255);
    private static final int SELECTION_OUTLINE_COLOR = ImGui.getColorU32(0, 150, 255, 255);
    private static final int SELECTION_FILL_COLOR = ImGui.getColorU32(0, 150, 255, 0); // Fully transparent
    private static final int ROTATION_LINE_COLOR = ImGui.getColorU32(0, 150, 255, 180);

    private final HandleDetector handleDetector;

    public SelectionTransformRenderer() {
        this.handleDetector = new HandleDetector();
    }

    /**
     * Renders the selection with transformation handles.
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

        // Get transformed bounds
        Rectangle canvasBounds = selection.getBounds();
        Rectangle transformedBounds = applyTransformToBounds(canvasBounds, transform);

        // Convert to screen coordinates
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

        // Draw selection outline
        drawList.addQuad(
                (float)topLeft.x, (float)topLeft.y,
                (float)topRight.x, (float)topRight.y,
                (float)bottomRight.x, (float)bottomRight.y,
                (float)bottomLeft.x, (float)bottomLeft.y,
                SELECTION_OUTLINE_COLOR,
                2.0f
        );

        // Draw semi-transparent fill
        drawList.addQuadFilled(
                (float)topLeft.x, (float)topLeft.y,
                (float)topRight.x, (float)topRight.y,
                (float)bottomRight.x, (float)bottomRight.y,
                (float)bottomLeft.x, (float)bottomLeft.y,
                SELECTION_FILL_COLOR
        );

        // Draw handles if requested
        if (showHandles) {
            renderHandles(drawList, selection, canvasState, transform, hoveredHandle,
                    canvasDisplayX, canvasDisplayY);
        }
    }

    private void renderHandles(ImDrawList drawList,
                               SelectionRegion selection,
                               CanvasState canvasState,
                               TransformState transform,
                               HandleType hoveredHandle,
                               float canvasDisplayX,
                               float canvasDisplayY) {

        HandleDetector.HandlePositions positions = handleDetector.calculateHandlePositions(
                selection, canvasState, transform, canvasDisplayX, canvasDisplayY);

        if (positions == null) {
            return;
        }

        // Draw rotation line (connecting top-center to rotation handle)
        Point topCenter = positions.topCenter;
        Point rotation = positions.rotation;
        drawList.addLine((float)topCenter.x, (float)topCenter.y, (float)rotation.x, (float)rotation.y, ROTATION_LINE_COLOR, 1.5f);

        // Draw resize handles (squares)
        drawHandle(drawList, positions.topLeft, hoveredHandle == HandleType.TOP_LEFT);
        drawHandle(drawList, positions.topRight, hoveredHandle == HandleType.TOP_RIGHT);
        drawHandle(drawList, positions.bottomLeft, hoveredHandle == HandleType.BOTTOM_LEFT);
        drawHandle(drawList, positions.bottomRight, hoveredHandle == HandleType.BOTTOM_RIGHT);
        drawHandle(drawList, positions.topCenter, hoveredHandle == HandleType.TOP_CENTER);
        drawHandle(drawList, positions.bottomCenter, hoveredHandle == HandleType.BOTTOM_CENTER);
        drawHandle(drawList, positions.middleLeft, hoveredHandle == HandleType.MIDDLE_LEFT);
        drawHandle(drawList, positions.middleRight, hoveredHandle == HandleType.MIDDLE_RIGHT);

        // Draw rotation handle (circle)
        drawRotationHandle(drawList, rotation, hoveredHandle == HandleType.ROTATION);
    }

    private void drawHandle(ImDrawList drawList, Point position, boolean isHovered) {
        int halfSize = HANDLE_SIZE / 2;

        // Draw border (black outline)
        drawList.addRectFilled(
                (float)(position.x - halfSize - 1),
                (float)(position.y - halfSize - 1),
                (float)(position.x + halfSize + 1),
                (float)(position.y + halfSize + 1),
                HANDLE_COLOR_BORDER
        );

        // Draw handle (white fill, or highlighted if hovered)
        int fillColor = isHovered
                ? ImGui.getColorU32(100, 200, 255, 255)
                : HANDLE_COLOR_NORMAL;

        drawList.addRectFilled(
                (float)(position.x - halfSize),
                (float)(position.y - halfSize),
                (float)(position.x + halfSize),
                (float)(position.y + halfSize),
                fillColor
        );
    }

    private void drawRotationHandle(ImDrawList drawList, Point position, boolean isHovered) {
        int radius = ROTATION_HANDLE_SIZE / 2;

        // Draw border (black outline)
        drawList.addCircleFilled((float)position.x, (float)position.y, (float)(radius + 1), HANDLE_COLOR_BORDER);

        // Draw handle (white fill, or highlighted if hovered)
        int fillColor = isHovered
                ? ImGui.getColorU32(100, 200, 255, 255)
                : HANDLE_COLOR_NORMAL;

        drawList.addCircleFilled((float)position.x, (float)position.y, (float)radius, fillColor);

        // Draw crosshair inside rotation handle
        drawList.addLine(
                (float)(position.x - radius + 2), (float)position.y,
                (float)(position.x + radius - 2), (float)position.y,
                HANDLE_COLOR_BORDER, 1.0f
        );
        drawList.addLine(
                (float)position.x, (float)(position.y - radius + 2),
                (float)position.x, (float)(position.y + radius - 2),
                HANDLE_COLOR_BORDER, 1.0f
        );
    }

    private Rectangle applyTransformToBounds(Rectangle bounds, TransformState transform) {
        // Apply scale to dimensions
        int scaledWidth = (int) Math.round(bounds.width * transform.getScaleX());
        int scaledHeight = (int) Math.round(bounds.height * transform.getScaleY());

        // Calculate offset due to scaling (scale happens from center)
        int scaleOffsetX = (bounds.width - scaledWidth) / 2;
        int scaleOffsetY = (bounds.height - scaledHeight) / 2;

        // Apply translation
        int translatedX = bounds.x + transform.getTranslateX() + scaleOffsetX;
        int translatedY = bounds.y + transform.getTranslateY() + scaleOffsetY;

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

    private void drawDashedQuad(ImDrawList drawList, Point p1, Point p2, Point p3, Point p4) {
        // Use yellow/orange for preview to make it more visible
        int dashColor = ImGui.getColorU32(255, 200, 0, 255);
        int dashLength = 5;
        int gapLength = 3;

        drawDashedLine(drawList, p1, p2, dashColor, dashLength, gapLength);
        drawDashedLine(drawList, p2, p3, dashColor, dashLength, gapLength);
        drawDashedLine(drawList, p3, p4, dashColor, dashLength, gapLength);
        drawDashedLine(drawList, p4, p1, dashColor, dashLength, gapLength);
    }

    private void drawDashedLine(ImDrawList drawList, Point start, Point end,
                               int color, int dashLength, int gapLength) {
        int dx = end.x - start.x;
        int dy = end.y - start.y;
        double length = Math.sqrt(dx * dx + dy * dy);

        if (length == 0) return;

        double segmentLength = dashLength + gapLength;
        int numSegments = (int) (length / segmentLength);

        for (int i = 0; i <= numSegments; i++) {
            double t1 = (i * segmentLength) / length;
            double t2 = Math.min(1.0, (i * segmentLength + dashLength) / length);

            if (t1 >= 1.0) break;

            int x1 = start.x + (int) (dx * t1);
            int y1 = start.y + (int) (dy * t1);
            int x2 = start.x + (int) (dx * t2);
            int y2 = start.y + (int) (dy * t2);

            drawList.addLine((float)x1, (float)y1, (float)x2, (float)y2, color, 1.5f);
        }
    }
}
