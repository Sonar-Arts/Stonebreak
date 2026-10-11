package com.stonebreak.ui.runtime.screens;

/**
 * How a screen is shown while its lifecycle stays in the screen class (#297 onward: the shipped UI
 * document, {@link PresentedDocument}). The screen tells it when it shows and hides and asks it to
 * paint at the screen's place in the frame; {@link #paint} returns false when nothing was painted.
 */
public interface ScreenPresentation {

    void shown();

    void hidden();

    boolean paint(int windowWidth, int windowHeight);

    /** True while the presentation is what the player sees. */
    default boolean showing() {
        return false;
    }
}
