package com.stonebreak.ui.worldSelect.handlers;

import com.stonebreak.ui.worldSelect.managers.WorldStateManager;
import static org.lwjgl.glfw.GLFW.*;

/**
 * Handles keyboard input for the WorldSelectScreen.
 * Manages navigation, text input, and dialog interactions.
 */
public class WorldInputHandler {

    private final WorldStateManager stateManager;
    private final WorldActionHandler actionHandler;

    // Key press tracking to prevent repeat actions
    private boolean upKeyPressed = false;
    private boolean downKeyPressed = false;
    private boolean enterKeyPressed = false;
    private boolean escapeKeyPressed = false;
    private boolean tabKeyPressed = false;
    private boolean backspaceKeyPressed = false;

    // Text input mode (for create dialog)
    private boolean nameInputMode = true; // true = name input, false = seed input

    public WorldInputHandler(WorldStateManager stateManager, WorldActionHandler actionHandler) {
        this.stateManager = stateManager;
        this.actionHandler = actionHandler;
    }

    /**
     * Handles all keyboard input for the world select screen.
     */
    public void handleInput(long window) {
        if (stateManager.isShowCreateDialog()) {
            handleCreateDialogInput(window);
        } else {
            handleWorldListInput(window);
        }
    }

    /**
     * Handles keyboard input for the world list navigation.
     */
    private void handleWorldListInput(long window) {
        // Navigation keys
        handleNavigationKeys(window);

        // Action keys
        handleActionKeys(window);

        // Escape key
        handleEscapeKey(window);
    }

    /**
     * Handles keyboard input for the create world dialog.
     */
    private void handleCreateDialogInput(long window) {
        // Text input navigation (Tab to switch fields)
        handleTabKey(window);

        // Text editing keys
        handleBackspaceKey(window);

        // Action keys
        handleDialogActionKeys(window);

        // Escape key (close dialog)
        handleEscapeKey(window);
    }

    /**
     * Handles up/down navigation keys.
     */
    private void handleNavigationKeys(long window) {
        // Up arrow or W key
        boolean upPressed = isKeyPressed(window, GLFW_KEY_UP) || isKeyPressed(window, GLFW_KEY_W);
        if (upPressed && !upKeyPressed) {
            stateManager.moveSelectionUp();
        }
        upKeyPressed = upPressed;

        // Down arrow or S key
        boolean downPressed = isKeyPressed(window, GLFW_KEY_DOWN) || isKeyPressed(window, GLFW_KEY_S);
        if (downPressed && !downKeyPressed) {
            stateManager.moveSelectionDown();
        }
        downKeyPressed = downPressed;
    }

    /**
     * Handles action keys (Enter, Space).
     */
    private void handleActionKeys(long window) {
        // Enter key to load world or open create dialog
        boolean enterPressed = isKeyPressed(window, GLFW_KEY_ENTER) || isKeyPressed(window, GLFW_KEY_SPACE);
        if (enterPressed && !enterKeyPressed) {
            if (stateManager.hasWorlds()) {
                actionHandler.loadSelectedWorld();
            } else {
                actionHandler.openCreateWorldDialog();
            }
        }
        enterKeyPressed = enterPressed;

        // N key to create new world
        if (isKeyPressed(window, GLFW_KEY_N)) {
            actionHandler.openCreateWorldDialog();
        }
    }

    /**
     * Handles dialog action keys.
     */
    private void handleDialogActionKeys(long window) {
        // Enter key to create world
        boolean enterPressed = isKeyPressed(window, GLFW_KEY_ENTER);
        if (enterPressed && !enterKeyPressed) {
            actionHandler.createWorldFromDialog();
        }
        enterKeyPressed = enterPressed;
    }

    /**
     * Handles escape key.
     */
    private void handleEscapeKey(long window) {
        boolean escPressed = isKeyPressed(window, GLFW_KEY_ESCAPE);
        if (escPressed && !escapeKeyPressed) {
            if (stateManager.isShowCreateDialog()) {
                actionHandler.closeCreateWorldDialog();
            } else {
                actionHandler.returnToMainMenu();
            }
        }
        escapeKeyPressed = escPressed;
    }

    /**
     * Handles Tab key for switching input fields in create dialog.
     */
    private void handleTabKey(long window) {
        boolean tabPressed = isKeyPressed(window, GLFW_KEY_TAB);
        if (tabPressed && !tabKeyPressed) {
            nameInputMode = !nameInputMode;
        }
        tabKeyPressed = tabPressed;
    }

    /**
     * Handles backspace key for text editing.
     */
    private void handleBackspaceKey(long window) {
        boolean backspacePressed = isKeyPressed(window, GLFW_KEY_BACKSPACE);
        if (backspacePressed && !backspaceKeyPressed) {
            if (nameInputMode) {
                stateManager.removeLastCharacterFromWorldName();
            } else {
                stateManager.removeLastCharacterFromWorldSeed();
            }
        }
        backspaceKeyPressed = backspacePressed;
    }

    /**
     * Handles character input for text fields.
     */
    public void handleCharacterInput(char character) {
        if (!stateManager.isShowCreateDialog()) {
            return;
        }

        if (nameInputMode) {
            stateManager.appendToWorldName(character);
        } else {
            stateManager.appendToWorldSeed(character);
        }
    }

    /**
     * Handles key input events (for special key handling).
     */
    public void handleKeyInput(int key, int action, int mods) {
        // Handle special keys that might not come through character input
        if (action == GLFW_PRESS || action == GLFW_REPEAT) {
            switch (key) {
                case GLFW_KEY_DELETE:
                    // Delete key - same as backspace for now
                    if (stateManager.isShowCreateDialog()) {
                        if (nameInputMode) {
                            stateManager.removeLastCharacterFromWorldName();
                        } else {
                            stateManager.removeLastCharacterFromWorldSeed();
                        }
                    }
                    break;
            }
        }
    }

    // ===== UTILITY METHODS =====

    /**
     * Checks if a key is currently pressed.
     */
    private boolean isKeyPressed(long window, int key) {
        return glfwGetKey(window, key) == GLFW_PRESS;
    }

    /**
     * Gets the current text input mode for the create dialog.
     */
    public boolean isNameInputMode() {
        return nameInputMode;
    }

    /**
     * Sets the text input mode for the create dialog.
     */
    public void setNameInputMode(boolean nameInputMode) {
        this.nameInputMode = nameInputMode;
    }

    /**
     * Resets input state (useful when switching screens).
     */
    public void resetInputState() {
        upKeyPressed = false;
        downKeyPressed = false;
        enterKeyPressed = false;
        escapeKeyPressed = false;
        tabKeyPressed = false;
        backspaceKeyPressed = false;
        nameInputMode = true;
    }
}