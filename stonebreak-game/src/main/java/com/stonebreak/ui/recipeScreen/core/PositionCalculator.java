package com.stonebreak.ui.recipeScreen.core;

import com.stonebreak.core.Game;

/**
 * Computes the recipe book's outer panel bounds. Sub-pane bounds (sidebar,
 * grid viewport, detail pane) are derived inside the
 * {@code RecipeRenderCoordinator} so geometry stays close to where it's used.
 */
public final class PositionCalculator {

    private PositionCalculator() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static class PanelDimensions {
        public final int x;
        public final int y;
        public final int width;
        public final int height;

        public PanelDimensions(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    public static PanelDimensions calculatePanelDimensions() {
        int sw = Game.getWindowWidth();
        int sh = Game.getWindowHeight();
        int width  = Math.min(RecipeBookConstants.PANEL_MAX_WIDTH,  sw - RecipeBookConstants.PANEL_MIN_MARGIN);
        int height = Math.min(RecipeBookConstants.PANEL_MAX_HEIGHT, sh - RecipeBookConstants.PANEL_MIN_MARGIN);
        int x = (sw - width)  / 2;
        int y = (sh - height) / 2;
        return new PanelDimensions(x, y, width, height);
    }

    public static boolean isPointInBounds(float x, float y, int boundsX, int boundsY,
                                          int boundsWidth, int boundsHeight) {
        return x >= boundsX && x <= boundsX + boundsWidth
            && y >= boundsY && y <= boundsY + boundsHeight;
    }
}
