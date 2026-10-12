package com.stonebreak.ui;

import com.stonebreak.ui.runtime.screens.PresentationSlot;

/**
 * The pause menu that appears when the user presses the escape key. Owns the lifecycle (visibility,
 * which the PAUSED state follows); the shipped UI document {@code ui.pauseMenu.PauseDocument} is
 * how it shows, its buttons reaching {@link PauseMenuActions} through the UI host.
 */
public class PauseMenu {

    /**
     * How the menu is shown while the lifecycle stays here (#297: the shipped UI document,
     * {@code ui.pauseMenu.PauseDocument}). Told when the menu shows and hides, and asked to paint on
     * every {@link #render} call.
     */
    public interface Presentation extends com.stonebreak.ui.runtime.screens.ScreenPresentation {
    }

    private final PresentationSlot presentation = new PresentationSlot();

    /** Installs (or, with null, removes) the presentation. */
    public void setPresentation(Presentation presentation) {
        this.presentation.install(presentation);
    }

    /** Paints the menu where the frame renderer asks for it (twice per frame over the field). */
    public void render(int windowWidth, int windowHeight) {
        presentation.paint(windowWidth, windowHeight);
    }

    public boolean isVisible() {
        return presentation.isVisible();
    }

    public void setVisible(boolean visible) {
        presentation.setVisible(visible);
    }
}
