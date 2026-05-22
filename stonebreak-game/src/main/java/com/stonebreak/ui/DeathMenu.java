package com.stonebreak.ui;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.deathMenu.SkijaDeathMenuRenderer;

/**
 * Represents the death menu that appears when the player dies.
 * Skija/MasonryUI-backed; owns its renderer and self-contained hit-tests so
 * input code does not need to reach back into the UIRenderer.
 */
public class DeathMenu {

    private static final float BASE_BUTTON_WIDTH  = SkijaDeathMenuRenderer.BUTTON_WIDTH;
    private static final float BASE_BUTTON_HEIGHT = SkijaDeathMenuRenderer.BUTTON_HEIGHT;

    private final SkijaDeathMenuRenderer skijaRenderer;

    private boolean visible = false;
    private boolean respawnButtonHovered = false;

    public DeathMenu(SkijaUIBackend skijaBackend) {
        this.skijaRenderer = new SkijaDeathMenuRenderer(skijaBackend);
    }

    /**
     * Renders the death menu using the Skija backend.
     */
    public void render(int windowWidth, int windowHeight) {
        if (!visible) return;
        skijaRenderer.render(windowWidth, windowHeight, respawnButtonHovered);
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
    public boolean isRespawnButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitRespawnButton(mouseX, mouseY, windowWidth, windowHeight);
    }

    /**
     * Updates hover state for the respawn button.
     */
    public void updateHover(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (!visible) {
            respawnButtonHovered = false;
            return;
        }
        respawnButtonHovered = hitRespawnButton(mouseX, mouseY, windowWidth, windowHeight);
    }

    /**
     * Cleanup resources.
     */
    public void cleanup() {
        if (skijaRenderer != null) skijaRenderer.dispose();
    }

    private static boolean hitRespawnButton(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        float buttonWidth  = BASE_BUTTON_WIDTH  * scale;
        float buttonHeight = BASE_BUTTON_HEIGHT * scale;
        float centerX = windowWidth  / 2.0f;
        float centerY = windowHeight / 2.0f;
        float x = centerX - buttonWidth / 2f;
        float y = centerY + 20f * scale;
        return mouseX >= x && mouseX <= x + buttonWidth
            && mouseY >= y && mouseY <= y + buttonHeight;
    }
}
