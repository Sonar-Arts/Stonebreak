package com.stonebreak.ui.statisticsScreen;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.runtime.screens.PresentationSlot;
import com.stonebreak.ui.runtime.screens.ScreenPresentation;

/**
 * Statistics screen showing per-world player activity trackers.
 * Follows the same pattern as {@link com.stonebreak.ui.PauseMenu}.
 *
 * <p>Since #299 it may be shown as the shipped UI document {@value #DOCUMENT_ID}
 * ({@link #setPresentation}); visibility and the STATISTICS state stay here.
 */
public class StatisticsScreen {

    /** The shipped document's screen id ({@code ui/documents/statistics.sbui}). */
    public static final String DOCUMENT_ID = "statistics";

    private static final float BASE_BUTTON_WIDTH  = SkijaStatisticsRenderer.BUTTON_WIDTH;
    private static final float BASE_BUTTON_HEIGHT = SkijaStatisticsRenderer.BUTTON_HEIGHT;

    private final SkijaStatisticsRenderer skijaRenderer;

    private final PresentationSlot presentation = new PresentationSlot();
    private boolean backButtonHovered = false;

    public StatisticsScreen(SkijaUIBackend backend) {
        this.skijaRenderer = new SkijaStatisticsRenderer(backend);
    }

    public void render(int windowWidth, int windowHeight) {
        if (!isVisible() || presentation.paint(windowWidth, windowHeight)) return;
        skijaRenderer.render(windowWidth, windowHeight, backButtonHovered);
    }

    public boolean isVisible() { return presentation.isVisible(); }

    public void setVisible(boolean visible) {
        presentation.setVisible(visible);
    }

    /** Installs (or, with null, removes) the alternative presentation; the legacy one is the default. */
    public void setPresentation(ScreenPresentation p) {
        presentation.install(p);
    }

    public boolean isBackButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return isVisible() && hitBackButton(mouseX, mouseY, windowWidth, windowHeight);
    }

    public void updateHover(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (!isVisible()) {
            backButtonHovered = false;
            return;
        }
        backButtonHovered = isBackButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
    }

    public void cleanup() {
        if (skijaRenderer != null) skijaRenderer.dispose();
    }

    private boolean hitBackButton(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        float bw = BASE_BUTTON_WIDTH  * scale;
        float bh = BASE_BUTTON_HEIGHT * scale;
        float panelHeight = SkijaStatisticsRenderer.PANEL_HEIGHT * scale;
        float cx = windowWidth  / 2f;
        float cy = windowHeight / 2f;
        float panelBottom = cy + panelHeight / 2f;
        float x = cx - bw / 2f;
        float y = panelBottom - (SkijaStatisticsRenderer.BACK_BUTTON_BOTTOM_MARGIN * scale) - bh;
        return mouseX >= x && mouseX <= x + bw
            && mouseY >= y && mouseY <= y + bh;
    }
}
