package com.openmason.main.systems.menus.textureCreator.tools;

import com.openmason.main.systems.menus.textureCreator.SymmetryState;
import com.openmason.main.systems.menus.textureCreator.canvas.CoverageBlender;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import com.openmason.main.systems.menus.textureCreator.utils.SymmetryHelper;

import java.util.List;

/**
 * Utility class for drawing brush strokes with variable size.
 *
 * Provides shared brush drawing logic following DRY principle.
 * All brush-based tools (Pencil, Eraser, etc.) can use this helper.
 *
 * Brush shape: Circular/round brush for smooth, natural drawing.
 * Respects all constraints: selection bounds, cube net validation, undo recording.
 *
 * @author Open Mason Team
 */
public class BrushDrawingHelper {

    /**
     * Draw a circular brush stroke at the specified position.
     * Respects selection constraints and cube net validation.
     *
     * @param centerX canvas pixel X coordinate (brush center)
     * @param centerY canvas pixel Y coordinate (brush center)
     * @param color color to draw (RGBA packed)
     * @param canvas canvas to draw on
     * @param command command to record changes (for undo/redo)
     * @param brushSize brush diameter in pixels (1 = single pixel, 3 = 3x3 circle, etc.)
     */
    public static void drawBrushStroke(int centerX, int centerY, int color,
                                       PixelCanvas canvas, DrawCommand command, int brushSize) {
        if (brushSize < 1) {
            brushSize = 1; // Minimum size is 1 pixel
        }

        if (brushSize == 1) {
            // Single pixel: use coverage blending for mask edges only
            setPixelWithCoverage(centerX, centerY, color, 1.0f, canvas, command);
            return;
        }

        // Draw circular brush with smooth edge falloff
        float radius = (brushSize - 1) / 2.0f;

        // Expand bounding box by 1 pixel to catch partial-coverage edge pixels
        int minX = (int) Math.floor(centerX - radius) - 1;
        int maxX = (int) Math.ceil(centerX + radius) + 1;
        int minY = (int) Math.floor(centerY - radius) - 1;
        int maxY = (int) Math.ceil(centerY + radius) + 1;

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float dx = x - centerX;
                float dy = y - centerY;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);

                // Smooth falloff at brush edge: full inside, fade over 1px at boundary
                float brushCoverage = CoverageBlender.smoothstep(radius + 0.5f, radius - 0.5f, distance);

