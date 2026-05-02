package com.stonebreak.ui;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.pauseMenu.SkijaPauseMenuRenderer;

/**
 * Represents the pause menu that appears when the user presses the escape key.
 * Skija/MasonryUI-backed; owns its renderer and self-contained hit-tests so
 * input code does not need to reach back into the UIRenderer.
 */
public class PauseMenu {

    private static final float BUTTON_WIDTH  = SkijaPauseMenuRenderer.BUTTON_WIDTH;
    private static final float BUTTON_HEIGHT = SkijaPauseMenuRenderer.BUTTON_HEIGHT;

    private final SkijaPauseMenuRenderer skijaRenderer;

    private boolean visible = false;
    private boolean quitButtonHovered = false;
    private boolean settingsButtonHovered = false;

    public PauseMenu(SkijaUIBackend skijaBackend) {
        this.skijaRenderer = new SkijaPauseMenuRenderer(skijaBackend);
    }

    public void render(int windowWidth, int windowHeight) {
        if (!visible) return;
        skijaRenderer.render(windowWidth, windowHeight, settingsButtonHovered, quitButtonHovered);
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void toggleVisibility() {
        this.visible = !this.visible;
    }

    public boolean isResumeButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitButton(mouseX, mouseY, windowWidth, windowHeight, -60f);
    }

    public boolean isSettingsButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitButton(mouseX, mouseY, windowWidth, windowHeight, 10f);
    }

    public boolean isQuitButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitButton(mouseX, mouseY, windowWidth, windowHeight, 80f);
    }

    public void updateHover(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (!visible) {
            quitButtonHovered = false;
            settingsButtonHovered = false;
            return;
        }
        settingsButtonHovered = isSettingsButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
        quitButtonHovered     = isQuitButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
    }

    public void cleanup() {
        if (skijaRenderer != null) skijaRenderer.dispose();
    }

    private static boolean hitButton(float mouseX, float mouseY, int windowWidth, int windowHeight, float buttonTopOffset) {
        float centerX = windowWidth  / 2.0f;
        float centerY = windowHeight / 2.0f;
        float x = centerX - BUTTON_WIDTH / 2f;
        float y = centerY + buttonTopOffset;
        return mouseX >= x && mouseX <= x + BUTTON_WIDTH
            && mouseY >= y && mouseY <= y + BUTTON_HEIGHT;
    }
}
