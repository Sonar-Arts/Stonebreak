package com.stonebreak.ui.terrainmapper.handlers;

import com.stonebreak.ui.terrainmapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainmapper.managers.TerrainStateManager;
import com.stonebreak.ui.terrainmapper.renderers.TerrainFooterRenderer;
import com.stonebreak.ui.terrainmapper.renderers.TerrainSidebarRenderer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Handles mouse input and interactions for the Terrain Mapper screen.
 * Manages mouse clicks, dragging for panning, scrolling for zoom, hover states,
 * visualization mode selection, and Simulate Seed button.
 */
public class TerrainMouseHandler {

    private final TerrainStateManager stateManager;
    private final TerrainActionHandler actionHandler;
    private final TerrainFooterRenderer footerRenderer;
    private final TerrainSidebarRenderer sidebarRenderer;
    private com.stonebreak.ui.terrainmapper.TerrainMapperScreen terrainMapperScreen;

    public TerrainMouseHandler(TerrainStateManager stateManager, TerrainActionHandler actionHandler,
                               TerrainFooterRenderer footerRenderer, TerrainSidebarRenderer sidebarRenderer) {
        this.stateManager = stateManager;
        this.actionHandler = actionHandler;
        this.footerRenderer = footerRenderer;
        this.sidebarRenderer = sidebarRenderer;
    }

    /**
     * Sets the terrain mapper screen reference (called after screen construction).
     * Needed to trigger visualization updates when Simulate Seed is clicked.
     */
    public void setTerrainMapperScreen(com.stonebreak.ui.terrainmapper.TerrainMapperScreen screen) {
        this.terrainMapperScreen = screen;
    }

    /**
     * Handles mouse movement for hover effects and dragging.
     */
    public void handleMouseMove(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        // Update button hover states
        boolean backHovered = footerRenderer.isBackButtonHovered(windowWidth, windowHeight, mouseX, mouseY);
        boolean createHovered = footerRenderer.isCreateButtonHovered(windowWidth, windowHeight, mouseX, mouseY);
        boolean simulateSeedHovered = footerRenderer.isSimulateSeedButtonHovered(windowWidth, windowHeight, mouseX, mouseY);
        stateManager.setBackButtonHovered(backHovered);
        stateManager.setCreateButtonHovered(createHovered);
        stateManager.setSimulateSeedButtonHovered(simulateSeedHovered);

        // Handle panning if dragging
        if (stateManager.isDragging()) {
            handlePanning(mouseX, mouseY);
        }

        // Update last mouse position
        stateManager.setLastMouseX(mouseX);
        stateManager.setLastMouseY(mouseY);
    }

    /**
     * Handles mouse click events.
     */
    public void handleMouseClick(double mouseX, double mouseY, int windowWidth, int windowHeight, int button, int action) {
        // Handle mouse button press
        if (action == GLFW_PRESS && button == GLFW_MOUSE_BUTTON_LEFT) {
            handleLeftClickPress(mouseX, mouseY, windowWidth, windowHeight);
        }

        // Handle mouse button release
        if (action == GLFW_RELEASE && button == GLFW_MOUSE_BUTTON_LEFT) {
            handleLeftClickRelease();
        }
    }

    /**
     * Handles left mouse button press.
     */
    private void handleLeftClickPress(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        // Check if clicking on text input fields
        if (isClickInWorldNameField(mouseX, mouseY)) {
            stateManager.setActiveField(TerrainStateManager.ActiveField.WORLD_NAME);
            stateManager.getWorldNameField().handleMouseClick(mouseX, mouseY);
            return;
        }

        if (isClickInSeedField(mouseX, mouseY)) {
            stateManager.setActiveField(TerrainStateManager.ActiveField.SEED);
            stateManager.getSeedField().handleMouseClick(mouseX, mouseY);
            return;
        }

        // Check if clicking on buttons
        if (footerRenderer.isBackButtonHovered(windowWidth, windowHeight, mouseX, mouseY)) {
            actionHandler.goBack();
            return;
        }

        if (footerRenderer.isCreateButtonHovered(windowWidth, windowHeight, mouseX, mouseY)) {
            actionHandler.createWorld();
            return;
        }

        if (footerRenderer.isSimulateSeedButtonHovered(windowWidth, windowHeight, mouseX, mouseY)) {
            handleSimulateSeedClick();
            return;
        }

        // Check if clicking on visualization mode selector
        int fieldY = TerrainMapperConfig.TITLE_HEIGHT + TerrainMapperConfig.PADDING;
        int selectorY = fieldY + (TerrainMapperConfig.INPUT_FIELD_HEIGHT + TerrainMapperConfig.COMPONENT_SPACING) * 2 + 30;
        TerrainStateManager.VisualizationMode clickedMode = sidebarRenderer.getClickedMode(mouseX, mouseY, selectorY);
        if (clickedMode != null) {
            stateManager.setSelectedVisualizationMode(clickedMode);
            // If visualization is active, invalidate cache to trigger re-render with new mode
            // This will be handled in TerrainMapperScreen
            return;
        }

        // Check if clicking in the map area to start panning
        if (isClickInMapArea(mouseX, mouseY, windowWidth, windowHeight)) {
            stateManager.setDragging(true);
            stateManager.setActiveField(TerrainStateManager.ActiveField.NONE);
        }
    }