                if (brushCoverage > 0.0f) {
                    setPixelWithCoverage(x, y, color, brushCoverage, canvas, command);
                }
            }
        }
    }

    /**
     * Draw a line with brush strokes between two points (for smooth dragging).
     * Uses Bresenham's algorithm to sample points along the line,
     * then draws brush strokes at each sampled point.
     *
     * @param x0 start X coordinate
     * @param y0 start Y coordinate
     * @param x1 end X coordinate
     * @param y1 end Y coordinate
     * @param color color to draw (RGBA packed)
     * @param canvas canvas to draw on
     * @param command command to record changes (for undo/redo)
     * @param brushSize brush diameter in pixels
     */
    public static void drawBrushLine(int x0, int y0, int x1, int y1, int color,
                                     PixelCanvas canvas, DrawCommand command, int brushSize) {
        // Use Bresenham's line algorithm to sample points
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        while (true) {
            // Draw brush stroke at current point
            drawBrushStroke(x0, y0, color, canvas, command, brushSize);

            if (x0 == x1 && y0 == y1) {
                break;
            }

            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    /**
     * Set a single pixel with coverage-based blending, undo tracking, and constraint checking.
     *
     * <p>Combines the tool's coverage (brush edge falloff) with the shape mask's
     * coverage (polygon boundary) to produce smooth edges on both the brush
     * and the editable region boundary.
     *
     * @param x        pixel X coordinate
     * @param y        pixel Y coordinate
     * @param color    color to set (RGBA packed)
     * @param coverage tool coverage fraction (0.0 to 1.0)
     * @param canvas   canvas to draw on
     * @param command  command to record changes (for undo/redo)
     */
    private static void setPixelWithCoverage(int x, int y, int color, float coverage,
                                              PixelCanvas canvas, DrawCommand command) {
        if (!canvas.isValidCoordinate(x, y)) {
            return;
        }

        // Combine tool coverage with shape mask coverage
        float maskCoverage = canvas.getMaskCoverage(x, y);
        float finalCoverage = coverage * maskCoverage;

        if (finalCoverage <= 0.0f) {
            return;
        }

        int oldColor = canvas.getPixel(x, y);
        int blended;
        if (finalCoverage >= 1.0f) {
            blended = color;
        } else {
            blended = CoverageBlender.blendWithCoverage(color, oldColor, finalCoverage);
        }

        if (blended == oldColor) {
            return; // No visible change
        }

        if (command != null) {
            command.recordPixelChange(x, y, oldColor, blended);
        }

        // Bypass the mask check in setPixel since we already handled coverage
        canvas.setPixel(x, y, blended);
    }

    // ========================================
    // SYMMETRY-AWARE DRAWING METHODS
    // ========================================

    /**
     * Draw a brush stroke with symmetry support.
     * Checks symmetry state and draws mirrored strokes if enabled.
     *
     * @param centerX canvas pixel X coordinate (brush center)
     * @param centerY canvas pixel Y coordinate (brush center)
     * @param color color to draw (RGBA packed)
     * @param canvas canvas to draw on
     * @param command command to record changes (for undo/redo)
     * @param brushSize brush diameter in pixels
     * @param symmetryState symmetry state (null to skip symmetry)
     * @param toolClassName simple class name of the tool using this method
     */
    public static void drawBrushStrokeWithSymmetry(int centerX, int centerY, int color,
                                                    PixelCanvas canvas, DrawCommand command,
                                                    int brushSize, SymmetryState symmetryState,
                                                    String toolClassName) {
        // Draw original stroke
        drawBrushStroke(centerX, centerY, color, canvas, command, brushSize);

        // Apply symmetry if enabled
        if (symmetryState != null && symmetryState.isActive() &&
            symmetryState.isEnabledForTool(toolClassName)) {

            List<SymmetryHelper.Point2i> mirrorPoints = SymmetryHelper.calculateMirrorPoints(
                centerX, centerY,
                symmetryState.getMode(),
                canvas.getWidth(), canvas.getHeight(),
                symmetryState.getAxisOffsetX(), symmetryState.getAxisOffsetY()
            );

            // Skip the first point (original), draw the rest (mirrored)
            for (int i = 1; i < mirrorPoints.size(); i++) {
                SymmetryHelper.Point2i point = mirrorPoints.get(i);
                drawBrushStroke(point.x(), point.y(), color, canvas, command, brushSize);
            }
        }
    }

    /**
     * Draw a line with brush strokes and symmetry support.
     *
     * @param x0 start X coordinate
     * @param y0 start Y coordinate
     * @param x1 end X coordinate
     * @param y1 end Y coordinate
     * @param color color to draw (RGBA packed)
     * @param canvas canvas to draw on
     * @param command command to record changes (for undo/redo)
     * @param brushSize brush diameter in pixels
     * @param symmetryState symmetry state (null to skip symmetry)
     * @param toolClassName simple class name of the tool using this method
     */
    public static void drawBrushLineWithSymmetry(int x0, int y0, int x1, int y1, int color,
                                                  PixelCanvas canvas, DrawCommand command,
                                                  int brushSize, SymmetryState symmetryState,
                                                  String toolClassName) {
        // Draw original line
        drawBrushLine(x0, y0, x1, y1, color, canvas, command, brushSize);

        // Apply symmetry if enabled
        if (symmetryState != null && symmetryState.isActive() &&
            symmetryState.isEnabledForTool(toolClassName)) {

            // Get mirrored start point
            List<SymmetryHelper.Point2i> startMirrors = SymmetryHelper.calculateMirrorPoints(
                x0, y0,
                symmetryState.getMode(),
                canvas.getWidth(), canvas.getHeight(),
                symmetryState.getAxisOffsetX(), symmetryState.getAxisOffsetY()
            );

            // Get mirrored end point
            List<SymmetryHelper.Point2i> endMirrors = SymmetryHelper.calculateMirrorPoints(
                x1, y1,
                symmetryState.getMode(),
                canvas.getWidth(), canvas.getHeight(),
                symmetryState.getAxisOffsetX(), symmetryState.getAxisOffsetY()
            );

            // Draw mirrored lines (skip index 0 which is the original)
            for (int i = 1; i < startMirrors.size() && i < endMirrors.size(); i++) {
                SymmetryHelper.Point2i start = startMirrors.get(i);
                SymmetryHelper.Point2i end = endMirrors.get(i);
                drawBrushLine(start.x(), start.y(), end.x(), end.y(), color, canvas, command, brushSize);
            }
        }
    }
}
