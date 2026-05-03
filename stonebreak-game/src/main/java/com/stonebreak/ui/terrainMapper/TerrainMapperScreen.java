package com.stonebreak.ui.terrainMapper;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.terrainMapper.handlers.TerrainActionHandler;
import com.stonebreak.ui.terrainMapper.handlers.TerrainInputHandler;
import com.stonebreak.ui.terrainMapper.handlers.TerrainMouseHandler;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.terrainMapper.renderers.SkijaTerrainMapperRenderer;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerKind;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;

/**
 * Facade for the terrain mapper screen. Builds the modular components
 * (state → handlers → renderer), wires the button callbacks, and exposes
 * the input/render surface that {@code Main} and {@code Game} consume.
 *
 * Mirrors the shape of {@code SettingsMenu} and {@code WorldSelectScreen}
 * so every MasonryUI-backed screen in the game follows the same lifecycle.
 */
public final class TerrainMapperScreen {

    private final TerrainMapperStateManager state;
    private final TerrainActionHandler actionHandler;
    private final TerrainInputHandler inputHandler;
    private final TerrainMouseHandler mouseHandler;
    private final MasonryUI ui;
    private final SkijaTerrainMapperRenderer renderer;

    public TerrainMapperScreen(SkijaUIBackend skijaBackend) {
        this.state = new TerrainMapperStateManager();
        this.actionHandler = new TerrainActionHandler(state, new WorldDiscoveryManager());
        this.inputHandler = new TerrainInputHandler(state, actionHandler);
        this.mouseHandler = new TerrainMouseHandler(state, actionHandler);
        this.ui = new MasonryUI(skijaBackend);
        this.renderer = new SkijaTerrainMapperRenderer(ui, state);

        wireCallbacks();
    }

    private void wireCallbacks() {
        state.getBackButton().onClick(actionHandler::goBack);
        state.getSimulateSeedButton().onClick(actionHandler::simulateSeed);
        state.getCreateButton().onClick(actionHandler::createWorld);
        for (var button : state.getModeButtons()) {
            VisualizerKind tag = button.tag();
            button.onClick(() -> state.setActiveVisualizer(tag));
        }
        state.getSetSpawnButton().onClick(state::clearSpawnPoint);
        state.getCenterOnSpawnButton().onClick(actionHandler::centerOnSpawn);
    }

    // ─────────────────────────────────────────────── Input

    public void handleInput(long window) {
        inputHandler.handleInput(window);
    }

    public void handleCharacterInput(char character) {
        inputHandler.handleCharacterInput(character);
    }

    public void handleKeyInput(int key, int action, int mods) {
        inputHandler.handleKeyInput(key, action, mods);
    }

    public void handleMouseMove(double x, double y, int windowWidth, int windowHeight) {
        mouseHandler.handleMouseMove(x, y, windowWidth, windowHeight);
    }

    public void handleMouseClick(double x, double y, int windowWidth, int windowHeight,
                                 int button, int action) {
        mouseHandler.handleMouseClick(x, y, windowWidth, windowHeight, button, action);
    }

    public void handleMouseWheel(double x, double y, double delta) {
        mouseHandler.handleMouseWheel(x, y, delta);
    }

    // ─────────────────────────────────────────────── Lifecycle

    public void render(int windowWidth, int windowHeight) {
        renderer.render(windowWidth, windowHeight);
    }

    public void dispose() {
        renderer.dispose();
        ui.dispose();
    }

    public TerrainMapperStateManager getStateManager() { return state; }
}
