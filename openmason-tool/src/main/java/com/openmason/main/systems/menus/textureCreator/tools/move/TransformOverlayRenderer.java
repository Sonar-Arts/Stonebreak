package com.openmason.main.systems.menus.textureCreator.tools.move;

import com.openmason.main.systems.menus.textureCreator.canvas.CanvasState;
import imgui.ImDrawList;

import java.awt.Rectangle;
import java.util.EnumMap;
import java.util.Map;

/**
 * Draws selection outlines and interaction handles for the move tool following
 */
final class TransformOverlayRenderer {

    // Handle sizing
    private static final float HANDLE_SIZE = 8.0f;
    private static final float ACTIVE_HANDLE_SIZE = 10.0f;
    private static final float PIVOT_RADIUS = 5.0f;
    private static final float PIVOT_INNER_RADIUS = 2.0f;

    // Professional color scheme
    private static final int OUTLINE_COLOR = 0xFF3D3D3D;           // Dark gray outline
    private static final int HANDLE_FILL = 0xFFFFFFFF;             // White fill
    private static final int HANDLE_OUTLINE = 0xFF000000;          // Black outline
    private static final int HANDLE_HOVER_FILL = 0xFFFFAA40;       // Light blue fill
    private static final int HANDLE_HOVER_OUTLINE = 0xFF0077CC;    // Medium blue outline
    private static final int HANDLE_ACTIVE_FILL = 0xFFFF7700;      // Bright blue fill
    private static final int HANDLE_ACTIVE_OUTLINE = 0xFF0055AA;   // Deep blue outline
    private static final int PIVOT_COLOR = 0xFF3D3D3D;             // Dark gray for pivot
    private static final int PIVOT_HOVER_COLOR = 0xFF0077CC;       // Blue when hovering

    void render(ImDrawList drawList,
                Rectangle bounds,
                TransformationState transform,
                CanvasState canvasState,
                float canvasDisplayX,
                float canvasDisplayY,
                TransformHandle hovered,
                TransformHandle active) {

        double[][] corners = computeCorners(bounds, transform);
        float[][] screenCorners = toScreen(corners, canvasState, canvasDisplayX, canvasDisplayY);

        drawOutline(drawList, screenCorners);

        Map<TransformHandle, float[]> handlePositions = computeHandlePositions(
                corners, bounds, transform, canvasState, canvasDisplayX, canvasDisplayY);

        // Draw rotation handle connecting line
        float[] topCenter = handlePositions.get(TransformHandle.SCALE_NORTH);
        float[] rotationHandle = handlePositions.get(TransformHandle.ROTATE);
        if (topCenter != null && rotationHandle != null) {
            int lineColor = (hovered == TransformHandle.ROTATE || active == TransformHandle.ROTATE)
                    ? HANDLE_HOVER_OUTLINE : HANDLE_OUTLINE;
            drawList.addLine(topCenter[0], topCenter[1], rotationHandle[0], rotationHandle[1], lineColor, 1.5f);
        }

        // Draw scale handles
        for (Map.Entry<TransformHandle, float[]> entry : handlePositions.entrySet()) {
            TransformHandle handle = entry.getKey();
            float[] position = entry.getValue();

            if (handle == TransformHandle.PIVOT_CENTER) {
                drawPivotPoint(drawList, position, handle == hovered, handle == active);
            } else if (handle == TransformHandle.ROTATE) {
                drawRotationHandle(drawList, position, handle == hovered, handle == active);
            } else {
                drawScaleHandle(drawList, position, handle == hovered, handle == active);
            }
        }
    }

    TransformHandle detectHandle(float mouseX,
                                 float mouseY,
                                 Rectangle bounds,
                                 TransformationState transform,
                                 CanvasState canvasState,
                                 float canvasDisplayX,
                                 float canvasDisplayY) {

        double[][] corners = computeCorners(bounds, transform);
        Map<TransformHandle, float[]> positions = computeHandlePositions(
                corners, bounds, transform, canvasState, canvasDisplayX, canvasDisplayY);

        // Check for handle hits
        TransformHandle closest = TransformHandle.NONE;
        double closestDistance = Double.POSITIVE_INFINITY;

        for (Map.Entry<TransformHandle, float[]> entry : positions.entrySet()) {
            TransformHandle handle = entry.getKey();
            float[] point = entry.getValue();

            double distance = Math.hypot(mouseX - point[0], mouseY - point[1]);
            double threshold;
            if (handle == TransformHandle.PIVOT_CENTER) {
                threshold = PIVOT_RADIUS * 1.5;
            } else if (handle == TransformHandle.ROTATE) {
                threshold = HANDLE_SIZE * 0.6; // Match rotation handle radius
            } else {
                threshold = HANDLE_SIZE;
            }

            if (distance <= threshold && distance < closestDistance) {
                closest = handle;
                closestDistance = distance;
            }
        }

        return closest;
    }

    private static double[][] computeCorners(Rectangle bounds, TransformationState transform) {
        return new double[][]{
                TransformMath.mapLocalToCanvas(0.0, 0.0, bounds, transform),
                TransformMath.mapLocalToCanvas(bounds.width, 0.0, bounds, transform),
                TransformMath.mapLocalToCanvas(bounds.width, bounds.height, bounds, transform),
                TransformMath.mapLocalToCanvas(0.0, bounds.height, bounds, transform)
        };
    }

