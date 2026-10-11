package com.stonebreak.ui.statisticsScreen;

import com.stonebreak.ui.runtime.screens.PresentationSlot;
import com.stonebreak.ui.runtime.screens.ScreenPresentation;

/**
 * Statistics screen showing per-world player activity trackers. Visibility and the STATISTICS
 * state stay here; it shows as the shipped UI document {@value #DOCUMENT_ID}
 * ({@link #setPresentation}), which the UI host feeds the player's statistics.
 */
public class StatisticsScreen {

    /** The shipped document's screen id ({@code ui/documents/statistics.sbui}). */
    public static final String DOCUMENT_ID = "statistics";

    private final PresentationSlot presentation = new PresentationSlot();

    public void render(int windowWidth, int windowHeight) {
        presentation.paint(windowWidth, windowHeight);
    }

    public boolean isVisible() { return presentation.isVisible(); }

    public void setVisible(boolean visible) {
        presentation.setVisible(visible);
    }

    /** Installs (or, with null, removes) the presentation. */
    public void setPresentation(ScreenPresentation p) {
        presentation.install(p);
    }
}
