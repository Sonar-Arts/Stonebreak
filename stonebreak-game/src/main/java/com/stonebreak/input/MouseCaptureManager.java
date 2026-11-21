package com.stonebreak.input;

import com.openmason.engine.platform.input.PointerCapture;
import com.openmason.engine.platform.window.Window;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.player.Camera;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;

/**
 * Stonebreak's mouse-capture rules: which game states, menus and documents keep the cursor free,
 * and when look input reaches the {@link Camera}. The mechanics (hiding/locking the cursor with raw
 * motion, focus requests, temporary release on focus loss, jump-free deltas x sensitivity) are the
 * engine's {@link PointerCapture} (#96).
 */
public class MouseCaptureManager {

    private final PointerCapture pointer;

    // Camera reference for mouse look
    private Camera camera;

    public MouseCaptureManager(Window window) {
        this.pointer = new PointerCapture(window, 0.1f);
    }

    /**
     * Sets the camera reference for mouse look functionality.
     */
    public void setCamera(Camera camera) {
        this.camera = camera;
    }

    /**
     * Updates mouse capture state based on current game state.
     * This should be called whenever the game state changes.
     */
    public void updateCaptureState() {
        GameState currentState = Game.getInstance().getState();
        pointer.setCaptured(shouldCaptureMouseForState(currentState));
    }

    /**
     * Determines if mouse should be captured for the given game state.
     */
    private boolean shouldCaptureMouseForState(GameState state) {
        Game game = Game.getInstance();

        // Never capture mouse if death menu is visible
        com.stonebreak.ui.DeathMenu deathMenu = game.getDeathMenu();
        if (deathMenu != null && deathMenu.isVisible()) {
            return false;
        }
        // A UI document screen that needs the pointer (a migrated menu or panel) keeps it free.
        if (com.stonebreak.ui.runtime.screens.DocumentScreenHost.ifCreated()
                .map(com.stonebreak.ui.runtime.screens.DocumentScreenHost::needsPointer).orElse(false)) {
            return false;
        }

        switch (state) {
            case PLAYING -> {
                // Always capture mouse in PLAYING state unless chat is open
                ChatSystem chatSystem = game.getChatSystem();
                if (chatSystem != null && chatSystem.isOpen()) {
                    return false;
                }
                return true; // Normal gameplay
            }
            case WORKBENCH_UI, RECIPE_BOOK_UI, INVENTORY_UI, PAUSED, CHARACTER_SHEET_UI, FURNACE_UI, CHEST_UI -> {
                return false;
            }
            case MAIN_MENU, LOADING, SETTINGS -> {
                return false;
            }
            case FOCUS_BATTLE -> {
                // Menu-driven combat: the cursor stays visible for the battle HUD, and the scripted
                // camera must never receive mouse look.
                return false;
            }
            default -> {
                return false;
            }
        }
    }
    
    /**
     * Processes mouse movement for camera look.
     * This should be called from the GLFW cursor position callback.
     */
    public void processMouseMovement(double xpos, double ypos) {
        // Look only while captured, with a camera, and with no chat open; otherwise only the anchor
        // follows the cursor, so nothing jumps when look resumes.
        Camera target = camera;
        ChatSystem chatSystem = Game.getInstance().getChatSystem();
        boolean chatOpen = chatSystem != null && chatSystem.isOpen();
        pointer.onCursor(xpos, ypos, target != null && !chatOpen,
                target == null ? null : target::processMouseMovement);
    }

    /**
     * Forces mouse capture state update.
     * Useful when game state changes require immediate mouse capture update.
     */
    public void forceUpdate() {
        updateCaptureState();
    }

    /**
     * Checks if mouse is currently captured.
     */
    public boolean isMouseCaptured() {
        return pointer.isCaptured();
    }

    /**
     * Sets mouse sensitivity for camera movement.
     */
    public void setMouseSensitivity(float sensitivity) {
        pointer.setSensitivity(Math.max(0.01f, Math.min(1.0f, sensitivity)));
    }

    /**
     * Gets current mouse sensitivity.
     */
    public float getMouseSensitivity() {
        return pointer.sensitivity();
    }

    /**
     * Temporarily releases mouse (useful for ALT+TAB scenarios).
     */
    public void temporaryRelease() {
        pointer.temporaryRelease();
    }

    /**
     * Restores mouse capture after temporary release.
     */
    public void restoreCapture() {
        pointer.restoreCapture();
    }

    /**
     * Cleanup resources.
     */
    public void cleanup() {
        if (pointer.isCaptured()) {
            pointer.release();
        }
    }
}
