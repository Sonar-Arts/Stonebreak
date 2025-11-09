package com.stonebreak.ui.terrainmapper;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.terrainmapper.managers.TerrainStateManager;
import com.stonebreak.ui.terrainmapper.handlers.TerrainActionHandler;
import com.stonebreak.ui.terrainmapper.handlers.TerrainInputHandler;
import com.stonebreak.ui.terrainmapper.handlers.TerrainMouseHandler;
import com.stonebreak.ui.terrainmapper.renderers.TerrainFooterRenderer;
import com.stonebreak.ui.terrainmapper.renderers.TerrainMapRenderer;
import com.stonebreak.ui.terrainmapper.renderers.TerrainSidebarRenderer;
import com.stonebreak.ui.terrainmapper.visualization.NoiseVisualizer;
import com.stonebreak.ui.terrainmapper.visualization.impl.*;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Terrain Mapper screen for creating new worlds with terrain visualization.
 * Serves as a facade that coordinates all modular components for world creation
 * with interactive noise visualization capabilities.
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

    // ===== VISUALIZATION COMPONENTS =====
    private final Map<TerrainStateManager.VisualizationMode, NoiseVisualizer> visualizers;
    private long currentSeed;

    // ===== CONSTRUCTOR =====

    /**
     * Creates a new TerrainMapperScreen with the specified UI renderer.
     * Initializes all modular components, visualizers, and their dependencies.
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
        this.mouseHandler = new TerrainMouseHandler(stateManager, actionHandler, footerRenderer, sidebarRenderer);

        // Wire the screen reference to the mouse handler (needed for Simulate Seed button)
        this.mouseHandler.setTerrainMapperScreen(this);

        // Initialize visualizers
        this.visualizers = new HashMap<>();
        this.currentSeed = 0;
        initializeVisualizers();
    }

    /**
     * Initializes all noise visualizers with default configuration.
     */
    private void initializeVisualizers() {
        // Use default terrain generation config
        TerrainGenerationConfig config = TerrainGenerationConfig.defaultConfig();

        // Get seed from seed field or generate random
        long seed = parseSeed();

        // Create all visualizers
        visualizers.put(TerrainStateManager.VisualizationMode.TERRAIN_HEIGHT,
                new HeightMapVisualizer(seed, config));
        visualizers.put(TerrainStateManager.VisualizationMode.CONTINENTALNESS,
                new ContinentalnessVisualizer(seed, config));
        visualizers.put(TerrainStateManager.VisualizationMode.EROSION,
                new ErosionVisualizer(seed, config));
        visualizers.put(TerrainStateManager.VisualizationMode.PEAKS_VALLEYS,
                new PeaksValleysVisualizer(seed, config));
        visualizers.put(TerrainStateManager.VisualizationMode.WEIRDNESS,
                new WeirdnessVisualizer(seed, config));
        visualizers.put(TerrainStateManager.VisualizationMode.TEMPERATURE,
                new TemperatureVisualizer(seed, config));
        visualizers.put(TerrainStateManager.VisualizationMode.HUMIDITY,
                new HumidityVisualizer(seed, config));
        visualizers.put(TerrainStateManager.VisualizationMode.BIOME_DISTRIBUTION,
                new BiomeVisualizer(seed, config));

        this.currentSeed = seed;
    }

    /**
     * Parses the seed from the seed input field.
     * Returns the numeric seed or the hash of the text.
     * Note: Does NOT generate random seeds - call generateAndSetRandomSeed() for that.
     */
    private long parseSeed() {
        String seedText = stateManager.getSeedField().getText().trim();

        // Empty field - return 0 as default (caller should handle this)
        if (seedText.isEmpty() || seedText.equals("Leave blank for random")) {
            return 0;
        }

        try {
            return Long.parseLong(seedText);
        } catch (NumberFormatException e) {
            // Use hash of text as seed
            return seedText.hashCode();
        }
    }

    /**
     * Generates a random seed and populates the seed field with it.
     * Called when user clicks "Simulate Seed" with an empty seed field.
     */
    public void generateAndSetRandomSeed() {
        long randomSeed = System.currentTimeMillis();
        stateManager.getSeedField().setText(String.valueOf(randomSeed));
    }

    /**
     * Regenerates all visualizers with the current seed from the seed field.
     * Called when user clicks "Simulate Seed" button or changes visualization settings.
     * This is the ONLY method that should trigger visualization regeneration.
     */
    public void updateVisualization() {
        long newSeed = parseSeed();
        currentSeed = newSeed;
        initializeVisualizers();
        // Invalidate cache to force fresh render
        mapRenderer.getNoiseRenderer().invalidateCache();
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
     * Note: Visualization regeneration is NOT done here - it's triggered explicitly
     * when the user clicks "Simulate Seed" button.
     */
    public void render(int windowWidth, int windowHeight) {
        // Get current visualizer if visualization is active
        NoiseVisualizer currentVisualizer = null;
        if (stateManager.isVisualizationActive()) {
            currentVisualizer = visualizers.get(stateManager.getSelectedVisualizationMode());
        }

        // Render all components
        sidebarRenderer.render(windowWidth, windowHeight);
        mapRenderer.render(windowWidth, windowHeight, currentVisualizer, currentSeed);
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
