package com.stonebreak.ui.terrainmapper.config;

/**
 * Configuration constants for the Terrain Mapper screen.
 * Contains layout dimensions, colors, and grid settings.
 */
public class TerrainMapperConfig {

    // Layout Constants
    public static final int SIDEBAR_WIDTH = 340;
    public static final int FOOTER_HEIGHT = 80;
    public static final int PADDING = 20;
    public static final int COMPONENT_SPACING = 20;

    // Sidebar Constants
    public static final int TITLE_HEIGHT = 80;
    public static final int INPUT_FIELD_HEIGHT = 45;
    public static final int INPUT_FIELD_WIDTH = SIDEBAR_WIDTH - (PADDING * 2);

    // Button Constants
    public static final int BUTTON_WIDTH = 200;
    public static final int BUTTON_HEIGHT = 40;

    // Grid Constants
    public static final int GRID_CELL_SIZE = 16;  // Size of each grid cell in pixels
    public static final float GRID_LINE_WIDTH = 1.0f;
    public static final float ZOOM_MIN = 0.5f;
    public static final float ZOOM_MAX = 5.0f;
    public static final float ZOOM_STEP = 0.1f;

    // Color Constants (RGBA 0-255)
    public static final int[] BACKGROUND_COLOR = {25, 25, 25, 255};
    public static final int[] SIDEBAR_BG_COLOR = {51, 38, 31, 255};
    public static final int[] FOOTER_BG_COLOR = {38, 38, 38, 255};

    public static final int[] GRID_LINE_COLOR = {76, 76, 76, 255};
    public static final int[] GRID_BG_COLOR = {46, 46, 46, 255};

    public static final int[] TEXT_COLOR = {255, 255, 255, 255};
    public static final int[] TEXT_COLOR_SECONDARY = {178, 178, 178, 255};

    // Font Sizes
    public static final int TITLE_FONT_SIZE = 48;
    public static final int LABEL_FONT_SIZE = 14;
    public static final int PREVIEW_TITLE_FONT_SIZE = 32;
    public static final int PREVIEW_SUBTITLE_FONT_SIZE = 16;

    // Animation Constants
    public static final float PAN_SMOOTHING = 0.8f;  // For smooth panning (0-1, higher = smoother)

    private TerrainMapperConfig() {
        // Private constructor to prevent instantiation
    }
}
