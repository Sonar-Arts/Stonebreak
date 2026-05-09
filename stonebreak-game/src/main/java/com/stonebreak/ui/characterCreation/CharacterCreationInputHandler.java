package com.stonebreak.ui.characterCreation;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

/**
 * Keyboard input for the character creation screen. Routes through the GLFW
 * key callback — no per-frame polling needed.
 */
public final class CharacterCreationInputHandler {

    private final CharacterCreationStateManager state;
    private final CharacterCreationActionHandler actions;

    public CharacterCreationInputHandler(CharacterCreationStateManager state,
                                        CharacterCreationActionHandler actions) {
        this.state   = state;
        this.actions = actions;
    }

    /** Kept for API parity with other screens; no per-frame key polling needed. */
    public void handleInput(long window) { /* no-op */ }

    public void handleCharacterInput(char c) { /* no text fields on this screen */ }

    public void handleKeyInput(int key, int action, int mods) {
        if (action != GLFW_PRESS) return;
        switch (key) {
            case GLFW_KEY_TAB    -> cycleTab();
            case GLFW_KEY_ESCAPE -> actions.goBackToWorldSelect();
            default              -> { /* ignore */ }
        }
    }

    private void cycleTab() {
        CharacterCreationTab[] tabs  = CharacterCreationTab.values();
        int current = state.getActiveTab().ordinal();
        int next    = (current + 1) % tabs.length;
        state.setActiveTab(tabs[next]);
    }
}
