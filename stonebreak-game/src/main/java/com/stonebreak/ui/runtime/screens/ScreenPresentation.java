package com.stonebreak.ui.runtime.screens;

/**
 * Another way to show a legacy screen while its lifecycle stays where it was (#297 onward: the
 * shipped UI document, {@link PresentedDocument}). The screen tells it when it shows and hides and
 * asks it to paint where the legacy renderer drew; {@link #paint} returning false falls back to the
 * legacy renderer for that call.
 */
public interface ScreenPresentation {

    void shown();

    void hidden();

    boolean paint(int windowWidth, int windowHeight);

    /** True while the presentation, not the legacy screen, is what the player sees. */
    default boolean showing() {
        return false;
    }
}
