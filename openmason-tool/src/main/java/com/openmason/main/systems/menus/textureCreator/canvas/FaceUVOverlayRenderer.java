package com.openmason.main.systems.menus.textureCreator.canvas;

import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureMapping.UVRegion;
import com.openmason.main.systems.rendering.model.gmr.uv.IFaceTextureManager;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Renders UV region overlays for arbitrary face meshes on the texture canvas.
 *
 * <p>Visualizes each face's UV region as a bordered rectangle, with dimming
 * for non-active materials and highlighting for the selected face. Replaces
 * the rigid 6-face cube net approach with support for any number of faces
 * at arbitrary UV positions.
 *
 * <p>Stateless renderer — all data is passed via the {@link #render} method.
 *
 * @see CubeNetOverlayRenderer
 * @see IFaceTextureManager
 */
public class FaceUVOverlayRenderer {

    private static final Logger logger = LoggerFactory.getLogger(FaceUVOverlayRenderer.class);

    // ── Visual styling constants ─────────────────────────────────────────

    private static final float BORDER_THICKNESS = 2.0f;
    private static final float SELECTED_BORDER_THICKNESS = 3.0f;

    private static final int BORDER_BASE_COLOR = 0xFFFFFFFF;           // White (ABGR)
    private static final int SELECTED_BORDER_COLOR = ImColor.rgba(51, 153, 255, 255); // Accent blue
    private static final int SELECTED_FILL_COLOR = ImColor.rgba(51, 153, 255, 38);    // Accent blue ~15%
    private static final int DIMMED_FILL_BASE_COLOR = 0xFF404040;      // Dark gray (ABGR)

    private static final int LABEL_TEXT_BASE_COLOR = 0xFF282828;       // Dark gray text
    private static final int LABEL_BG_BASE_COLOR = 0xFFFFFFFF;        // White background
    private static final int SELECTED_LABEL_BG_COLOR = ImColor.rgba(51, 153, 255, 255); // Accent blue
    private static final int SELECTED_LABEL_TEXT_COLOR = 0xFFFFFFFF;   // White text on accent

    private static final float LABEL_PADDING = 4.0f;
    private static final float MIN_UV_DIMENSION = 0.001f;
    private static final float LABEL_MIN_ZOOM = 2.0f;

    /**
     * Create face UV overlay renderer.
     */
    public FaceUVOverlayRenderer() {
        logger.debug("Face UV overlay renderer created");
    }

    /**
     * Render UV region overlays for all face mappings.
     *
     * <p>Three-pass pipeline:
     * <ol>
     *   <li>Dimmed regions — non-active material faces drawn as dark overlays</li>
     *   <li>Active material face borders — white borders, selected face highlighted</li>
     *   <li>Face ID labels — centered text with pill backgrounds (zoom >= 2.0)</li>
     * </ol>
     *
     * @param faceTextureManager Source of face mapping data
     * @param activeMaterialId   Currently active material ID in the editor
     * @param selectedFaceId     Currently selected face ID (-1 for none)
     * @param canvasWidth        Canvas width in pixels
     * @param canvasHeight       Canvas height in pixels
     * @param canvasX            Canvas display X position (screen space)
     * @param canvasY            Canvas display Y position (screen space)
     * @param zoom               Current zoom level
     * @param opacity            Overlay opacity (0.0 to 1.0)
     */
    public void render(IFaceTextureManager faceTextureManager,
                       int activeMaterialId, int selectedFaceId,
                       int canvasWidth, int canvasHeight,
                       float canvasX, float canvasY,
                       float zoom, float opacity) {

        if (faceTextureManager == null || opacity <= 0.0f) {
            return;
        }

        Collection<FaceTextureMapping> allMappings = faceTextureManager.getAllMappings();
        if (allMappings.isEmpty()) {
            return;
        }

        ImDrawList drawList = ImGui.getWindowDrawList();

        // Pass 1: Dimmed regions for non-active materials
        renderDimmedRegions(drawList, allMappings, activeMaterialId,
                            canvasWidth, canvasHeight, canvasX, canvasY, zoom, opacity);

        // Pass 2: Active material face borders
        renderActiveFaceBorders(drawList, allMappings, activeMaterialId, selectedFaceId,
                                 canvasWidth, canvasHeight, canvasX, canvasY, zoom, opacity);

        // Pass 3: Face ID labels (only when zoomed in enough)
        renderFaceLabels(drawList, allMappings, activeMaterialId, selectedFaceId,
                          canvasWidth, canvasHeight, canvasX, canvasY, zoom, opacity);
    }

    /**
     * Render dimmed fill for faces belonging to non-active materials.
     */
    private void renderDimmedRegions(ImDrawList drawList, Collection<FaceTextureMapping> mappings,
                                     int activeMaterialId,
                                     int canvasWidth, int canvasHeight,
                                     float canvasX, float canvasY,
                                     float zoom, float opacity) {

        int dimAlpha = (int) (opacity * 0.2f * 255.0f);
        int fillColor = (DIMMED_FILL_BASE_COLOR & 0x00FFFFFF) | (dimAlpha << 24);

        for (FaceTextureMapping mapping : mappings) {
            if (mapping.materialId() == activeMaterialId) {
                continue;
            }

            UVRegion region = mapping.uvRegion();
            if (isRegionTooSmall(region)) {
                continue;
            }

            float screenX = canvasX + (region.u0() * canvasWidth * zoom);
            float screenY = canvasY + (region.v0() * canvasHeight * zoom);
            float screenW = region.width() * canvasWidth * zoom;
            float screenH = region.height() * canvasHeight * zoom;

            drawList.addRectFilled(
                screenX, screenY,
                screenX + screenW, screenY + screenH,
                fillColor
            );
        }
    }

    /**
     * Render bordered rectangles for faces belonging to the active material.
     */
    private void renderActiveFaceBorders(ImDrawList drawList, Collection<FaceTextureMapping> mappings,
                                          int activeMaterialId, int selectedFaceId,
                                          int canvasWidth, int canvasHeight,
                                          float canvasX, float canvasY,
                                          float zoom, float opacity) {

        int alpha = (int) (opacity * 255.0f);
        int borderColor = (BORDER_BASE_COLOR & 0x00FFFFFF) | (alpha << 24);

        for (FaceTextureMapping mapping : mappings) {
            if (mapping.materialId() != activeMaterialId) {
                continue;
            }

            UVRegion region = mapping.uvRegion();
            if (isRegionTooSmall(region)) {
                continue;
            }

            float screenX = canvasX + (region.u0() * canvasWidth * zoom);
            float screenY = canvasY + (region.v0() * canvasHeight * zoom);
            float screenW = region.width() * canvasWidth * zoom;
            float screenH = region.height() * canvasHeight * zoom;

            boolean isSelected = (mapping.faceId() == selectedFaceId);

            if (isSelected) {
                // Selected face: accent fill + thicker accent border
                int selectedFillAlpha = (int) (opacity * (38.0f / 255.0f) * 255.0f);
                int adjustedFillColor = (SELECTED_FILL_COLOR & 0x00FFFFFF) | (selectedFillAlpha << 24);

                drawList.addRectFilled(
                    screenX, screenY,
                    screenX + screenW, screenY + screenH,
                    adjustedFillColor
                );

                int selectedBorderAlpha = alpha;
                int adjustedBorderColor = (SELECTED_BORDER_COLOR & 0x00FFFFFF) | (selectedBorderAlpha << 24);

                drawList.addRect(
                    screenX, screenY,
                    screenX + screenW, screenY + screenH,
                    adjustedBorderColor, 0.0f, 0, SELECTED_BORDER_THICKNESS
                );
            } else {
                // Normal face: white border
                drawList.addRect(
                    screenX, screenY,
                    screenX + screenW, screenY + screenH,
                    borderColor, 0.0f, 0, BORDER_THICKNESS
                );
            }
        }
    }

    /**
     * Render centered face ID labels for faces in the active material.
     */
    private void renderFaceLabels(ImDrawList drawList, Collection<FaceTextureMapping> mappings,
                                   int activeMaterialId, int selectedFaceId,
                                   int canvasWidth, int canvasHeight,
                                   float canvasX, float canvasY,
                                   float zoom, float opacity) {

        if (zoom < LABEL_MIN_ZOOM) {
            return;
        }

        int alpha = (int) (opacity * 255.0f);
        int bgAlpha = (int) (opacity * 0.8f * 255.0f);

        int labelTextColor = (LABEL_TEXT_BASE_COLOR & 0x00FFFFFF) | (alpha << 24);
        int labelBgColor = (LABEL_BG_BASE_COLOR & 0x00FFFFFF) | (bgAlpha << 24);

        for (FaceTextureMapping mapping : mappings) {
            if (mapping.materialId() != activeMaterialId) {
                continue;
            }

            UVRegion region = mapping.uvRegion();
            if (isRegionTooSmall(region)) {
                continue;
            }

            float screenX = canvasX + (region.u0() * canvasWidth * zoom);
            float screenY = canvasY + (region.v0() * canvasHeight * zoom);
            float screenW = region.width() * canvasWidth * zoom;
            float screenH = region.height() * canvasHeight * zoom;

            String label = "F" + mapping.faceId();
            ImVec2 textSize = ImGui.calcTextSize(label);

            // Center text within the UV region
            float textX = screenX + (screenW - textSize.x) / 2.0f;
            float textY = screenY + (screenH - textSize.y) / 2.0f;

            boolean isSelected = (mapping.faceId() == selectedFaceId);

            if (isSelected) {
                // Selected label: accent background, white text
                int selectedBgAlpha = (int) (opacity * 0.9f * 255.0f);
                int adjustedSelectedBg = (SELECTED_LABEL_BG_COLOR & 0x00FFFFFF) | (selectedBgAlpha << 24);
                int selectedTextColor = (SELECTED_LABEL_TEXT_COLOR & 0x00FFFFFF) | (alpha << 24);

                drawList.addRectFilled(
                    textX - LABEL_PADDING, textY - LABEL_PADDING,
                    textX + textSize.x + LABEL_PADDING, textY + textSize.y + LABEL_PADDING,
                    adjustedSelectedBg, 2.0f
                );

                drawList.addText(textX, textY, selectedTextColor, label);
            } else {
                // Normal label: white background, dark text
                drawList.addRectFilled(
                    textX - LABEL_PADDING, textY - LABEL_PADDING,
                    textX + textSize.x + LABEL_PADDING, textY + textSize.y + LABEL_PADDING,
                    labelBgColor, 2.0f
                );

                drawList.addText(textX, textY, labelTextColor, label);
            }
        }
    }

    /**
     * Check if a UV region is too small to render meaningfully.
     *
     * @param region UV region to check
     * @return true if width or height is effectively zero
     */
    private boolean isRegionTooSmall(UVRegion region) {
        return region.width() < MIN_UV_DIMENSION || region.height() < MIN_UV_DIMENSION;
    }
}
