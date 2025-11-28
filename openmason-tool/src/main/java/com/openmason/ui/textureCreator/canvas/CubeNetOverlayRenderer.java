package com.openmason.ui.textureCreator.canvas;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders overlay for 64x48 cube net textures showing face boundaries and labels.
 */
public class CubeNetOverlayRenderer {

    private static final Logger logger = LoggerFactory.getLogger(CubeNetOverlayRenderer.class);

    // Cube net dimensions
    private static final int CUBE_NET_WIDTH = 64;
    private static final int CUBE_NET_HEIGHT = 48;
    private static final int FACE_SIZE = 16;

    // Face region data structure
        private record FaceRegion(String label, int pixelX, int pixelY) {
    }

    // Face regions (pixel coordinates in 64x48 canvas)
    private static final FaceRegion FACE_TOP = new FaceRegion("TOP", 16, 0);
    private static final FaceRegion FACE_LEFT = new FaceRegion("LEFT", 0, 16);
    private static final FaceRegion FACE_FRONT = new FaceRegion("FRONT", 16, 16);
    private static final FaceRegion FACE_RIGHT = new FaceRegion("RIGHT", 32, 16);
    private static final FaceRegion FACE_BACK = new FaceRegion("BACK", 48, 16);
    private static final FaceRegion FACE_BOTTOM = new FaceRegion("BOTTOM", 16, 32);

    private static final FaceRegion[] ALL_FACES = {
        FACE_TOP, FACE_LEFT, FACE_FRONT, FACE_RIGHT, FACE_BACK, FACE_BOTTOM
    };

    // Non-editable region coordinates (pixel coordinates in 64x48 canvas)
        private record NonEditableRegion(int pixelX, int pixelY) {
    }

    private static final NonEditableRegion[] NON_EDITABLE_REGIONS = {
        // Row 0: columns 0, 2, 3
        new NonEditableRegion(0, 0),
        new NonEditableRegion(32, 0),
        new NonEditableRegion(48, 0),
        // Row 2: columns 0, 2, 3
        new NonEditableRegion(0, 32),
        new NonEditableRegion(32, 32),
        new NonEditableRegion(48, 32)
    };

    // Visual styling constants
    private static final float BORDER_THICKNESS = 2.0f;
    private static final int BORDER_BASE_COLOR = 0xFFFFFFFF; // White (RGBA)
    private static final int LABEL_TEXT_BASE_COLOR = 0xFF282828; // Dark gray text (RGBA)
    private static final int LABEL_BG_BASE_COLOR = 0xFFFFFFFF; // White background (RGBA)
    private static final int NON_EDITABLE_BASE_COLOR = 0xFF404040; // Dark gray (RGBA)
    private static final float LABEL_PADDING = 4.0f; // Padding around text

    /**
     * Create cube net overlay renderer.
     */
    public CubeNetOverlayRenderer() {
        logger.debug("Cube net overlay renderer created");
    }

    /**
     * Render cube net overlay if canvas dimensions match 64x48.
     *
     * @param canvasWidth canvas width in pixels
     * @param canvasHeight canvas height in pixels
     * @param canvasX canvas display X position
     * @param canvasY canvas display Y position
     * @param zoom current zoom level
     * @param opacity overlay opacity (0.0 to 1.0)
     */
    public void render(int canvasWidth, int canvasHeight, float canvasX, float canvasY,
                      float zoom, float opacity) {
        // Only render for 64x48 cube net canvases
        if (!isValidCubeNetCanvas(canvasWidth, canvasHeight)) {
            return;
        }

        // Skip rendering if opacity is zero
        if (opacity <= 0.0f) {
            return;
        }

        ImDrawList drawList = ImGui.getWindowDrawList();

        // Render non-editable regions first (so they're behind everything else)
        renderNonEditableRegions(drawList, canvasX, canvasY, zoom, opacity);

        // Render face boundaries
        renderFaceBoundaries(drawList, canvasX, canvasY, zoom, opacity);

        // Render face labels on top
        renderFaceLabels(drawList, canvasX, canvasY, zoom, opacity);
    }

    /**
     * Render boundaries around each face region.
     *
     * @param drawList ImGui draw list
     * @param canvasX canvas display X position
     * @param canvasY canvas display Y position
     * @param zoom current zoom level
     * @param opacity overlay opacity
     */
    private void renderFaceBoundaries(ImDrawList drawList, float canvasX, float canvasY,
                                     float zoom, float opacity) {
        // Calculate alpha value from opacity
        int alpha = (int) (opacity * 255.0f);
        int borderColor = (BORDER_BASE_COLOR & 0x00FFFFFF) | (alpha << 24);

        for (FaceRegion face : ALL_FACES) {
            // Calculate screen position and size
            float screenX = canvasX + (face.pixelX * zoom);
            float screenY = canvasY + (face.pixelY * zoom);
            float screenSize = FACE_SIZE * zoom;

            // Draw rectangle border
            drawList.addRect(
                screenX, screenY,
                screenX + screenSize, screenY + screenSize,
                borderColor, 0.0f, 0, BORDER_THICKNESS
            );
        }
    }

