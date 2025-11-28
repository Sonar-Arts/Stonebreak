package com.openmason.ui.textureCreator.selection;

import imgui.ImColor;
import imgui.ImDrawList;

/**
 * Renders selection regions with visual feedback.
 */
public class SelectionRenderer {

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
     */
    public void render(ImDrawList drawList, SelectionRegion selection,
                      float canvasX, float canvasY, float zoom) {
        if (drawList == null || selection == null || selection.isEmpty()) {
            return;
        }

        updateAnimation();

        if (selection.getType() == SelectionRegion.SelectionType.RECTANGLE) {
            renderRectangular(drawList, selection, canvasX, canvasY, zoom);
        } else {
            renderFreeform(drawList, selection, canvasX, canvasY, zoom);
        }
    }

    /**
     * Renders a preview selection during drag (before finalization).
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

    public void renderSelection(ImDrawList drawList, SelectionRegion selection,
                                float canvasX, float canvasY, float zoom) {
        render(drawList, selection, canvasX, canvasY, zoom);
    }

    public void reset() {
        animationOffset = 0;
        lastAnimationTime = System.currentTimeMillis();
    }

    private void renderRectangular(ImDrawList drawList, SelectionRegion selection,
                                   float canvasX, float canvasY, float zoom) {
        java.awt.Rectangle bounds = selection.getBounds();
        float x1 = canvasX + bounds.x * zoom;
        float y1 = canvasY + bounds.y * zoom;
        float x2 = canvasX + (bounds.x + bounds.width) * zoom;
        float y2 = canvasY + (bounds.y + bounds.height) * zoom;

        drawList.addRect(x1, y1, x2, y2, SELECTION_OUTER_COLOR, 0.0f, 0, 2.0f);

        float inset = Math.max(1.0f, zoom * 0.1f);
        drawList.addRect(x1 + inset, y1 + inset,
                        x2 - inset, y2 - inset,
                        SELECTION_INNER_COLOR, 0.0f, 0, 1.0f);
    }

    private void renderFreeform(ImDrawList drawList, SelectionRegion selection,
                                float canvasX, float canvasY, float zoom) {
        java.awt.Rectangle bounds = selection.getBounds();
        int maxX = bounds.x + bounds.width;
        int maxY = bounds.y + bounds.height;

        float fillOpacity = zoom < 1.5f ? 0.15f : 0.25f;
        int fillColor = ImColor.rgba(51, 153, 255, (int) (fillOpacity * 255));

        for (int y = bounds.y; y < maxY; y++) {
            for (int x = bounds.x; x < maxX; x++) {
                if (!selection.contains(x, y)) {
                    continue;
                }

                float sx1 = canvasX + x * zoom;
                float sy1 = canvasY + y * zoom;
                float sx2 = sx1 + zoom;
                float sy2 = sy1 + zoom;

                drawList.addRectFilled(sx1, sy1, sx2, sy2, fillColor);

                if (!selection.contains(x - 1, y)) {
                    drawList.addLine(sx1, sy1, sx1, sy2, SELECTION_OUTER_COLOR, 1.0f);
                }
                if (!selection.contains(x + 1, y)) {
                    drawList.addLine(sx2, sy1, sx2, sy2, SELECTION_OUTER_COLOR, 1.0f);
                }
                if (!selection.contains(x, y - 1)) {
                    drawList.addLine(sx1, sy1, sx2, sy1, SELECTION_OUTER_COLOR, 1.0f);
                }
                if (!selection.contains(x, y + 1)) {
                    drawList.addLine(sx1, sy2, sx2, sy2, SELECTION_OUTER_COLOR, 1.0f);
                }
            }
        }
    }
}
