package com.stonebreak.ui.recipeScreen.core;

public final class RecipeBookConstants {

    public static final int RECIPE_DISPLAY_HEIGHT = 80;
    public static final int ITEM_SLOT_SIZE = 40;
    public static final int PADDING = 20;
    public static final int TITLE_HEIGHT = 30;

    public static final int MAX_PANEL_WIDTH = 900;
    public static final int MAX_PANEL_HEIGHT = 700;
    public static final int MIN_SCREEN_MARGIN = 100;

    public static final int CATEGORY_BUTTON_HEIGHT = 25;
    public static final int CATEGORY_BUTTON_SPACING = 5;
    public static final int SEARCH_BAR_HEIGHT = 30;

    public static final int POPUP_MAX_WIDTH = 600;
    public static final int POPUP_MAX_HEIGHT = 480;
    public static final int POPUP_MIN_MARGIN = 80;
    public static final int CLOSE_BUTTON_SIZE = 24;
    public static final int PAGINATION_BUTTON_SIZE = 30;

    public static final long TYPING_TIMEOUT = 500L;

    public static final String[] CATEGORIES = {"All", "Building", "Tools", "Food", "Decorative"};

    private RecipeBookConstants() {
        throw new UnsupportedOperationException("Utility class");
    }
}