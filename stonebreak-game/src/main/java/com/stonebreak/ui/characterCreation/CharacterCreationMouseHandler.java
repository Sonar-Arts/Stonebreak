package com.stonebreak.ui.characterCreation;

/**
 * The character creation screen's mouse wheel: scrolls the active tab's list offset in state (the
 * document's {@code .wheel} action, #301).
 */
public final class CharacterCreationMouseHandler {

    private static final float SCROLL_SPEED = 24f;

    private final CharacterCreationStateManager state;

    public CharacterCreationMouseHandler(CharacterCreationStateManager state) {
        this.state = state;
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
