package com.stonebreak.ui.terrainmapper;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.terrainmapper.managers.TerrainStateManager;
import com.stonebreak.ui.terrainmapper.handlers.TerrainActionHandler;
import com.stonebreak.ui.terrainmapper.handlers.TerrainInputHandler;
import com.stonebreak.ui.terrainmapper.handlers.TerrainMouseHandler;
import com.stonebreak.ui.terrainmapper.renderers.TerrainFooterRenderer;
import com.stonebreak.ui.terrainmapper.renderers.TerrainMapRenderer;
import com.stonebreak.ui.terrainmapper.renderers.TerrainSidebarRenderer;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;

/**
 * Terrain Mapper screen for creating new worlds with terrain visualization.
 * Serves as a facade that coordinates all modular components for world creation
 * with a visual preview of the terrain (currently static, will be dynamic in the future).
 */
public class TerrainMapperScreen {

    // ===== MODULAR COMPONENTS =====
    private final TerrainStateManager stateManager;
    private final WorldDiscoveryManager discoveryManager;
    private final TerrainActionHandler actionHandler;
    private final TerrainInputHandler inputHandler;
    private final TerrainMouseHandler mouseHandler;
    private final TerrainSidebarRenderer sidebarRenderer;
    private final TerrainMapRenderer mapRenderer;
    private final TerrainFooterRenderer footerRenderer;
    private final UIRenderer uiRenderer;

    // ===== CONSTRUCTOR =====

    /**
     * Creates a new TerrainMapperScreen with the specified UI renderer.
     * Initializes all modular components and their dependencies.
     */
    public TerrainMapperScreen(UIRenderer uiRenderer, WorldDiscoveryManager discoveryManager) {
        this.uiRenderer = uiRenderer;
        this.discoveryManager = discoveryManager;

        // Initialize state manager
        this.stateManager = new TerrainStateManager();

        // Initialize action handler
        this.actionHandler = new TerrainActionHandler(stateManager, discoveryManager);

        // Initialize renderers
        this.sidebarRenderer = new TerrainSidebarRenderer(uiRenderer, stateManager);
        this.mapRenderer = new TerrainMapRenderer(uiRenderer, stateManager);
        this.footerRenderer = new TerrainFooterRenderer(uiRenderer, stateManager);

        // Initialize input handlers (need renderers first for mouse handler)
        this.inputHandler = new TerrainInputHandler(stateManager, actionHandler);
        this.mouseHandler = new TerrainMouseHandler(stateManager, actionHandler, footerRenderer);
    }

    // ===== INPUT HANDLING =====

    /**
     * Handles character input for text fields.
     */
    public void handleCharacterInput(int codepoint) {
        inputHandler.handleCharacterInput(codepoint);
    }

    /**
     * Handles key input events.
     */
    public void handleKeyInput(int key, int action, int mods) {
        inputHandler.handleKeyInput(key, action, mods);
    }

    // ===== MOUSE HANDLING =====

    /**
     * Handles mouse movement for hover effects and dragging.
     */
    public void handleMouseMove(double x, double y, int width, int height) {
        mouseHandler.handleMouseMove(x, y, width, height);
    }

    /**
     * Handles mouse click events.
     */
    public void handleMouseClick(double x, double y, int width, int height, int button, int action) {
        mouseHandler.handleMouseClick(x, y, width, height, button, action);
    }

    /**
     * Handles mouse scroll events for zooming.
     */
    public void handleMouseScroll(double xOffset, double yOffset, double mouseX, double mouseY, int width, int height) {
        mouseHandler.handleScroll(xOffset, yOffset, mouseX, mouseY, width, height);
    }

    // ===== RENDERING =====

    /**
     * Renders the terrain mapper screen.
     */
    public void render(int windowWidth, int windowHeight) {
        // Render all components
        sidebarRenderer.render(windowWidth, windowHeight);
        mapRenderer.render(windowWidth, windowHeight);
        footerRenderer.render(windowWidth, windowHeight);
    }

    // ===== STATE MANAGEMENT =====

    /**
     * Resets the screen state for a fresh world creation session.
     * Called when opening the terrain mapper screen.
     */
    public void reset() {
        stateManager.reset();
    }

    /**
     * Gets the state manager for external access if needed.
     */
    public TerrainStateManager getStateManager() {
        return stateManager;
    }
}
