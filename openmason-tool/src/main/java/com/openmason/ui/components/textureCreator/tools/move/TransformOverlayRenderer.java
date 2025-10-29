package com.openmason.ui.components.textureCreator.tools.move;

import com.openmason.ui.components.textureCreator.canvas.CanvasState;
import imgui.ImDrawList;

import java.awt.Rectangle;
import java.util.EnumMap;
import java.util.Map;

/**
 * Draws selection outlines and interaction handles for the move tool following
 * Photoshop-style transform UI standards:
 * - White handles with dark outlines for visibility
 * - Rotation triggered by hovering outside corners (no separate rotation handles)
 * - Center pivot point for rotation reference
 * - Professional blue highlight colors
 */
final class TransformOverlayRenderer {

    // Handle sizing
    private static final float HANDLE_SIZE = 8.0f;
    private static final float ACTIVE_HANDLE_SIZE = 10.0f;
    private static final float PIVOT_RADIUS = 5.0f;
    private static final float PIVOT_INNER_RADIUS = 2.0f;

    // Rotation detection zone (distance outside corners to trigger rotation)
    private static final float ROTATION_ZONE_DISTANCE = 20.0f;
    private static final float ROTATION_ARC_RADIUS = 16.0f;  // Radius for rotation arc indicators
    private static final float ROTATION_ARROW_SIZE = 4.0f;   // Size of arrow heads

    // Professional color scheme (ABGR format for ImGui)
    private static final int OUTLINE_COLOR = 0xFF3D3D3D;           // Dark gray outline
    private static final int HANDLE_FILL = 0xFFFFFFFF;             // White fill
    private static final int HANDLE_OUTLINE = 0xFF000000;          // Black outline
    private static final int HANDLE_HOVER_FILL = 0xFFFFAA40;       // Light blue fill
    private static final int HANDLE_HOVER_OUTLINE = 0xFF0077CC;    // Medium blue outline
    private static final int HANDLE_ACTIVE_FILL = 0xFFFF7700;      // Bright blue fill
    private static final int HANDLE_ACTIVE_OUTLINE = 0xFF0055AA;   // Deep blue outline
    private static final int PIVOT_COLOR = 0xFF3D3D3D;             // Dark gray for pivot
    private static final int PIVOT_HOVER_COLOR = 0xFF0077CC;       // Blue when hovering
    private static final int ROTATION_ARC_COLOR = 0xAA0077CC;      // Semi-transparent blue for rotation arcs
    private static final int ROTATION_ARC_ACTIVE = 0xFF0077CC;     // Opaque blue for active rotation

