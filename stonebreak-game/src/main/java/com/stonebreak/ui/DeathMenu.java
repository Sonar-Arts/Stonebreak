package com.stonebreak.ui;

import com.stonebreak.rendering.UI.UIRenderer;

/**
 * Represents the death menu that appears when the player dies.
 * Displays a "You Died!" message and a respawn button.
 */
public class DeathMenu {

    // Menu state
    private boolean visible = false;
    private boolean respawnButtonHovered = false;

    /**
     * Creates a new death menu.
     */
    public DeathMenu() {
        // No OpenGL resources needed
    }

    /**
     * Renders the death menu using UIRenderer.
     */
    public void render(UIRenderer uiRenderer, int windowWidth, int windowHeight) {
        if (!visible) {
            return;
        }

        uiRenderer.renderDeathMenu(windowWidth, windowHeight, respawnButtonHovered);
    }

    /**
     * Checks if the menu is currently visible.
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Sets the visibility of the menu.
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /**
     * Checks if the respawn button was clicked.
     */
    public boolean isRespawnButtonClicked(float mouseX, float mouseY, UIRenderer uiRenderer, int windowWidth, int windowHeight) {
        return visible && uiRenderer.isDeathRespawnClicked(mouseX, mouseY, windowWidth, windowHeight);
    }

    /**
     * Updates hover state for the respawn button.
     */
    public void updateHover(float mouseX, float mouseY, UIRenderer uiRenderer, int windowWidth, int windowHeight) {
        if (!visible) {
            respawnButtonHovered = false;
            return;
        }

        respawnButtonHovered = uiRenderer.isDeathRespawnClicked(mouseX, mouseY, windowWidth, windowHeight);
    }

    /**
     * Cleanup resources.
     */
    public void cleanup() {
        // No OpenGL resources to cleanup
    }
}
