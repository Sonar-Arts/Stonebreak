package com.openmason.ui.components.textureCreator.selection;

import com.openmason.ui.components.textureCreator.tools.selection.PixelPreview;
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

    // Free selection pixel colors
    private static final int PIXEL_SELECTED_COLOR = ImColor.rgba(51, 153, 255, 180);
    private static final int PIXEL_PREVIEW_COLOR = ImColor.rgba(51, 153, 255, 120);

    // Marching ants animation (optional - can add later)
    private int animationOffset = 0;
    private long lastAnimationTime = 0;
    private static final long ANIMATION_INTERVAL_MS = 100; // Update every 100ms

    /**
     * Extracts external edges from a set of selected pixels.
     * An edge is external if it borders a non-selected pixel.
     *
     * @param pixels Set of selected pixels
     * @param isSelected Function to check if a pixel coordinate is selected
     * @return List of edge segments forming the outline
     */
    private List<Edge> extractEdges(Set<? extends Object> pixels, PixelChecker isSelected) {
        List<Edge> edges = new ArrayList<>();

        // For each selected pixel, check its 4 edges
        for (Object pixelObj : pixels) {
            int px, py;

            // Handle both FreeSelection.Pixel and PixelPreview.Pixel
            if (pixelObj instanceof FreeSelection.Pixel) {
                FreeSelection.Pixel pixel = (FreeSelection.Pixel) pixelObj;
                px = pixel.x;
                py = pixel.y;
            } else if (pixelObj instanceof PixelPreview.Pixel) {
                PixelPreview.Pixel pixel = (PixelPreview.Pixel) pixelObj;
                px = pixel.x;
                py = pixel.y;
            } else {
                continue; // Skip unknown types
            }

            // Check top edge (if pixel above is not selected)
            if (!isSelected.isSelected(px, py - 1)) {
                edges.add(new Edge(new Point(px, py), new Point(px + 1, py)));
            }

            // Check right edge (if pixel to right is not selected)
            if (!isSelected.isSelected(px + 1, py)) {
                edges.add(new Edge(new Point(px + 1, py), new Point(px + 1, py + 1)));
            }

            // Check bottom edge (if pixel below is not selected)
            if (!isSelected.isSelected(px, py + 1)) {
                edges.add(new Edge(new Point(px + 1, py + 1), new Point(px, py + 1)));
            }

            // Check left edge (if pixel to left is not selected)
            if (!isSelected.isSelected(px - 1, py)) {
                edges.add(new Edge(new Point(px, py + 1), new Point(px, py)));
            }
        }

        return edges;
    }

    /**
     * Functional interface for checking if a pixel is selected.
     */
    @FunctionalInterface
    private interface PixelChecker {
        boolean isSelected(int x, int y);
    }

    /**
     * Traces edges into continuous closed paths.
     * Connects adjacent edge segments to form seamless outlines.
     *
     * @param edges List of edge segments to trace
     * @return List of closed paths (each path is a list of points)
     */
    private List<List<Point>> traceEdgePaths(List<Edge> edges) {
        List<List<Point>> paths = new ArrayList<>();
        Set<Edge> remainingEdges = new HashSet<>(edges);

        while (!remainingEdges.isEmpty()) {
            // Start a new path with any remaining edge
            Edge startEdge = remainingEdges.iterator().next();
            remainingEdges.remove(startEdge);

            List<Point> path = new ArrayList<>();
            path.add(startEdge.start);
            path.add(startEdge.end);

            Point currentEnd = startEdge.end;

            // Keep connecting edges until we form a closed loop
            boolean foundConnection = true;
            while (foundConnection && !currentEnd.equals(startEdge.start)) {
                foundConnection = false;

                // Find an edge that connects to the current end point
                Iterator<Edge> it = remainingEdges.iterator();
                while (it.hasNext()) {
                    Edge edge = it.next();
                    if (edge.start.equals(currentEnd)) {
                        // Found a connecting edge
                        path.add(edge.end);
                        currentEnd = edge.end;
                        it.remove();
                        foundConnection = true;
                        break;
                    }
                }
            }

            // Add the completed path
            if (!path.isEmpty()) {
                paths.add(path);
            }
        }

        return paths;
    }

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
     * Renders a free-form (pixel-based) selection region with seamless connected outline.
     * Uses edge tracing to create continuous paths around the selected region.
     *
     * @param drawList  ImGui draw list to render to
     * @param selection The free selection to render
     * @param canvasX   Canvas x-position in screen coordinates
     * @param canvasY   Canvas y-position in screen coordinates
     * @param zoom      Current zoom level
     */
    public void renderFreeSelection(ImDrawList drawList, FreeSelection selection,
                                    float canvasX, float canvasY, float zoom) {
        if (drawList == null || selection == null || selection.isEmpty()) {
            return;
        }

        // Update marching ants animation
        updateAnimation();

        // Extract edges and trace paths for seamless outline
        Set<FreeSelection.Pixel> pixels = selection.getPixels();
        List<Edge> edges = extractEdges(pixels, selection::contains);
        List<List<Point>> paths = traceEdgePaths(edges);

        // Render each closed path with connected lines (KISS approach)
        for (List<Point> path : paths) {
            if (path.size() < 2) continue; // Need at least 2 points

            // Draw connected lines for the outline
            for (int i = 0; i < path.size(); i++) {
                Point p1 = path.get(i);
                Point p2 = path.get((i + 1) % path.size()); // Wrap around to close the path

                float x1 = canvasX + p1.x * zoom;
                float y1 = canvasY + p1.y * zoom;
                float x2 = canvasX + p2.x * zoom;
                float y2 = canvasY + p2.y * zoom;

                // Draw outer line (bright blue, 2px thick)
                drawList.addLine(x1, y1, x2, y2, SELECTION_OUTER_COLOR, 2.0f);
            }
        }
    }

    /**
     * Renders a pixel preview during free-form selection creation with seamless outline.
     * Uses edge tracing to create continuous paths around the preview region.
     *
     * @param drawList  ImGui draw list to render to
     * @param pixels    Set of pixels to preview
     * @param canvasX   Canvas x-position in screen coordinates
     * @param canvasY   Canvas y-position in screen coordinates
     * @param zoom      Current zoom level
     */
    public void renderPixelPreview(ImDrawList drawList, Set<PixelPreview.Pixel> pixels,
                                   float canvasX, float canvasY, float zoom) {
        if (drawList == null || pixels == null || pixels.isEmpty()) {
            return;
        }

        // Create a pixel checker for the preview pixels
        Set<String> pixelSet = new HashSet<>();
        for (PixelPreview.Pixel pixel : pixels) {
            pixelSet.add(pixel.x + "," + pixel.y);
        }

        PixelChecker checker = (x, y) -> pixelSet.contains(x + "," + y);

        // Extract edges and trace paths for seamless outline
        List<Edge> edges = extractEdges(pixels, checker);
        List<List<Point>> paths = traceEdgePaths(edges);

        // First, render semi-transparent fill for all pixels
        for (PixelPreview.Pixel pixel : pixels) {
            float x = canvasX + pixel.x * zoom;
            float y = canvasY + pixel.y * zoom;
            drawList.addRectFilled(x, y, x + zoom, y + zoom, PIXEL_PREVIEW_COLOR);
        }

        // Then render each closed path with connected lines (KISS approach)
        for (List<Point> path : paths) {
            if (path.size() < 2) continue; // Need at least 2 points

            // Draw connected lines for the outline
            for (int i = 0; i < path.size(); i++) {
                Point p1 = path.get(i);
                Point p2 = path.get((i + 1) % path.size()); // Wrap around to close the path

                float x1 = canvasX + p1.x * zoom;
                float y1 = canvasY + p1.y * zoom;
                float x2 = canvasX + p2.x * zoom;
                float y2 = canvasY + p2.y * zoom;

                // Draw outline (bright blue, 2px thick)
                drawList.addLine(x1, y1, x2, y2, PREVIEW_OUTLINE_COLOR, 2.0f);
            }
        }
    }

    /**
     * Renders any selection region (dispatches to appropriate render method).
     *
     * @param drawList  ImGui draw list to render to
     * @param selection The selection region to render
     * @param canvasX   Canvas x-position in screen coordinates
     * @param canvasY   Canvas y-position in screen coordinates
     * @param zoom      Current zoom level
     */
    public void renderSelection(ImDrawList drawList, SelectionRegion selection,
                                float canvasX, float canvasY, float zoom) {
        if (selection instanceof FreeSelection) {
            renderFreeSelection(drawList, (FreeSelection) selection, canvasX, canvasY, zoom);
        } else {
            // Default to rendering bounds (works for RectangularSelection and others)
            render(drawList, selection, canvasX, canvasY, zoom);
        }
    }

    /**
     * Resets animation state.
     */
    public void reset() {
        animationOffset = 0;
        lastAnimationTime = System.currentTimeMillis();
    }
}
