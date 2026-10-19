package com.stonebreak.ui.characterCreation;

import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.openmason.engine.ui.masonry.MasonryUI;

/**
 * Facade for the character creation screen. Builds the modular components
 * (state → handlers) and wires the button callbacks. The shipped document
 * {@value #DOCUMENT_ID} presents it and reaches it through the seams below (#301).
 */
public final class CharacterCreationScreen {

    private final CharacterCreationStateManager state;
    private final CharacterCreationActionHandler actionHandler;
    private final CharacterCreationMouseHandler  mouseHandler;
    /** Measures the tab labels with the game font. */
    private final MasonryUI ui;
    private final CharacterCreationLayout layout;

    public CharacterCreationScreen(SkijaUIBackend skijaBackend) {
        this.layout       = new CharacterCreationLayout();
        this.state        = new CharacterCreationStateManager();
        this.actionHandler= new CharacterCreationActionHandler(state);
        this.mouseHandler = new CharacterCreationMouseHandler(state);
        this.ui           = new MasonryUI(skijaBackend);

        wireCallbacks();
    }

    private void wireCallbacks() {
        state.getBackToWorldSelectButton().onClick(actionHandler::goBackToWorldSelect);
        state.getTerrainMapperButton().onClick(actionHandler::goToTerrainMapper);
        state.getUndoButton().onClick(() -> state.getCharacterStats().undoLastAllocation());
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
    public CharacterCreationLayout.TabMetric[] tabMetrics(int w, int h) {
        return CharacterCreationLayout.tabMetrics(ui, layout.tabBar(layout.rightPanel(w, h)));
    }

    /** Clamps the three list scrolls to their lists' overflow (once a frame, by the UI host). */
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
        ui.dispose();
    }

}
