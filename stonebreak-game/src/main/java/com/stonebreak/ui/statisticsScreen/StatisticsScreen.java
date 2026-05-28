package com.stonebreak.ui.statisticsScreen;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;

/**
 * Statistics screen showing per-world player activity trackers.
 * Follows the same pattern as {@link com.stonebreak.ui.PauseMenu}.
 */
public class StatisticsScreen {

    private static final float BASE_BUTTON_WIDTH  = SkijaStatisticsRenderer.BUTTON_WIDTH;
    private static final float BASE_BUTTON_HEIGHT = SkijaStatisticsRenderer.BUTTON_HEIGHT;

    private final SkijaStatisticsRenderer skijaRenderer;

    private boolean visible = false;
    private boolean backButtonHovered = false;

    public StatisticsScreen(SkijaUIBackend backend) {
        this.skijaRenderer = new SkijaStatisticsRenderer(backend);
    }

    public void render(int windowWidth, int windowHeight) {
        if (!visible) return;
        skijaRenderer.render(windowWidth, windowHeight, backButtonHovered);
    }

    public boolean isVisible() { return visible; }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isBackButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitBackButton(mouseX, mouseY, windowWidth, windowHeight);
    }

    public void updateHover(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (!visible) {
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
