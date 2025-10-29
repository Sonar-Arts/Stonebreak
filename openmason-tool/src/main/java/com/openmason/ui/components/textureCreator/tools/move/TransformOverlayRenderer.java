package com.openmason.ui.components.textureCreator.tools.move;

import com.openmason.ui.components.textureCreator.canvas.CanvasState;
import imgui.ImDrawList;

import java.awt.Rectangle;
import java.util.EnumMap;
import java.util.Map;

/**
 * Draws selection outlines and interaction handles for the move tool and
 * provides basic hit testing for those handles.
 */
final class TransformOverlayRenderer {

    private static final float HANDLE_SIZE = 8.0f;
    private static final float ACTIVE_HANDLE_SIZE = 10.0f;
    private static final float ROTATION_OFFSET = 24.0f;
    private static final float ROTATION_RADIUS = 6.0f;

    private static final int OUTLINE_COLOR = 0x90FFFFFF;
    private static final int HANDLE_COLOR = 0xFFFFFFFF;
    private static final int HANDLE_HOVER_COLOR = 0xFF4FC3F7;
    private static final int HANDLE_ACTIVE_COLOR = 0xFF0288D1;
    private static final int ROTATION_COLOR = 0xFFE0E0E0;

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
                corners, canvasState, canvasDisplayX, canvasDisplayY);

        for (Map.Entry<TransformHandle, float[]> entry : handlePositions.entrySet()) {
            TransformHandle handle = entry.getKey();
            float[] position = entry.getValue();

            if (isRotationHandle(handle)) {
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

        Map<TransformHandle, float[]> positions = computeHandlePositions(
                computeCorners(bounds, transform), canvasState, canvasDisplayX, canvasDisplayY);

        TransformHandle closest = TransformHandle.NONE;
        double closestDistance = Double.POSITIVE_INFINITY;

        for (Map.Entry<TransformHandle, float[]> entry : positions.entrySet()) {
            TransformHandle handle = entry.getKey();
            float[] point = entry.getValue();

            double distance = Math.hypot(mouseX - point[0], mouseY - point[1]);
            double threshold = isRotationHandle(handle) ? ROTATION_RADIUS * 1.8 : HANDLE_SIZE;

            if (distance <= threshold && distance < closestDistance) {
                closest = handle;
                closestDistance = distance;
            }
        }

        return closest;
    }

    boolean isInside(float mouseX,
                     float mouseY,
                     Rectangle bounds,
                     TransformationState transform,
                     CanvasState canvasState,
                     float canvasDisplayX,
                     float canvasDisplayY) {

        float[][] polygon = toScreen(computeCorners(bounds, transform), canvasState, canvasDisplayX, canvasDisplayY);
        return pointInPolygon(mouseX, mouseY, polygon);
    }

    private static boolean isRotationHandle(TransformHandle handle) {
        return handle == TransformHandle.ROTATE_NORTH
                || handle == TransformHandle.ROTATE_EAST
                || handle == TransformHandle.ROTATE_SOUTH
                || handle == TransformHandle.ROTATE_WEST;
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
                                                                        CanvasState canvasState,
                                                                        float offsetX,
                                                                        float offsetY) {
        Map<TransformHandle, float[]> positions = new EnumMap<>(TransformHandle.class);
        float[][] screen = toScreen(corners, canvasState, offsetX, offsetY);

        float[] tl = screen[0];
        float[] tr = screen[1];
        float[] br = screen[2];
        float[] bl = screen[3];

        positions.put(TransformHandle.SCALE_NORTH_WEST, tl);
        positions.put(TransformHandle.SCALE_NORTH_EAST, tr);
        positions.put(TransformHandle.SCALE_SOUTH_EAST, br);
        positions.put(TransformHandle.SCALE_SOUTH_WEST, bl);

        float[] topMid = midpoint(tl, tr);
        float[] rightMid = midpoint(tr, br);
        float[] bottomMid = midpoint(br, bl);
        float[] leftMid = midpoint(bl, tl);

        positions.put(TransformHandle.SCALE_NORTH, topMid);
        positions.put(TransformHandle.SCALE_EAST, rightMid);
        positions.put(TransformHandle.SCALE_SOUTH, bottomMid);
        positions.put(TransformHandle.SCALE_WEST, leftMid);

        positions.put(TransformHandle.ROTATE_NORTH, offsetPoint(topMid, tl, ROTATION_OFFSET));
        positions.put(TransformHandle.ROTATE_EAST, offsetPoint(rightMid, tr, ROTATION_OFFSET));
        positions.put(TransformHandle.ROTATE_SOUTH, offsetPoint(bottomMid, br, ROTATION_OFFSET));
        positions.put(TransformHandle.ROTATE_WEST, offsetPoint(leftMid, bl, ROTATION_OFFSET));

        return positions;
    }

    private static float[] midpoint(float[] a, float[] b) {
        return new float[]{(a[0] + b[0]) * 0.5f, (a[1] + b[1]) * 0.5f};
    }

    private static float[] offsetPoint(float[] origin, float[] reference, float offset) {
        float dx = origin[0] - reference[0];
        float dy = origin[1] - reference[1];
        double length = Math.hypot(dx, dy);
        if (length == 0.0) {
            return new float[]{origin[0], origin[1] - offset};
        }
        double ux = dx / length;
        double uy = dy / length;
        return new float[]{
                (float) (origin[0] + ux * (offset + HANDLE_SIZE)),
                (float) (origin[1] + uy * (offset + HANDLE_SIZE))
        };
    }

    private static void drawOutline(ImDrawList drawList, float[][] corners) {
        for (int i = 0; i < corners.length; i++) {
            float[] current = corners[i];
            float[] next = corners[(i + 1) % corners.length];
            drawList.addLine(current[0], current[1], next[0], next[1], OUTLINE_COLOR, 1.5f);
        }
    }

    private static void drawScaleHandle(ImDrawList drawList, float[] point, boolean hovered, boolean active) {
        float half = (active ? ACTIVE_HANDLE_SIZE : HANDLE_SIZE) * 0.5f;
        int color = active ? HANDLE_ACTIVE_COLOR : hovered ? HANDLE_HOVER_COLOR : HANDLE_COLOR;

        drawList.addRectFilled(point[0] - half, point[1] - half,
                point[0] + half, point[1] + half, color, 2.0f);
        drawList.addRect(point[0] - half, point[1] - half,
                point[0] + half, point[1] + half, OUTLINE_COLOR, 2.0f, 0, 1.0f);
    }

    private static void drawRotationHandle(ImDrawList drawList, float[] point, boolean hovered, boolean active) {
        float radius = active ? ROTATION_RADIUS + 1.5f : ROTATION_RADIUS;
        int color = active ? HANDLE_ACTIVE_COLOR : hovered ? HANDLE_HOVER_COLOR : ROTATION_COLOR;
        drawList.addCircleFilled(point[0], point[1], radius, color, 16);
        drawList.addCircle(point[0], point[1], radius, OUTLINE_COLOR, 16, 1.0f);
    }

    private static boolean pointInPolygon(float x, float y, float[][] polygon) {
        boolean inside = false;
        for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
            float xi = polygon[i][0];
            float yi = polygon[i][1];
            float xj = polygon[j][0];
            float yj = polygon[j][1];

            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi + 1e-6f) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }
}
