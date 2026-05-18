package com.stonebreak.ui.characterCreation;

import com.stonebreak.ui.characterCreation.renderers.SkijaCharacterCreationRenderer;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * Mouse dispatch for the character creation screen. Delegates content clicks
 * to the renderer (which owns the per-frame button bounds), scroll events to
 * the active tab's offset in state, and nav button clicks to the action handler.
 */
public final class CharacterCreationMouseHandler {

    private static final float SCROLL_SPEED = 24f;

    private final CharacterCreationStateManager state;
    private final CharacterCreationActionHandler actions;
    private final CharacterCreationLayout layout;
    private SkijaCharacterCreationRenderer renderer;

    public CharacterCreationMouseHandler(CharacterCreationStateManager state,
                                        CharacterCreationActionHandler actions,
                                        CharacterCreationLayout layout) {
        this.state   = state;
        this.actions = actions;
        this.layout  = layout;
    }

    /** Called by the Screen facade after the renderer is constructed. */
    public void setRenderer(SkijaCharacterCreationRenderer renderer) {
        this.renderer = renderer;
    }

    public void handleMouseMove(double x, double y, int w, int h) {
        float mx = (float) x;
        float my = (float) y;
        state.setMousePosition(mx, my);

        state.getBackToWorldSelectButton().updateHover(mx, my);
        state.getTerrainMapperButton().updateHover(mx, my);
    }

    public void handleMouseClick(double x, double y, int w, int h, int button, int action) {
        if (button != GLFW_MOUSE_BUTTON_LEFT) return;
        if (action == GLFW_RELEASE) return;
        if (action != GLFW_PRESS) return;

        float mx = (float) x;
        float my = (float) y;

        if (state.getBackToWorldSelectButton().handleClick(mx, my)) return;
        if (state.getTerrainMapperButton().handleClick(mx, my))     return;

        if (renderer != null) {
            renderer.handleClick(mx, my, actions);
        }
    }

    public void handleMouseWheel(double x, double y, double delta) {
        if (delta == 0.0) return;
        float amount = (float) delta * SCROLL_SPEED;

        switch (state.getActiveTab()) {
            case CLASS_ABILITIES -> {
                float updated = state.getClassScroll() - amount;
                state.setClassScroll(Math.max(0f, updated));
            }
            case SKILLS -> {
                float updated = state.getSkillScroll() - amount;
                state.setSkillScroll(Math.max(0f, updated));
            }
            case FEATS -> {
                float updated = state.getFeatScroll() - amount;
                state.setFeatScroll(Math.max(0f, updated));
            }
            default -> { /* BACKGROUND and ABILITY_SCORE do not scroll */ }
        }
    }
}
