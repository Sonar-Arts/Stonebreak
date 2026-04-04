package com.openmason.main.systems.menus.textureCreator.canvas;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the face boundary mask overlay on the texture canvas.
 *
 * <p>Two visual elements:
 * <ol>
 *   <li><b>Hatch overlay</b> — diagonal lines over non-paintable pixels (outside the mask),
 *       matching the pattern used by {@link CubeNetOverlayRenderer} for non-editable zones.</li>
 *   <li><b>Polygon outline</b> — the face's boundary polygon drawn as a visible border.</li>
 * </ol>
 *
 * <p>Stateless renderer — all data is passed via the {@link #render} method.
 *
 * @see PolygonShapeMask
 * @see CanvasRenderer
 */
public class FaceBoundaryRenderer {

    private static final Logger logger = LoggerFactory.getLogger(FaceBoundaryRenderer.class);

    // ── Hatch pattern styling ────────────────────────────────────────────

    private static final int HATCH_BASE_COLOR = 0xFF404040;    // Dark gray (ABGR)
    private static final float HATCH_LINE_THICKNESS = 1.0f;
    private static final float HATCH_SPACING = 6.0f;           // Pixels between diagonal lines

    // ── Polygon outline styling ──────────────────────────────────────────

    private static final int OUTLINE_COLOR = ImColor.rgba(255, 200, 50, 220); // Warm yellow
    private static final float OUTLINE_THICKNESS = 2.0f;

    public FaceBoundaryRenderer() {
        logger.debug("Face boundary renderer created");
    }

    /**
     * Render the face boundary mask overlay.
     *
     * <p>Draws the hatch overlay on non-paintable areas and the polygon outline.
     *
     * @param mask     the active face boundary mask (null = no mask active)
     * @param canvasX  canvas display X position (screen space)
     * @param canvasY  canvas display Y position (screen space)
     * @param zoom     current zoom level
     * @param opacity  overlay opacity (0.0 to 1.0)
     */
    public void render(PolygonShapeMask mask, float canvasX, float canvasY,
                       float zoom, float opacity) {
        if (mask == null || opacity <= 0.0f) {
            return;
        }

        ImDrawList drawList = ImGui.getWindowDrawList();

        renderHatchOverlay(drawList, mask, canvasX, canvasY, zoom, opacity);
        renderPolygonOutline(drawList, mask, canvasX, canvasY, zoom, opacity);
    }

    /**
     * Render diagonal hatch pattern over non-paintable areas (outside the mask).
     *
     * <p>Uses a clipped diagonal line approach: for each masked-out pixel,
     * a small filled rect is drawn at low opacity. When zoomed out,
     * diagonal lines are drawn across the bounding region instead for performance.
     */
    private void renderHatchOverlay(ImDrawList drawList, PolygonShapeMask mask,
                                     float canvasX, float canvasY,
                                     float zoom, float opacity) {

        int maskWidth = mask.getWidth();
        int maskHeight = mask.getHeight();

        // Compute the polygon's bounding box in canvas pixel coordinates
        float[] polyX = mask.getPolygonXCoords();
        float[] polyY = mask.getPolygonYCoords();

        float minPX = Float.MAX_VALUE, maxPX = Float.MIN_VALUE;
        float minPY = Float.MAX_VALUE, maxPY = Float.MIN_VALUE;

        for (int i = 0; i < mask.getVertexCount(); i++) {
            minPX = Math.min(minPX, polyX[i]);
            maxPX = Math.max(maxPX, polyX[i]);
            minPY = Math.min(minPY, polyY[i]);
            maxPY = Math.max(maxPY, polyY[i]);
        }

        // Clamp to canvas bounds
        int startX = Math.max(0, (int) Math.floor(minPX));
        int endX = Math.min(maskWidth - 1, (int) Math.ceil(maxPX));
        int startY = Math.max(0, (int) Math.floor(minPY));
        int endY = Math.min(maskHeight - 1, (int) Math.ceil(maxPY));

        // Screen-space bounding box of the polygon region
        float screenMinX = canvasX + startX * zoom;
        float screenMinY = canvasY + startY * zoom;
        float screenMaxX = canvasX + (endX + 1) * zoom;
        float screenMaxY = canvasY + (endY + 1) * zoom;

        float regionScreenW = screenMaxX - screenMinX;
        float regionScreenH = screenMaxY - screenMinY;

        int alpha = (int) (opacity * 0.25f * 255.0f);
        int hatchColor = (HATCH_BASE_COLOR & 0x00FFFFFF) | (alpha << 24);

        // Strategy: draw diagonal lines clipped to the non-mask region
        // Push clip rect to contain the hatch to the polygon bounding box
        drawList.pushClipRect(screenMinX, screenMinY, screenMaxX, screenMaxY, true);

        // First, fill the entire bounding region with a subtle dim
        int fillAlpha = (int) (opacity * 0.12f * 255.0f);
        int fillColor = (HATCH_BASE_COLOR & 0x00FFFFFF) | (fillAlpha << 24);

        // Draw filled rects only for pixels outside the mask within the bounding box
        if (zoom >= 4.0f) {
            // At high zoom, draw per-pixel rects for precision
            for (int y = startY; y <= endY; y++) {
                for (int x = startX; x <= endX; x++) {
                    if (!mask.isEditable(x, y)) {
                        float sx = canvasX + x * zoom;
                        float sy = canvasY + y * zoom;
                        drawList.addRectFilled(sx, sy, sx + zoom, sy + zoom, fillColor);
                    }
                }
            }
        } else {
            // At low zoom, just dim the entire bounding area — the polygon outline
            // provides sufficient visual distinction
            drawList.addRectFilled(screenMinX, screenMinY, screenMaxX, screenMaxY, fillColor);
        }

        // Draw diagonal hatch lines across the bounding area
        // Only the areas outside the mask will show through because the mask area
        // has the texture drawn on top of it
        float spacing = HATCH_SPACING;
        float totalDiag = regionScreenW + regionScreenH;

        for (float offset = 0; offset < totalDiag; offset += spacing) {
            // Line from bottom-left to top-right diagonal
            float x0 = screenMinX + offset;
            float y0 = screenMinY;
            float x1 = screenMinX;
            float y1 = screenMinY + offset;

            // Clip the line to the bounding rectangle
            // The line goes from (x0, y0) to (x1, y1) where it's a diagonal
            if (x0 > screenMaxX) {
                y0 += (x0 - screenMaxX);
                x0 = screenMaxX;
            }
            if (y1 > screenMaxY) {
                x1 += (y1 - screenMaxY);
                y1 = screenMaxY;
            }

            if (x0 >= screenMinX && y0 <= screenMaxY && x1 <= screenMaxX && y1 >= screenMinY) {
                drawList.addLine(x0, y0, x1, y1, hatchColor, HATCH_LINE_THICKNESS);
            }
        }

        drawList.popClipRect();
    }

    /**
     * Render the polygon outline on the canvas.
     *
     * @param drawList ImGui draw list
     * @param mask     face boundary mask with polygon data
     * @param canvasX  canvas display X position
     * @param canvasY  canvas display Y position
     * @param zoom     current zoom level
     * @param opacity  overlay opacity
     */
    private void renderPolygonOutline(ImDrawList drawList, PolygonShapeMask mask,
                                       float canvasX, float canvasY,
                                       float zoom, float opacity) {

        float[] polyX = mask.getPolygonXCoords();
        float[] polyY = mask.getPolygonYCoords();
        int count = mask.getVertexCount();

        int alpha = (int) (opacity * (220.0f / 255.0f) * 255.0f);
        int outlineColor = (OUTLINE_COLOR & 0x00FFFFFF) | (alpha << 24);

        // Draw edges connecting consecutive vertices
        for (int i = 0; i < count; i++) {
            int next = (i + 1) % count;

            float screenX0 = canvasX + polyX[i] * zoom;
            float screenY0 = canvasY + polyY[i] * zoom;
            float screenX1 = canvasX + polyX[next] * zoom;
            float screenY1 = canvasY + polyY[next] * zoom;

            drawList.addLine(screenX0, screenY0, screenX1, screenY1,
                             outlineColor, OUTLINE_THICKNESS);
        }
    }
}
