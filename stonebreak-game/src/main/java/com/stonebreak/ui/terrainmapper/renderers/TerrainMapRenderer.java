package com.stonebreak.ui.terrainmapper.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.terrainmapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainmapper.managers.TerrainStateManager;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Renders the terrain preview map area.
 * Currently displays a static grid with placeholder text and supports pan/zoom.
 */
public class TerrainMapRenderer {

    private final UIRenderer uiRenderer;
    private final TerrainStateManager stateManager;

    public TerrainMapRenderer(UIRenderer uiRenderer, TerrainStateManager stateManager) {
        this.uiRenderer = uiRenderer;
        this.stateManager = stateManager;
    }

    /**
     * Renders the terrain preview map.
     */
    public void render(int windowWidth, int windowHeight) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            // Calculate map area dimensions
            float mapX = TerrainMapperConfig.SIDEBAR_WIDTH;
            float mapY = 0;
            float mapWidth = windowWidth - TerrainMapperConfig.SIDEBAR_WIDTH;
            float mapHeight = windowHeight - TerrainMapperConfig.FOOTER_HEIGHT;

            // Draw map background
            renderBackground(vg, mapX, mapY, mapWidth, mapHeight, stack);

            // Save current transform state
            nvgSave(vg);

            // Set up scissor to clip rendering to map area
            nvgScissor(vg, mapX, mapY, mapWidth, mapHeight);

            // Apply pan and zoom transformations
            float centerX = mapX + mapWidth / 2.0f;
            float centerY = mapY + mapHeight / 2.0f;

            nvgTranslate(vg, centerX, centerY);
            nvgScale(vg, stateManager.getZoom(), stateManager.getZoom());
            nvgTranslate(vg, stateManager.getPanX(), stateManager.getPanY());
            nvgTranslate(vg, -centerX, -centerY);

            // Draw the grid
            renderGrid(vg, mapX, mapY, mapWidth, mapHeight, stack);

            // Restore transform state
            nvgRestore(vg);

            // Draw overlay text (not affected by pan/zoom)
            renderOverlayText(vg, centerX, centerY, stack);
        }
    }

    /**
     * Renders the background of the map area.
     */
    private void renderBackground(long vg, float x, float y, float width, float height, MemoryStack stack) {
        nvgBeginPath(vg);
        nvgRect(vg, x, y, width, height);
        nvgFillColor(vg, uiRenderer.nvgRGBA(
                TerrainMapperConfig.GRID_BG_COLOR[0],
                TerrainMapperConfig.GRID_BG_COLOR[1],
                TerrainMapperConfig.GRID_BG_COLOR[2],
                TerrainMapperConfig.GRID_BG_COLOR[3],
                NVGColor.malloc(stack)
        ));
        nvgFill(vg);
    }

    /**
     * Renders a grid pattern for the terrain preview.
     * This is a static grid that will be replaced with actual terrain visualization later.
     */
    private void renderGrid(long vg, float mapX, float mapY, float mapWidth, float mapHeight, MemoryStack stack) {
        int gridSize = TerrainMapperConfig.GRID_CELL_SIZE;
        float lineWidth = TerrainMapperConfig.GRID_LINE_WIDTH;

        nvgStrokeColor(vg, uiRenderer.nvgRGBA(
                TerrainMapperConfig.GRID_LINE_COLOR[0],
                TerrainMapperConfig.GRID_LINE_COLOR[1],
                TerrainMapperConfig.GRID_LINE_COLOR[2],
                TerrainMapperConfig.GRID_LINE_COLOR[3],
                NVGColor.malloc(stack)
        ));
        nvgStrokeWidth(vg, lineWidth);

        // Calculate grid bounds (extended to cover panned/zoomed area)
        float zoom = stateManager.getZoom();
        float extendedWidth = mapWidth / zoom + Math.abs(stateManager.getPanX()) * 2;
        float extendedHeight = mapHeight / zoom + Math.abs(stateManager.getPanY()) * 2;

        float startX = mapX - extendedWidth / 2;
        float startY = mapY - extendedHeight / 2;
        float endX = mapX + mapWidth + extendedWidth / 2;
        float endY = mapY + mapHeight + extendedHeight / 2;

        // Draw vertical lines
        for (float x = startX - (startX % gridSize); x <= endX; x += gridSize) {
            nvgBeginPath(vg);
            nvgMoveTo(vg, x, startY);
            nvgLineTo(vg, x, endY);
            nvgStroke(vg);
        }

        // Draw horizontal lines
        for (float y = startY - (startY % gridSize); y <= endY; y += gridSize) {
            nvgBeginPath(vg);
            nvgMoveTo(vg, startX, y);
            nvgLineTo(vg, endX, y);
            nvgStroke(vg);
        }
    }

    /**
     * Renders overlay text that is not affected by pan/zoom.
     */
    private void renderOverlayText(long vg, float centerX, float centerY, MemoryStack stack) {
        // Draw "Terrain Preview" title
        nvgFontSize(vg, TerrainMapperConfig.PREVIEW_TITLE_FONT_SIZE);
        nvgFontFace(vg, "minecraft");
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
        nvgFillColor(vg, uiRenderer.nvgRGBA(
                TerrainMapperConfig.TEXT_COLOR[0],
                TerrainMapperConfig.TEXT_COLOR[1],
                TerrainMapperConfig.TEXT_COLOR[2],
                (int)(0.8f * 255),  // Slightly transparent
                NVGColor.malloc(stack)
        ));
        nvgText(vg, centerX, centerY - 20, "Terrain Preview");

        // Draw instructions
        nvgFontSize(vg, TerrainMapperConfig.PREVIEW_SUBTITLE_FONT_SIZE);
        nvgFillColor(vg, uiRenderer.nvgRGBA(
                TerrainMapperConfig.TEXT_COLOR_SECONDARY[0],
                TerrainMapperConfig.TEXT_COLOR_SECONDARY[1],
                TerrainMapperConfig.TEXT_COLOR_SECONDARY[2],
                (int)(0.7f * 255),  // More transparent
                NVGColor.malloc(stack)
        ));
        nvgText(vg, centerX, centerY + 20, "Drag to pan, scroll to zoom");
    }
}
