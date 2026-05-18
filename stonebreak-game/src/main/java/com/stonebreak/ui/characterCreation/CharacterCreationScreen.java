package com.stonebreak.ui.characterCreation;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.characterCreation.renderers.SkijaCharacterCreationRenderer;

/**
 * Facade for the character creation screen. Builds the modular components
 * (state → handlers → renderer), wires the button callbacks, and exposes
 * the input/render surface that Main and Game consume.
 *
 * Mirrors the shape of TerrainMapperScreen so every MasonryUI-backed screen
 * in the game follows the same lifecycle.
 */
public final class CharacterCreationScreen {

    private final CharacterCreationStateManager state;
    private final CharacterCreationActionHandler actionHandler;
    private final CharacterCreationInputHandler  inputHandler;
    private final CharacterCreationMouseHandler  mouseHandler;
    private final MasonryUI ui;
    private final SkijaCharacterCreationRenderer renderer;
    private final CharacterCreationLayout layout;

    public CharacterCreationScreen(SkijaUIBackend skijaBackend) {
        this.layout       = new CharacterCreationLayout();
        this.state        = new CharacterCreationStateManager();
        this.actionHandler= new CharacterCreationActionHandler(state);
        this.inputHandler = new CharacterCreationInputHandler(state, actionHandler);
        this.mouseHandler = new CharacterCreationMouseHandler(state, actionHandler, layout);
        this.ui           = new MasonryUI(skijaBackend);
        this.renderer     = new SkijaCharacterCreationRenderer(ui, state, layout);

        mouseHandler.setRenderer(renderer);
        wireCallbacks();
    }

    private void wireCallbacks() {
        state.getBackToWorldSelectButton().onClick(actionHandler::goBackToWorldSelect);
        state.getTerrainMapperButton().onClick(actionHandler::goToTerrainMapper);
    }

    // ─────────────────────────────────────────────── Input

    public void handleInput(long window) {
        inputHandler.handleInput(window);
    }

    public void handleCharacterInput(char c) {
        inputHandler.handleCharacterInput(c);
    }

    public void handleKeyInput(int key, int action, int mods) {
        inputHandler.handleKeyInput(key, action, mods);
    }

    public void handleMouseMove(double x, double y, int w, int h) {
        mouseHandler.handleMouseMove(x, y, w, h);
    }

    public void handleMouseClick(double x, double y, int w, int h, int btn, int action) {
        mouseHandler.handleMouseClick(x, y, w, h, btn, action);
    }

    public void handleMouseWheel(double x, double y, double delta) {
        mouseHandler.handleMouseWheel(x, y, delta);
    }

    // ─────────────────────────────────────────────── Lifecycle

    public void render(int windowWidth, int windowHeight) {
        renderer.render(windowWidth, windowHeight);
    }

    public void reset() {
        state.reset();
    }

    public CharacterStats getCharacterStats() {
        return state.getCharacterStats();
    }

    public void dispose() {
        renderer.dispose();
        ui.dispose();
    }
}