    /**
     * Handles left mouse button release.
     */
    private void handleLeftClickRelease() {
        stateManager.setDragging(false);
    }

    /**
     * Handles panning the map when dragging.
     */
    private void handlePanning(double mouseX, double mouseY) {
        double deltaX = mouseX - stateManager.getLastMouseX();
        double deltaY = mouseY - stateManager.getLastMouseY();

        // Apply pan with zoom compensation (slower pan when zoomed in)
        float panSpeed = 1.0f / stateManager.getZoom();
        stateManager.adjustPan((float) deltaX * panSpeed, (float) deltaY * panSpeed);
    }

    /**
     * Handles scroll events for zooming.
     */
    public void handleScroll(double xOffset, double yOffset, double mouseX, double mouseY,
                            int windowWidth, int windowHeight) {
        // Only zoom if mouse is in the map area
        if (!isClickInMapArea(mouseX, mouseY, windowWidth, windowHeight)) {
            return;
        }

        // Calculate new zoom level
        float oldZoom = stateManager.getZoom();
        float newZoom = oldZoom + (float) yOffset * TerrainMapperConfig.ZOOM_STEP;

        // Clamp zoom to valid range
        newZoom = Math.max(TerrainMapperConfig.ZOOM_MIN, Math.min(TerrainMapperConfig.ZOOM_MAX, newZoom));

        stateManager.setZoom(newZoom);
    }

    /**
     * Checks if a click is within the world name field bounds.
     */
    private boolean isClickInWorldNameField(double mouseX, double mouseY) {
        int fieldY = TerrainMapperConfig.TITLE_HEIGHT + TerrainMapperConfig.PADDING;
        float x = TerrainMapperConfig.PADDING;
        float y = fieldY;
        float width = TerrainMapperConfig.INPUT_FIELD_WIDTH;
        float height = TerrainMapperConfig.INPUT_FIELD_HEIGHT;

        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    /**
     * Checks if a click is within the seed field bounds.
     */
    private boolean isClickInSeedField(double mouseX, double mouseY) {
        int fieldY = TerrainMapperConfig.TITLE_HEIGHT + TerrainMapperConfig.PADDING;
        float x = TerrainMapperConfig.PADDING;
        float y = fieldY + TerrainMapperConfig.INPUT_FIELD_HEIGHT + TerrainMapperConfig.COMPONENT_SPACING;
        float width = TerrainMapperConfig.INPUT_FIELD_WIDTH;
        float height = TerrainMapperConfig.INPUT_FIELD_HEIGHT;

        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    /**
     * Checks if a click is within the map area.
     */
    private boolean isClickInMapArea(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        float mapX = TerrainMapperConfig.SIDEBAR_WIDTH;
        float mapY = 0;
        float mapWidth = windowWidth - TerrainMapperConfig.SIDEBAR_WIDTH;
        float mapHeight = windowHeight - TerrainMapperConfig.FOOTER_HEIGHT;

        return mouseX >= mapX && mouseX <= mapX + mapWidth &&
                mouseY >= mapY && mouseY <= mapY + mapHeight;
    }

    /**
     * Handles the Simulate Seed button click.
     * - If seed field is empty, generates a random seed and populates the field
     * - Regenerates visualization with the seed from the field
     * - Toggles visualization on/off
     */
    private void handleSimulateSeedClick() {
        if (terrainMapperScreen == null) {
            System.err.println("TerrainMapperScreen not set in TerrainMouseHandler!");
            return;
        }

        // If turning visualization ON
        if (!stateManager.isVisualizationActive()) {
            // Check if seed field is empty
            String seedText = stateManager.getSeedField().getText().trim();
            if (seedText.isEmpty() || seedText.equals("Leave blank for random")) {
                // Generate random seed and populate field
                terrainMapperScreen.generateAndSetRandomSeed();
            }

            // Regenerate visualization with the seed from the field
            terrainMapperScreen.updateVisualization();

            // Activate visualization
            stateManager.setVisualizationActive(true);
        } else {
            // Turning visualization OFF - just toggle the flag
            stateManager.setVisualizationActive(false);
        }
    }
}
