package com.stonebreak.ui.characterCreation;

import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.openmason.engine.ui.masonry.MasonryUI;
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

    /** Update navigation button labels for JOIN mode. */
    public void updateLabelsForMode() {
        if (MultiplayerSession.isInMode(MultiplayerSession.Mode.JOIN)) {
            state.getBackToWorldSelectButton().text("Main Menu");
            state.getTerrainMapperButton().text("Enter World");
        } else {
            state.getBackToWorldSelectButton().text("World Select");
            state.getTerrainMapperButton().text("Terrain Mapper");
        }
    }

    public void render(int windowWidth, int windowHeight) {
        renderer.render(windowWidth, windowHeight);
    }

    public void reset() {
        state.reset();
    }

    public CharacterStats getCharacterStats() {
        return state.getCharacterStats();
    }

    // ─────────────────────────────────────────────── The document's seams (#301)

    /** The shipped document: {@code ui/documents/character_creation.sbui}. */
    public static final String DOCUMENT_ID = "character_creation";

    public CharacterCreationStateManager state() { return state; }

    public CharacterCreationActionHandler actions() { return actionHandler; }

    public CharacterCreationMouseHandler mouse() { return mouseHandler; }

    public CharacterCreationLayout layout() { return layout; }

    /** The tab strip in a {@code w}x{@code h} window, measured with this screen's fonts. */
    public com.stonebreak.ui.characterCreation.renderers.SkijaCharacterCreationRenderer.TabMetric[] tabMetrics(
            int w, int h) {
        return com.stonebreak.ui.characterCreation.renderers.SkijaCharacterCreationRenderer.tabMetrics(ui,
                layout.tabBar(layout.rightPanel(w, h)));
    }

    /** Clamps the three list scrolls as the legacy renderers do on every frame. */
    public void clampScrolls() {
        int i = state.getSelectedClassIndex();
        var classes = com.stonebreak.rpg.classes.ClassRegistry.ALL;
        // with no class the ability list is not drawn, so its scroll is left as it is
        if (i >= 0 && i < classes.size()) {
            state.setClassScroll(clamp(state.getClassScroll(), classes.get(i).abilities().size() * 64f - 300f));
        }
        state.setSkillScroll(clamp(state.getSkillScroll(),
                com.stonebreak.rpg.skills.SkillRegistry.ALL.size() * (40f + 2f) - 320f));
        state.setFeatScroll(clamp(state.getFeatScroll(),
                com.stonebreak.rpg.feats.FeatRegistry.ALL.size() * (54f + 4f) - 320f));
    }

    private static float clamp(float v, float overflow) {
        return Math.clamp(v, 0f, Math.max(0f, overflow));
    }

    public void dispose() {
        renderer.dispose();
        ui.dispose();
    }

}
