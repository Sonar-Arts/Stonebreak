package com.openmason.ui.components.textureCreator.selection;

import imgui.ImColor;
import imgui.ImDrawList;

import java.util.*;

/**
 * Renders selection regions with visual feedback.
 * Supports both rectangular and free-form pixel-based selections.
 * Uses ImGui's ImDrawList for consistency with CanvasRenderer.
 *
 * SOLID: Single responsibility - handles selection visualization only
 * KISS: Simple rendering with contrasting colors
 * Open/Closed: Extensible for new selection types
 */
public class SelectionRenderer {

    /**
     * Represents a point in 2D space (used for edge vertices).
     */
    private static class Point {
        final int x, y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Point)) return false;
            Point other = (Point) obj;
            return x == other.x && y == other.y;
        }

        @Override
        public int hashCode() {
            return 31 * x + y;
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + ")";
        }
    }

    /**
     * Represents an edge segment between two points.
     */
    private static class Edge {
        final Point start, end;

        Edge(Point start, Point end) {
            this.start = start;
            this.end = end;
        }

        /**
         * Check if this edge connects to another edge (shares an endpoint).
         */
        boolean connectsTo(Edge other) {
            return end.equals(other.start);
        }

        @Override
        public String toString() {
            return start + " -> " + end;
        }
    }

    // Selection outline colors (bright blue with high contrast)
    private static final int SELECTION_OUTER_COLOR = ImColor.rgba(51, 153, 255, 255);  // Bright blue
    private static final int SELECTION_INNER_COLOR = ImColor.rgba(255, 255, 255, 200); // White with transparency

    // Preview selection colors (semi-transparent)
    private static final int PREVIEW_OUTLINE_COLOR = ImColor.rgba(51, 153, 255, 180);
    private static final int PREVIEW_FILL_COLOR = ImColor.rgba(51, 153, 255, 25);

    // Marching ants animation (optional - can add later)
    private int animationOffset = 0;
    private long lastAnimationTime = 0;
    private static final long ANIMATION_INTERVAL_MS = 100; // Update every 100ms

    /**
     * Renders a selection region overlay on the canvas.
     *
     * @param drawList  ImGui draw list to render to
     * @param selection The selection region to render
     * @param canvasX   Canvas x-position in screen coordinates
     * @param canvasY   Canvas y-position in screen coordinates
     * @param zoom      Current zoom level
     */
    public void render(ImDrawList drawList, SelectionRegion selection,
                      float canvasX, float canvasY, float zoom) {
        if (drawList == null || selection == null || selection.isEmpty()) {
            return;
        }

        // Update marching ants animation
        updateAnimation();

        // Get bounds for rendering
        java.awt.Rectangle bounds = selection.getBounds();

        // Convert canvas coordinates to screen coordinates
        float x1 = canvasX + bounds.x * zoom;
        float y1 = canvasY + bounds.y * zoom;
        float x2 = canvasX + (bounds.x + bounds.width) * zoom;
        float y2 = canvasY + (bounds.y + bounds.height) * zoom;

        // Draw outer selection outline (bright blue, 2px thick)
        drawList.addRect(x1, y1, x2, y2, SELECTION_OUTER_COLOR, 0.0f, 0, 2.0f);

        // Draw inner selection outline (white, 1px thick, inset by 2px for visibility)
        float inset = 2.0f;
        drawList.addRect(x1 + inset, y1 + inset,
                        x2 - inset, y2 - inset,
                        SELECTION_INNER_COLOR, 0.0f, 0, 1.0f);
    }

    /**
     * Renders a preview selection during drag (before finalization).
     *
     * @param drawList  ImGui draw list to render to
     * @param startX    Start x-coordinate in canvas space
     * @param startY    Start y-coordinate in canvas space
     * @param endX      End x-coordinate in canvas space
     * @param endY      End y-coordinate in canvas space
     * @param canvasX   Canvas x-position in screen coordinates
     * @param canvasY   Canvas y-position in screen coordinates
     * @param zoom      Current zoom level
     */
    public void renderPreview(ImDrawList drawList, int startX, int startY, int endX, int endY,
                              float canvasX, float canvasY, float zoom) {
        if (drawList == null) {
            return;
        }

        // Normalize coordinates
        int x1 = Math.min(startX, endX);
        int y1 = Math.min(startY, endY);
        int x2 = Math.max(startX, endX);
        int y2 = Math.max(startY, endY);

        // Convert to screen coordinates (add 1 to include the end pixel)
        float sx1 = canvasX + x1 * zoom;
        float sy1 = canvasY + y1 * zoom;
        float sx2 = canvasX + (x2 + 1) * zoom;
        float sy2 = canvasY + (y2 + 1) * zoom;

        // Draw semi-transparent fill
        drawList.addRectFilled(sx1, sy1, sx2, sy2, PREVIEW_FILL_COLOR);

        // Draw outline
        drawList.addRect(sx1, sy1, sx2, sy2, PREVIEW_OUTLINE_COLOR, 0.0f, 0, 2.0f);
    }

    /**
     * Updates marching ants animation offset.
     * (Currently unused, but reserved for future animated selection borders)
     */
    private void updateAnimation() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastAnimationTime > ANIMATION_INTERVAL_MS) {
            animationOffset = (animationOffset + 1) % 16;
            lastAnimationTime = currentTime;
        }
    }

    /**
     * Renders any selection region.
     *
     * @param drawList  ImGui draw list to render to
     * @param selection The selection region to render
     * @param canvasX   Canvas x-position in screen coordinates
     * @param canvasY   Canvas y-position in screen coordinates
     * @param zoom      Current zoom level
     */
    public void renderSelection(ImDrawList drawList, SelectionRegion selection,
                                float canvasX, float canvasY, float zoom) {
        // Render bounds (works for RectangularSelection and other selection types)
        render(drawList, selection, canvasX, canvasY, zoom);
    }

    /**
     * Resets animation state.
     */
    public void reset() {
        animationOffset = 0;
        lastAnimationTime = System.currentTimeMillis();
    }
}
