package com.stonebreak.ui.terrainmapper.handlers;

import com.stonebreak.ui.terrainmapper.managers.TerrainStateManager;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Handles keyboard input for the Terrain Mapper screen.
 * Manages text input, field switching, and keyboard shortcuts.
 */
public class TerrainInputHandler {

    private final TerrainStateManager stateManager;
    private final TerrainActionHandler actionHandler;

    public TerrainInputHandler(TerrainStateManager stateManager, TerrainActionHandler actionHandler) {
        this.stateManager = stateManager;
        this.actionHandler = actionHandler;
    }

    /**
     * Handles character input events.
     */
    public void handleCharacterInput(int codepoint) {
        // Route character input to the active text field
        if (stateManager.getActiveField() == TerrainStateManager.ActiveField.WORLD_NAME) {
            stateManager.getWorldNameField().handleCharacterInput((char) codepoint);
        } else if (stateManager.getActiveField() == TerrainStateManager.ActiveField.SEED) {
            stateManager.getSeedField().handleCharacterInput((char) codepoint);
        }
    }

    /**
     * Handles key press events.
     */
    public void handleKeyInput(int key, int action, int mods) {
        // Only handle press and repeat events
        if (action != GLFW_PRESS && action != GLFW_REPEAT) {
            return;
        }

        // Handle keyboard shortcuts
        switch (key) {
            case GLFW_KEY_ESCAPE:
                handleEscape();
                break;

            case GLFW_KEY_ENTER:
            case GLFW_KEY_KP_ENTER:
                handleEnter();
                break;

            case GLFW_KEY_TAB:
                handleTab();
                break;

            default:
                // Route key input to the active text field
                routeKeyToActiveField(key, action, mods);
                break;
        }
    }

    /**
     * Routes key input to the currently active text field.
     */
    private void routeKeyToActiveField(int key, int action, int mods) {
        if (stateManager.getActiveField() == TerrainStateManager.ActiveField.WORLD_NAME) {
            stateManager.getWorldNameField().handleKeyInput(key, action, mods);
        } else if (stateManager.getActiveField() == TerrainStateManager.ActiveField.SEED) {
            stateManager.getSeedField().handleKeyInput(key, action, mods);
        }
    }

    /**
     * Handles the Escape key press - returns to world select screen.
     */
    private void handleEscape() {
        actionHandler.goBack();
    }

    /**
     * Handles the Enter key press - creates the world.
     */
    private void handleEnter() {
        actionHandler.createWorld();
    }

    /**
     * Handles the Tab key press - switches between input fields.
     */
    private void handleTab() {
        stateManager.switchToNextField();
    }
}
