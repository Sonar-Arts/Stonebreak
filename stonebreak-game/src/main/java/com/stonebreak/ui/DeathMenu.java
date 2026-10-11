package com.stonebreak.ui;

import com.stonebreak.ui.runtime.screens.PresentationSlot;
import com.stonebreak.ui.runtime.screens.ScreenPresentation;

/**
 * The death menu that appears when the player dies. Visibility, the game loop's trigger and the
 * cursor stay here; it shows as the shipped UI document {@value #DOCUMENT_ID} ({@link #setPresentation}),
 * whose Respawn runs {@link DeathMenuActions#respawn}.
 */
public class DeathMenu {

    /** The shipped document's screen id ({@code ui/documents/death.sbui}). */
    public static final String DOCUMENT_ID = "death";

    private final PresentationSlot presentation = new PresentationSlot();

    /** Paints the menu's presentation. */
    public void render(int windowWidth, int windowHeight) {
        presentation.paint(windowWidth, windowHeight);
    }

    /**
     * Checks if the menu is currently visible.
     */
    public boolean isVisible() {
        return presentation.isVisible();
    }

    /** Installs (or, with null, removes) the presentation. */
    public void setPresentation(ScreenPresentation p) {
        presentation.install(p);
    }

    /**
     * Sets the visibility of the menu.
     */
    public void setVisible(boolean visible) {
        presentation.setVisible(visible);
    }
}
