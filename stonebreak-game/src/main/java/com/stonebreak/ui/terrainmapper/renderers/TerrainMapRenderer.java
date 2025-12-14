package com.stonebreak.ui.terrainmapper.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.terrainmapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainmapper.managers.TerrainStateManager;
import com.stonebreak.ui.terrainmapper.visualization.NoiseRenderer;
import com.stonebreak.ui.terrainmapper.visualization.NoiseVisualizer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Renders the terrain preview map area.
 * Shows either a static grid (when visualization is inactive) or
 * noise visualizations (when visualization is active).
 */
public class TerrainMapRenderer {

    private final UIRenderer uiRenderer;
    private final TerrainStateManager stateManager;
    private NoiseRenderer noiseRenderer;

    public TerrainMapRenderer(UIRenderer uiRenderer, TerrainStateManager stateManager) {
        this.uiRenderer = uiRenderer;
        this.stateManager = stateManager;
        this.noiseRenderer = new NoiseRenderer(uiRenderer);
    }

    /**
     * Sets the noise renderer (used by TerrainMapperScreen after initialization).
     */
    public void setNoiseRenderer(NoiseRenderer noiseRenderer) {
        this.noiseRenderer = noiseRenderer;
    }

    /**
     * Gets the noise renderer.
     */
    public NoiseRenderer getNoiseRenderer() {
        return noiseRenderer;
    }

    /**
     * Renders the terrain preview map.
     *
     * @param windowWidth Window width
     * @param windowHeight Window height
     * @param visualizer Current visualizer (null if visualization not active)
     * @param seed World seed for visualization
     */
    public void render(int windowWidth, int windowHeight, NoiseVisualizer visualizer, long seed) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            // Calculate map area dimensions
            float mapX = TerrainMapperConfig.SIDEBAR_WIDTH;
            float mapY = 0;
            float mapWidth = windowWidth - TerrainMapperConfig.SIDEBAR_WIDTH;
            float mapHeight = windowHeight - TerrainMapperConfig.FOOTER_HEIGHT;

            // Draw map background
            renderBackground(vg, mapX, mapY, mapWidth, mapHeight, stack);

            // Determine if visualization is active
            boolean visualizationActive = stateManager.isVisualizationActive() && visualizer != null;

            if (visualizationActive) {
                // Render noise visualization
                renderVisualization(visualizer, seed, mapX, mapY, mapWidth, mapHeight);
            } else {
                // Render static grid (original behavior)
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
                renderOverlayText(vg, mapX + mapWidth / 2.0f, mapY + mapHeight / 2.0f, stack);
            }

            // Render hover overlay if visualization is active
            if (visualizationActive && noiseRenderer.hasHoverData()) {
                int mouseX = (int) stateManager.getLastMouseX();
                int mouseY = (int) stateManager.getLastMouseY();
                noiseRenderer.renderHoverOverlay(mouseX, mouseY, windowWidth, windowHeight);
            }
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
        nvgText(vg, centerX, centerY + 20, "Click 'Simulate Seed' to visualize terrain");
    }

    /**
     * Renders noise visualization to the map area.
     */
    private void renderVisualization(NoiseVisualizer visualizer, long seed,
                                    float mapX, float mapY, float mapWidth, float mapHeight) {
        // Calculate world bounds based on map dimensions
        // For now, use a fixed scale: 1 pixel = 4 blocks
        int blocksPerPixel = 4;
        int worldWidth = (int) (mapWidth * blocksPerPixel);
        int worldHeight = (int) (mapHeight * blocksPerPixel);

        // Center the world view at origin
        int worldMinX = -worldWidth / 2;
        int worldMinZ = -worldHeight / 2;
        int worldMaxX = worldWidth / 2;
        int worldMaxZ = worldHeight / 2;

        // Render the noise visualization
        noiseRenderer.render(
                visualizer,
                seed,
                mapX, mapY,
                mapWidth, mapHeight,
                worldMinX, worldMinZ,
                worldMaxX, worldMaxZ
        );

        // Calculate current mouse world coordinates
        int mouseX = (int) stateManager.getLastMouseX();
        int mouseY = (int) stateManager.getLastMouseY();

        float relativeX = (mouseX - mapX) / mapWidth;
        float relativeZ = (mouseY - mapY) / mapHeight;

        int worldMouseX = Integer.MIN_VALUE;
        int worldMouseZ = Integer.MIN_VALUE;

        // Only calculate world coords if mouse is within map bounds
        if (relativeX >= 0 && relativeX <= 1 && relativeZ >= 0 && relativeZ <= 1) {
            worldMouseX = worldMinX + (int) (relativeX * (worldMaxX - worldMinX));
            worldMouseZ = worldMinZ + (int) (relativeZ * (worldMaxZ - worldMinZ));
        }

        // Check if world coordinates changed since last frame
        double prevWorldMouseX = stateManager.getPrevWorldMouseX();
        double prevWorldMouseZ = stateManager.getPrevWorldMouseZ();

        boolean worldPosChanged = (worldMouseX != (int) prevWorldMouseX) ||
                                  (worldMouseZ != (int) prevWorldMouseZ);

        // Only update hover if position changed or first hover
        if (worldPosChanged || Double.isNaN(prevWorldMouseX)) {
            noiseRenderer.updateHover(visualizer, seed, mouseX, mouseY,
                    mapX, mapY, mapWidth, mapHeight,
                    worldMinX, worldMinZ, worldMaxX, worldMaxZ);

            // Cache current world position for next frame
            stateManager.setPrevWorldMouseX(worldMouseX);
            stateManager.setPrevWorldMouseZ(worldMouseZ);
        }
    }
}
