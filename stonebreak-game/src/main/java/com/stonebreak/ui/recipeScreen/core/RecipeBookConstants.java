package com.stonebreak.ui.recipeScreen.core;

/**
 * Geometry constants for the three-pane recipe book layout (sidebar + grid +
 * detail). Sizing matches the inventory's stone aesthetic so the two screens
 * read as one family.
 */
public final class RecipeBookConstants {

    public static final int PANEL_MAX_WIDTH    = 960;
    public static final int PANEL_MAX_HEIGHT   = 700;
    public static final int PANEL_MIN_MARGIN   = 80;
    public static final int PANEL_PADDING      = 12;

    public static final int HEADER_HEIGHT      = 36;   // search bar row
    public static final int SIDEBAR_WIDTH      = 140;  // left category column
    public static final int DETAIL_WIDTH       = 250;  // right detail pane

    public static final int SLOT_SIZE          = 36;   // recipe / detail slots
    public static final int SLOT_GAP           = 4;

    public static final int CATEGORY_BUTTON_HEIGHT = 32;
    public static final int CATEGORY_BUTTON_GAP    = 4;

    public static final int DETAIL_INPUT_GRID  = 3;    // 3×3 input grid
    public static final int PAGINATION_BUTTON_SIZE = 28;

    public static final String[] CATEGORIES = {"All", "Building", "Tools", "Food", "Decorative"};

    private RecipeBookConstants() {
        throw new UnsupportedOperationException("Utility class");
    }
}
