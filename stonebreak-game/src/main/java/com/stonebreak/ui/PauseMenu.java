package com.stonebreak.ui;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.pauseMenu.SkijaPauseMenuRenderer;

/**
 * Represents the pause menu that appears when the user presses the escape key.
 * Skija/MasonryUI-backed; owns its renderer and self-contained hit-tests so
 * input code does not need to reach back into the UIRenderer.
 */
public class PauseMenu {

    private static final float BASE_BUTTON_WIDTH  = SkijaPauseMenuRenderer.BUTTON_WIDTH;
    private static final float BASE_BUTTON_HEIGHT = SkijaPauseMenuRenderer.BUTTON_HEIGHT;

    private final SkijaPauseMenuRenderer skijaRenderer;

    private boolean visible = false;
    private boolean quitButtonHovered = false;
    private boolean settingsButtonHovered = false;
    private boolean statisticsButtonHovered = false;
    private boolean glossaryButtonHovered = false;

    public PauseMenu(SkijaUIBackend skijaBackend) {
        this.skijaRenderer = new SkijaPauseMenuRenderer(skijaBackend);
    }

    public void render(int windowWidth, int windowHeight) {
        if (!visible) return;
        skijaRenderer.render(windowWidth, windowHeight, statisticsButtonHovered, glossaryButtonHovered, settingsButtonHovered, quitButtonHovered);
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
        return visible && hitButton(mouseX, mouseY, windowWidth, windowHeight, -140f);
    }

    public boolean isStatisticsButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitButton(mouseX, mouseY, windowWidth, windowHeight, -70f);
    }

    public boolean isGlossaryButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitButton(mouseX, mouseY, windowWidth, windowHeight, 0f);
    }

    public boolean isSettingsButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitButton(mouseX, mouseY, windowWidth, windowHeight, 70f);
    }

    public boolean isQuitButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitButton(mouseX, mouseY, windowWidth, windowHeight, 140f);
    }

    public void updateHover(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (!visible) {
            quitButtonHovered = false;
            settingsButtonHovered = false;
            statisticsButtonHovered = false;
            glossaryButtonHovered = false;
            return;
        }
        statisticsButtonHovered = isStatisticsButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
        glossaryButtonHovered   = isGlossaryButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
        settingsButtonHovered   = isSettingsButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
        quitButtonHovered       = isQuitButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
    }

    public void cleanup() {
        if (skijaRenderer != null) skijaRenderer.dispose();
    }

    private static boolean hitButton(float mouseX, float mouseY, int windowWidth, int windowHeight, float buttonTopOffset) {
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        float buttonWidth  = BASE_BUTTON_WIDTH  * scale;
        float buttonHeight = BASE_BUTTON_HEIGHT * scale;
        float centerX = windowWidth  / 2.0f;
        float centerY = windowHeight / 2.0f;
        float x = centerX - buttonWidth / 2f;
        float y = centerY + buttonTopOffset * scale;
        return mouseX >= x && mouseX <= x + buttonWidth
            && mouseY >= y && mouseY <= y + buttonHeight;
    }
}
