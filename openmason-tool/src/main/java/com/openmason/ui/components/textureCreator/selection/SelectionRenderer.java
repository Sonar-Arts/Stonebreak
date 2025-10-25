package com.openmason.ui.components.textureCreator.selection;

import imgui.ImColor;
import imgui.ImDrawList;

/**
 * Renders selection regions with visual feedback (dashed outline).
 * Uses ImGui's ImDrawList for consistency with CanvasRenderer.
 *
 * SOLID: Single responsibility - handles selection visualization only
 * KISS: Simple rectangle drawing with contrasting colors
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
     * Resets animation state.
     */
    public void reset() {
        animationOffset = 0;
        lastAnimationTime = System.currentTimeMillis();
    }
}
