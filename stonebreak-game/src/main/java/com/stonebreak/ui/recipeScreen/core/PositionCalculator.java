package com.stonebreak.ui.recipeScreen.core;

import com.stonebreak.core.Game;

public final class PositionCalculator {

    private PositionCalculator() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static class PanelDimensions {
        public final int width;
        public final int height;
        public final int x;
        public final int y;

        public PanelDimensions(int width, int height, int x, int y) {
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
        }
    }

    public static class PopupDimensions {
        public final int width;
        public final int height;
        public final int x;
        public final int y;

        public PopupDimensions(int width, int height, int x, int y) {
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
        }
    }

    public static class GridLayout {
        public final int recipesPerRow;
        public final int maxVisibleRows;
        public final int gridX;
        public final int gridY;
        public final int gridWidth;
        public final int gridHeight;

        public GridLayout(int recipesPerRow, int maxVisibleRows, int gridX, int gridY, int gridWidth, int gridHeight) {
            this.recipesPerRow = recipesPerRow;
            this.maxVisibleRows = maxVisibleRows;
            this.gridX = gridX;
            this.gridY = gridY;
            this.gridWidth = gridWidth;
            this.gridHeight = gridHeight;
        }
    }

    public static PanelDimensions calculateMainPanelDimensions() {
        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();

        int panelWidth = Math.min(screenWidth - RecipeBookConstants.MIN_SCREEN_MARGIN,
                                 RecipeBookConstants.MAX_PANEL_WIDTH);
        int panelHeight = Math.min(screenHeight - RecipeBookConstants.MIN_SCREEN_MARGIN,
                                  RecipeBookConstants.MAX_PANEL_HEIGHT);
        int panelX = (screenWidth - panelWidth) / 2;
        int panelY = (screenHeight - panelHeight) / 2;

        return new PanelDimensions(panelWidth, panelHeight, panelX, panelY);
    }

    public static PopupDimensions calculatePopupDimensions() {
        int screenWidth = Game.getWindowWidth();
        int screenHeight = Game.getWindowHeight();

        int popupWidth = Math.min(RecipeBookConstants.POPUP_MAX_WIDTH,
                                 screenWidth - RecipeBookConstants.POPUP_MIN_MARGIN);
        int popupHeight = Math.min(RecipeBookConstants.POPUP_MAX_HEIGHT,
                                  screenHeight - RecipeBookConstants.POPUP_MIN_MARGIN);
        int popupX = (screenWidth - popupWidth) / 2;
        int popupY = (screenHeight - popupHeight) / 2;

        return new PopupDimensions(popupWidth, popupHeight, popupX, popupY);
    }

    public static GridLayout calculateRecipeGridLayout(PanelDimensions panel) {
        int categoryY = panel.y + RecipeBookConstants.TITLE_HEIGHT + RecipeBookConstants.PADDING;
        int searchBarY = categoryY + 35 + RecipeBookConstants.PADDING;
        int recipeGridY = searchBarY + RecipeBookConstants.SEARCH_BAR_HEIGHT + RecipeBookConstants.PADDING;
        int recipeGridX = panel.x + RecipeBookConstants.PADDING;
        int recipeGridWidth = panel.width - 2 * RecipeBookConstants.PADDING;
        int recipeGridHeight = panel.height - (recipeGridY - panel.y) - RecipeBookConstants.PADDING;

        int recipesPerRow = Math.max(1, recipeGridWidth / (RecipeBookConstants.RECIPE_DISPLAY_HEIGHT + 10));
        int maxVisibleRows = recipeGridHeight / (RecipeBookConstants.RECIPE_DISPLAY_HEIGHT + 10);

        return new GridLayout(recipesPerRow, maxVisibleRows, recipeGridX, recipeGridY,
                             recipeGridWidth, recipeGridHeight);
    }

    public static boolean isPointInBounds(float x, float y, int boundsX, int boundsY, int boundsWidth, int boundsHeight) {
        return x >= boundsX && x <= boundsX + boundsWidth &&
               y >= boundsY && y <= boundsY + boundsHeight;
    }

    public static int calculateCategoryButtonY(PanelDimensions panel) {
        return panel.y + RecipeBookConstants.TITLE_HEIGHT + RecipeBookConstants.PADDING;
    }

    public static int calculateSearchBarY(PanelDimensions panel) {
        int categoryY = calculateCategoryButtonY(panel);
        return categoryY + 35 + RecipeBookConstants.PADDING;
    }
}