    /**
     * Render text labels for each face region.
     *
     * @param drawList ImGui draw list
     * @param canvasX canvas display X position
     * @param canvasY canvas display Y position
     * @param zoom current zoom level
     * @param opacity overlay opacity
     */
    private void renderFaceLabels(ImDrawList drawList, float canvasX, float canvasY,
                                  float zoom, float opacity) {
        // Calculate alpha values from opacity
        int alpha = (int) (opacity * 255.0f);
        int bgAlpha = (int) (opacity * 0.8f * 255.0f); // 80% of requested opacity for background

        // Dark text on light background for better visibility
        int labelTextColor = (LABEL_TEXT_BASE_COLOR & 0x00FFFFFF) | (alpha << 24);
        int labelBgColor = (LABEL_BG_BASE_COLOR & 0x00FFFFFF) | (bgAlpha << 24);

        // Only render labels if zoom is high enough to read them
        if (zoom < 2.0f) {
            return; // Too small to read
        }

        for (FaceRegion face : ALL_FACES) {
            // Calculate screen position and size
            float screenX = canvasX + (face.pixelX * zoom);
            float screenY = canvasY + (face.pixelY * zoom);
            float screenSize = FACE_SIZE * zoom;

            // Calculate text size
            ImVec2 textSize = ImGui.calcTextSize(face.label);

            // Center text within face region
            float textX = screenX + (screenSize - textSize.x) / 2.0f;
            float textY = screenY + (screenSize - textSize.y) / 2.0f;

            // Draw background rectangle for better visibility
            drawList.addRectFilled(
                textX - LABEL_PADDING,
                textY - LABEL_PADDING,
                textX + textSize.x + LABEL_PADDING,
                textY + textSize.y + LABEL_PADDING,
                labelBgColor,
                2.0f  // Slight rounding for corners
            );

            // Draw text label on top of background
            drawList.addText(textX, textY, labelTextColor, face.label);
        }
    }

    /**
     * Render non-editable region indicators.
     *
     * @param drawList ImGui draw list
     * @param canvasX canvas display X position
     * @param canvasY canvas display Y position
     * @param zoom current zoom level
     * @param opacity overlay opacity
     */
    private void renderNonEditableRegions(ImDrawList drawList, float canvasX, float canvasY,
                                         float zoom, float opacity) {
        // Calculate alpha value from opacity (use lower opacity for subtle effect)
        int alpha = (int) (opacity * 0.3f * 255.0f); // 30% of requested opacity
        int fillColor = (NON_EDITABLE_BASE_COLOR & 0x00FFFFFF) | (alpha << 24);

        for (NonEditableRegion region : NON_EDITABLE_REGIONS) {
            // Calculate screen position and size
            float screenX = canvasX + (region.pixelX * zoom);
            float screenY = canvasY + (region.pixelY * zoom);
            float screenSize = FACE_SIZE * zoom;

            // Draw filled rectangle with subtle shading
            drawList.addRectFilled(
                screenX, screenY,
                screenX + screenSize, screenY + screenSize,
                fillColor
            );

            // Draw diagonal cross-hatch pattern for better visibility
            if (zoom >= 3.0f) { // Only show pattern when zoomed in enough
                int lineColor = (NON_EDITABLE_BASE_COLOR & 0x00FFFFFF) | (alpha << 23); // 50% of fill alpha

                // Diagonal line from top-left to bottom-right
                drawList.addLine(
                    screenX, screenY,
                    screenX + screenSize, screenY + screenSize,
                    lineColor, 1.0f
                );

                // Diagonal line from top-right to bottom-left
                drawList.addLine(
                    screenX + screenSize, screenY,
                    screenX, screenY + screenSize,
                    lineColor, 1.0f
                );
            }
        }
    }

    /**
     * Check if canvas dimensions are valid for cube net format.
     *
     * @param width canvas width
     * @param height canvas height
     * @return true if 64x48, false otherwise
     */
    private boolean isValidCubeNetCanvas(int width, int height) {
        return width == CUBE_NET_WIDTH && height == CUBE_NET_HEIGHT;
    }
}