    private static float[][] toScreen(double[][] corners,
                                      CanvasState canvasState,
                                      float offsetX,
                                      float offsetY) {
        float[][] result = new float[corners.length][2];
        float zoom = canvasState.getZoomLevel();
        float panX = canvasState.getPanOffsetX();
        float panY = canvasState.getPanOffsetY();

        for (int i = 0; i < corners.length; i++) {
            result[i][0] = (float) (corners[i][0] * zoom + panX + offsetX);
            result[i][1] = (float) (corners[i][1] * zoom + panY + offsetY);
        }
        return result;
    }

    private static Map<TransformHandle, float[]> computeHandlePositions(double[][] corners,
                                                                        Rectangle bounds,
                                                                        TransformationState transform,
                                                                        CanvasState canvasState,
                                                                        float offsetX,
                                                                        float offsetY) {
        Map<TransformHandle, float[]> positions = new EnumMap<>(TransformHandle.class);
        float[][] screen = toScreen(corners, canvasState, offsetX, offsetY);

        float[] tl = screen[0];
        float[] tr = screen[1];
        float[] br = screen[2];
        float[] bl = screen[3];

        // Corner handles
        positions.put(TransformHandle.SCALE_NORTH_WEST, tl);
        positions.put(TransformHandle.SCALE_NORTH_EAST, tr);
        positions.put(TransformHandle.SCALE_SOUTH_EAST, br);
        positions.put(TransformHandle.SCALE_SOUTH_WEST, bl);

        // Edge midpoint handles
        float[] topCenter = midpoint(tl, tr);
        positions.put(TransformHandle.SCALE_NORTH, topCenter);
        positions.put(TransformHandle.SCALE_EAST, midpoint(tr, br));
        positions.put(TransformHandle.SCALE_SOUTH, midpoint(br, bl));
        positions.put(TransformHandle.SCALE_WEST, midpoint(bl, tl));

        // Rotation handle (above top center)
        float rotationHandleOffset = 30.0f; // pixels above the selection
        float[] rotationHandle = new float[]{topCenter[0], topCenter[1] - rotationHandleOffset};
        positions.put(TransformHandle.ROTATE, rotationHandle);

        // Center pivot point
        double[] centerLocal = {bounds.width / 2.0, bounds.height / 2.0};
        double[] centerCanvas = TransformMath.mapLocalToCanvas(centerLocal[0], centerLocal[1], bounds, transform);
        float[] centerScreen = toScreen(new double[][]{centerCanvas}, canvasState, offsetX, offsetY)[0];
        positions.put(TransformHandle.PIVOT_CENTER, centerScreen);

        return positions;
    }

    private static float[] midpoint(float[] a, float[] b) {
        return new float[]{(a[0] + b[0]) * 0.5f, (a[1] + b[1]) * 0.5f};
    }

    private static void drawOutline(ImDrawList drawList, float[][] corners) {
        for (int i = 0; i < corners.length; i++) {
            float[] current = corners[i];
            float[] next = corners[(i + 1) % corners.length];
            drawList.addLine(current[0], current[1], next[0], next[1], OUTLINE_COLOR, 2.0f);
        }
    }

    private static void drawScaleHandle(ImDrawList drawList, float[] point, boolean hovered, boolean active) {
        float half = (active ? ACTIVE_HANDLE_SIZE : HANDLE_SIZE) * 0.5f;

        int fillColor = active ? HANDLE_ACTIVE_FILL : hovered ? HANDLE_HOVER_FILL : HANDLE_FILL;
        int outlineColor = active ? HANDLE_ACTIVE_OUTLINE : hovered ? HANDLE_HOVER_OUTLINE : HANDLE_OUTLINE;

        // Draw filled square
        drawList.addRectFilled(point[0] - half, point[1] - half,
                point[0] + half, point[1] + half, fillColor, 1.0f);

        // Draw outline
        drawList.addRect(point[0] - half, point[1] - half,
                point[0] + half, point[1] + half, outlineColor, 1.0f, 0, 1.5f);
    }

    private static void drawPivotPoint(ImDrawList drawList, float[] point, boolean hovered, boolean active) {
        int color = (hovered || active) ? PIVOT_HOVER_COLOR : PIVOT_COLOR;

        // Draw outer circle
        drawList.addCircle(point[0], point[1], PIVOT_RADIUS, color, 16, 1.5f);

        // Draw inner circle
        drawList.addCircleFilled(point[0], point[1], PIVOT_INNER_RADIUS, color, 12);
    }

    private static void drawRotationHandle(ImDrawList drawList, float[] point, boolean hovered, boolean active) {
        float radius = (active ? ACTIVE_HANDLE_SIZE : HANDLE_SIZE) * 0.6f;

        int fillColor = active ? HANDLE_ACTIVE_FILL : hovered ? HANDLE_HOVER_FILL : HANDLE_FILL;
        int outlineColor = active ? HANDLE_ACTIVE_OUTLINE : hovered ? HANDLE_HOVER_OUTLINE : HANDLE_OUTLINE;

        // Draw filled circle
        drawList.addCircleFilled(point[0], point[1], radius, fillColor, 16);

        // Draw outline
        drawList.addCircle(point[0], point[1], radius, outlineColor, 16, 1.5f);

        // Draw rotation arc inside the handle
        float arcRadius = radius * 0.6f;
        float arcThickness = 1.2f;
        int arcColor = active ? HANDLE_ACTIVE_OUTLINE : hovered ? HANDLE_HOVER_OUTLINE : HANDLE_OUTLINE;

        // Draw a circular arrow arc (270 degrees)
        drawList.addCircle(point[0], point[1], arcRadius, arcColor, 12, arcThickness);
    }

}
