package com.stonebreak.ui.terrainMapper.handlers;

import com.stonebreak.rendering.UI.masonryUI.MClipboard;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_V;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

/**
 * Keyboard and character input for the terrain mapper. All per-frame key
 * polling is gone — we route through the GLFW key callback so Tab / Enter /
 * Escape fire exactly once per press. Character input is straight passthrough
 * to the active text field via {@link TerrainMapperStateManager}.
 */
public final class TerrainInputHandler {

    private final TerrainMapperStateManager state;
    private final TerrainActionHandler actions;

    public TerrainInputHandler(TerrainMapperStateManager state, TerrainActionHandler actions) {
        this.state = state;
        this.actions = actions;
    }

    /** Kept for parity with other screens; no per-frame keyboard polling needed. */
    public void handleInput(long window) { /* no-op */ }

    public void handleCharacterInput(char character) {
        state.appendToActiveField(character);
    }

    public void handleKeyInput(int key, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return;
        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;
        switch (key) {
            case GLFW_KEY_BACKSPACE -> state.backspaceActiveField();
            case GLFW_KEY_TAB       -> state.toggleActiveField();
            case GLFW_KEY_ENTER     -> actions.createWorld();
            case GLFW_KEY_ESCAPE    -> actions.goBack();
            // GLFW emits no character event for control combos, so paste lands here.
            case GLFW_KEY_V         -> { if (ctrl) state.appendToActiveField(MClipboard.read()); }
            default                 -> { /* ignore */ }
        }
    }
}
