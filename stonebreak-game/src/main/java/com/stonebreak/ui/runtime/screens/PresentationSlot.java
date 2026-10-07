package com.stonebreak.ui.runtime.screens;

/**
 * A legacy screen's hook for a {@link ScreenPresentation}: tracks the screen's visibility and tells
 * the presentation about real changes only, so every migrated screen keeps its lifecycle with the
 * same three calls ({@link #install}, {@link #setVisible}, {@link #paint}). Main thread only.
 */
public final class PresentationSlot {

    private ScreenPresentation presentation;
    private boolean visible;

    /** Installs (or, with null, removes) the presentation; one installed while visible is shown at once. */
    public void install(ScreenPresentation p) {
        if (presentation != null && visible) {
            presentation.hidden();
        }
        presentation = p;
        if (p != null && visible) {
            p.shown();
        }
    }

    /** @return true when the visibility changed */
    public boolean setVisible(boolean v) {
        if (visible == v) {
            return false;
        }
        visible = v;
        if (presentation != null) {
            if (v) {
                presentation.shown();
            } else {
                presentation.hidden();
            }
        }
        return true;
    }

    public boolean isVisible() {
        return visible;
    }

    /** @return true when the presentation painted this call (the legacy renderer must not) */
    public boolean paint(int windowWidth, int windowHeight) {
        return visible && presentation != null && presentation.paint(windowWidth, windowHeight);
    }

    /** True while the presentation, not the legacy screen, is showing. */
    public boolean showing() {
        return visible && presentation != null && presentation.showing();
    }
}
