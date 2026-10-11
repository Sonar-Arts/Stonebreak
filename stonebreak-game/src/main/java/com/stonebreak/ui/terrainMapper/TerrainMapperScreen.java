package com.stonebreak.ui.terrainMapper;

import com.stonebreak.ui.terrainMapper.handlers.TerrainActionHandler;
import com.stonebreak.ui.terrainMapper.handlers.TerrainInputHandler;
import com.stonebreak.ui.terrainMapper.handlers.TerrainMouseHandler;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerKind;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;

/**
 * Facade for the terrain mapper screen. Builds the modular components
 * (state → handlers) and wires the button callbacks. The shipped document
 * {@value #DOCUMENT_ID} presents it and sends its pointer input to {@link #mouse()};
 * the keyboard stays here, routed by {@code MenuInputRouter} (#301).
 */
public final class TerrainMapperScreen {

    private final TerrainMapperStateManager state;
    private final TerrainActionHandler actionHandler;
    private final TerrainInputHandler inputHandler;
    private final TerrainMouseHandler mouseHandler;

    public TerrainMapperScreen() {
        this.state = new TerrainMapperStateManager();
        this.actionHandler = new TerrainActionHandler(state, new WorldDiscoveryManager());
        this.inputHandler = new TerrainInputHandler(state, actionHandler);
        this.mouseHandler = new TerrainMouseHandler(state);

        wireCallbacks();
    }

    private void wireCallbacks() {
        state.getBackButton().onClick(actionHandler::goBack);
        state.getCharacterButton().onClick(actionHandler::goToCharacterCreation);
        state.getSimulateSeedButton().onClick(actionHandler::simulateSeed);
        state.getCreateButton().onClick(actionHandler::createWorld);
        for (var button : state.getModeButtons()) {
            VisualizerKind tag = button.tag();
            button.onClick(() -> state.setActiveVisualizer(tag));
        }
        state.getSetSpawnButton().onClick(state::clearSpawnPoint);
        state.getCenterOnSpawnButton().onClick(actionHandler::centerOnSpawn);
    }

    // ─────────────────────────────────────────────── Keyboard (the document leaves it here)

    public void handleCharacterInput(char character) {
        inputHandler.handleCharacterInput(character);
    }

    public void handleKeyInput(int key, int action, int mods) {
        inputHandler.handleKeyInput(key, action, mods);
    }

    // ─────────────────────────────────────────────── Lifecycle

    public void dispose() {
        state.dispose();
    }

    public TerrainMapperStateManager getStateManager() { return state; }

    /** The pointer rules, addressed by target (the terrain mapper document's actions, #301). */
    public TerrainMouseHandler mouse() { return mouseHandler; }

    /** The shipped document: {@code ui/documents/terrain_mapper.sbui} (#301). */
    public static final String DOCUMENT_ID = "terrain_mapper";
}
