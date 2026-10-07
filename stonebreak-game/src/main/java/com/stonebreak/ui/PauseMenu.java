package com.stonebreak.ui;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.pauseMenu.SkijaPauseMenuRenderer;

/**
 * Represents the pause menu that appears when the user presses the escape key.
 * Skija/MasonryUI-backed; owns its renderer and self-contained hit-tests so
 * input code does not need to reach back into the UIRenderer.
 *
 * <p>Layout is slot-based ({@link SkijaPauseMenuRenderer#buttonOffset}): the "Resync World"
 * button appears only in an online session (host/join), shifting the column to six buttons.
 */
public class PauseMenu {

    private static final float BASE_BUTTON_WIDTH  = SkijaPauseMenuRenderer.BUTTON_WIDTH;
    private static final float BASE_BUTTON_HEIGHT = SkijaPauseMenuRenderer.BUTTON_HEIGHT;

    /**
     * Another way to show the menu while the lifecycle stays here (#297: the shipped UI document,
     * {@code ui.pauseMenu.PauseDocument}). Told when the menu shows and hides; {@link #paint}
     * returning false falls back to the legacy renderer for that call.
     */
    public interface Presentation extends com.stonebreak.ui.runtime.screens.ScreenPresentation {
    }

    private final SkijaPauseMenuRenderer skijaRenderer;
    private Presentation presentation;

    private boolean visible = false;
    private boolean resumeButtonHovered = false;
    private boolean quitButtonHovered = false;
    private boolean settingsButtonHovered = false;
    private boolean statisticsButtonHovered = false;
    private boolean glossaryButtonHovered = false;
    private boolean resyncButtonHovered = false;

    public PauseMenu(SkijaUIBackend skijaBackend) {
        this.skijaRenderer = new SkijaPauseMenuRenderer(skijaBackend);
    }

    /** Installs (or, with null, removes) the alternative presentation; the legacy one is the default. */
    public void setPresentation(Presentation presentation) {
        if (this.presentation != null && visible) {
            this.presentation.hidden();
        }
        this.presentation = presentation;
        if (presentation != null && visible) {
            presentation.shown();
        }
    }

    public void render(int windowWidth, int windowHeight) {
        if (!visible) return;
        if (presentation != null && presentation.paint(windowWidth, windowHeight)) {
            return;
        }
        skijaRenderer.render(windowWidth, windowHeight, resumeButtonHovered, statisticsButtonHovered, glossaryButtonHovered,
                settingsButtonHovered, isResyncButtonVisible(), resyncButtonHovered, quitButtonHovered);
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        boolean changed = this.visible != visible;
        this.visible = visible;
        if (changed && presentation != null) {
            if (visible) {
                presentation.shown();
            } else {
                presentation.hidden();
            }
        }
    }

    public void toggleVisibility() {
        setVisible(!this.visible);
    }

    /**
     * The resync button only exists when a real network session is live (host or join), as
     * {@link UiOnlineState} reports it: overridable for fixtures and dev runs (#296).
     */
    public static boolean isResyncButtonVisible() {
        return UiOnlineState.isOnline();
    }

    private static int buttonCount() {
        return isResyncButtonVisible() ? 6 : 5;
    }

    public boolean isResumeButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitSlot(mouseX, mouseY, windowWidth, windowHeight, 0);
    }

    public boolean isStatisticsButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitSlot(mouseX, mouseY, windowWidth, windowHeight, 1);
    }

    public boolean isGlossaryButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitSlot(mouseX, mouseY, windowWidth, windowHeight, 2);
    }

    public boolean isSettingsButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitSlot(mouseX, mouseY, windowWidth, windowHeight, 3);
    }

    public boolean isResyncButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && isResyncButtonVisible()
            && hitSlot(mouseX, mouseY, windowWidth, windowHeight, 4);
    }

    public boolean isQuitButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitSlot(mouseX, mouseY, windowWidth, windowHeight, buttonCount() - 1);
    }

    public void updateHover(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (!visible) {
            resumeButtonHovered = false;
            quitButtonHovered = false;
            settingsButtonHovered = false;
            statisticsButtonHovered = false;
            glossaryButtonHovered = false;
            resyncButtonHovered = false;
            return;
        }
        resumeButtonHovered     = isResumeButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
        statisticsButtonHovered = isStatisticsButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
        glossaryButtonHovered   = isGlossaryButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
        settingsButtonHovered   = isSettingsButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
        resyncButtonHovered     = isResyncButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
        quitButtonHovered       = isQuitButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
    }

    public void cleanup() {
        if (skijaRenderer != null) skijaRenderer.dispose();
    }

    private static boolean hitSlot(float mouseX, float mouseY, int windowWidth, int windowHeight, int slot) {
        float offset = SkijaPauseMenuRenderer.buttonOffset(slot, buttonCount());
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        float buttonWidth  = BASE_BUTTON_WIDTH  * scale;
        float buttonHeight = BASE_BUTTON_HEIGHT * scale;
        float centerX = windowWidth  / 2.0f;
        float centerY = windowHeight / 2.0f;
        float x = centerX - buttonWidth / 2f;
        float y = centerY + offset * scale;
        return mouseX >= x && mouseX <= x + buttonWidth
            && mouseY >= y && mouseY <= y + buttonHeight;
    }
}