    void render(ImDrawList drawList,
                Rectangle bounds,
                TransformationState transform,
                CanvasState canvasState,
                float canvasDisplayX,
                float canvasDisplayY,
                TransformHandle hovered,
                TransformHandle active,
                boolean inRotationMode) {

        double[][] corners = computeCorners(bounds, transform);
        float[][] screenCorners = toScreen(corners, canvasState, canvasDisplayX, canvasDisplayY);

        drawOutline(drawList, screenCorners);

        Map<TransformHandle, float[]> handlePositions = computeHandlePositions(
                corners, bounds, transform, canvasState, canvasDisplayX, canvasDisplayY);

        // Draw rotation arc indicators if hovering in rotation mode
        if (inRotationMode && isCornerHandle(hovered)) {
            drawRotationArcIndicator(drawList, handlePositions.get(hovered), hovered, active == hovered);
        }

        // Draw scale handles
        for (Map.Entry<TransformHandle, float[]> entry : handlePositions.entrySet()) {
            TransformHandle handle = entry.getKey();
            float[] position = entry.getValue();

            if (handle == TransformHandle.PIVOT_CENTER) {
                drawPivotPoint(drawList, position, handle == hovered, handle == active);
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

        // First check for rotation mode (hovering outside corners)
        TransformHandle rotationCorner = detectRotationZone(mouseX, mouseY, positions);
        if (rotationCorner != TransformHandle.NONE) {
            return rotationCorner; // Return the corner handle, controller will know it's in rotation mode
        }

        // Then check for handle hits
        TransformHandle closest = TransformHandle.NONE;
        double closestDistance = Double.POSITIVE_INFINITY;

        for (Map.Entry<TransformHandle, float[]> entry : positions.entrySet()) {
            TransformHandle handle = entry.getKey();
            float[] point = entry.getValue();

            double distance = Math.hypot(mouseX - point[0], mouseY - point[1]);
            double threshold = handle == TransformHandle.PIVOT_CENTER ? PIVOT_RADIUS * 1.5 : HANDLE_SIZE;

            if (distance <= threshold && distance < closestDistance) {
                closest = handle;
                closestDistance = distance;
            }
        }

        return closest;
    }

    /**
     * Detect if mouse is in rotation zone (hovering outside corners).
     * Returns the corner handle if in rotation zone, NONE otherwise.
     */
    private TransformHandle detectRotationZone(float mouseX, float mouseY, Map<TransformHandle, float[]> positions) {
        TransformHandle[] corners = {
            TransformHandle.SCALE_NORTH_WEST,
            TransformHandle.SCALE_NORTH_EAST,
            TransformHandle.SCALE_SOUTH_EAST,
            TransformHandle.SCALE_SOUTH_WEST
        };

        for (TransformHandle corner : corners) {
            float[] point = positions.get(corner);
            if (point == null) continue;

            double distance = Math.hypot(mouseX - point[0], mouseY - point[1]);

            // Check if mouse is in the rotation zone (outside the handle but within range)
            if (distance > HANDLE_SIZE && distance <= ROTATION_ZONE_DISTANCE) {
                return corner;
            }
        }

        return TransformHandle.NONE;
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

    /**
     * Check if the handle is a corner (for rotation detection).
     */
    boolean isCornerHandle(TransformHandle handle) {
        return handle == TransformHandle.SCALE_NORTH_WEST
                || handle == TransformHandle.SCALE_NORTH_EAST
                || handle == TransformHandle.SCALE_SOUTH_EAST
                || handle == TransformHandle.SCALE_SOUTH_WEST;
    }

    /**
     * Check if the mouse position is in rotation mode for the given handle.
     * Returns true if hovering outside the corner (rotation zone).
     */
    boolean isInRotationMode(float mouseX,
                             float mouseY,
                             TransformHandle handle,
                             Rectangle bounds,
                             TransformationState transform,
                             CanvasState canvasState,
                             float canvasDisplayX,
                             float canvasDisplayY) {

        if (!isCornerHandle(handle)) {
            return false;
        }

        double[][] corners = computeCorners(bounds, transform);
        Map<TransformHandle, float[]> positions = computeHandlePositions(
                corners, bounds, transform, canvasState, canvasDisplayX, canvasDisplayY);

        float[] point = positions.get(handle);
        if (point == null) {
            return false;
        }

        double distance = Math.hypot(mouseX - point[0], mouseY - point[1]);

        // If distance is greater than handle size but within rotation zone, we're rotating
        return distance > HANDLE_SIZE && distance <= ROTATION_ZONE_DISTANCE;
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
        positions.put(TransformHandle.SCALE_NORTH, midpoint(tl, tr));
        positions.put(TransformHandle.SCALE_EAST, midpoint(tr, br));
        positions.put(TransformHandle.SCALE_SOUTH, midpoint(br, bl));
        positions.put(TransformHandle.SCALE_WEST, midpoint(bl, tl));

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

    /**
     * Draw rotation arc indicator with arrow showing rotation direction.
     * Appears outside the corner handle when hovering in rotation mode.
     */
    private static void drawRotationArcIndicator(ImDrawList drawList, float[] cornerPos, TransformHandle corner, boolean active) {
        if (cornerPos == null) return;

        int color = active ? ROTATION_ARC_ACTIVE : ROTATION_ARC_COLOR;

        // Determine arc angle range based on corner position
        float startAngle = 0;
        float endAngle = 0;
        boolean clockwise = true;

        switch (corner) {
            case SCALE_NORTH_WEST:
                startAngle = (float) Math.toRadians(180);
                endAngle = (float) Math.toRadians(270);
                clockwise = true;
                break;
            case SCALE_NORTH_EAST:
                startAngle = (float) Math.toRadians(270);
                endAngle = (float) Math.toRadians(360);
                clockwise = true;
                break;
            case SCALE_SOUTH_EAST:
                startAngle = (float) Math.toRadians(0);
                endAngle = (float) Math.toRadians(90);
                clockwise = true;
                break;
            case SCALE_SOUTH_WEST:
                startAngle = (float) Math.toRadians(90);
                endAngle = (float) Math.toRadians(180);
                clockwise = true;
                break;
            default:
                return;
        }

        // Draw arc (curved arrow path)
        int numSegments = 12;
        for (int i = 0; i < numSegments; i++) {
            float t1 = (float) i / numSegments;
            float t2 = (float) (i + 1) / numSegments;

            float angle1 = startAngle + (endAngle - startAngle) * t1;
            float angle2 = startAngle + (endAngle - startAngle) * t2;

            float x1 = cornerPos[0] + (float) Math.cos(angle1) * ROTATION_ARC_RADIUS;
            float y1 = cornerPos[1] + (float) Math.sin(angle1) * ROTATION_ARC_RADIUS;
            float x2 = cornerPos[0] + (float) Math.cos(angle2) * ROTATION_ARC_RADIUS;
            float y2 = cornerPos[1] + (float) Math.sin(angle2) * ROTATION_ARC_RADIUS;

            drawList.addLine(x1, y1, x2, y2, color, 2.0f);
        }

        // Draw arrow head at the end of the arc
        float arrowAngle = endAngle;
        float arrowX = cornerPos[0] + (float) Math.cos(arrowAngle) * ROTATION_ARC_RADIUS;
        float arrowY = cornerPos[1] + (float) Math.sin(arrowAngle) * ROTATION_ARC_RADIUS;

        // Arrow head points in the direction of rotation
        float arrowDir = arrowAngle + (float) Math.toRadians(90); // Perpendicular to radius
        float arrow1X = arrowX + (float) Math.cos(arrowDir + Math.toRadians(150)) * ROTATION_ARROW_SIZE;
        float arrow1Y = arrowY + (float) Math.sin(arrowDir + Math.toRadians(150)) * ROTATION_ARROW_SIZE;
        float arrow2X = arrowX + (float) Math.cos(arrowDir + Math.toRadians(210)) * ROTATION_ARROW_SIZE;
        float arrow2Y = arrowY + (float) Math.sin(arrowDir + Math.toRadians(210)) * ROTATION_ARROW_SIZE;

        drawList.addTriangleFilled(arrowX, arrowY, arrow1X, arrow1Y, arrow2X, arrow2Y, color);
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
