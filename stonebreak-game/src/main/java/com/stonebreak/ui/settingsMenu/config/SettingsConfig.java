package com.stonebreak.ui.settingsMenu.config;

/**
 * Configuration constants and static data for the settings menu.
 * Contains all UI dimensions, setting options, and configuration values.
 */
public final class SettingsConfig {
    
    // Prevent instantiation
    private SettingsConfig() {}
    
    // ===== ARM MODEL CONFIGURATION =====
    public static final String[] ARM_MODEL_TYPES = {
        "REGULAR", "SLIM"
    };
    
    public static final String[] ARM_MODEL_NAMES = {
        "Regular (4px wide)", "Slim (3px wide)"
    };
    
    // ===== CROSSHAIR CONFIGURATION =====
    public static final String[] CROSSHAIR_STYLES = {
        "SIMPLE_CROSS", "DOT", "CIRCLE", "SQUARE", "T_SHAPE", "PLUS_DOT"
    };
    
    public static final String[] CROSSHAIR_STYLE_NAMES = {
        "Simple Cross", "Dot", "Circle", "Square", "T-Shape", "Plus + Dot"
    };
    
    public static final float MIN_CROSSHAIR_SIZE = 4.0f;
    public static final float MAX_CROSSHAIR_SIZE = 64.0f;
    public static final float CROSSHAIR_SIZE_STEP = 2.0f;
    
    // ===== UI DIMENSIONS =====
    public static final float BUTTON_WIDTH = 400;
    public static final float BUTTON_HEIGHT = 40;
    public static final float SLIDER_WIDTH = 300;
    public static final float SLIDER_HEIGHT = 20;
    public static final float DROPDOWN_ITEM_HEIGHT = 30;
    
    // ===== CATEGORY PANEL DIMENSIONS =====
    public static final float CATEGORY_BUTTON_WIDTH = 150;
    public static final float CATEGORY_BUTTON_HEIGHT = 35;
    public static final float CATEGORY_PANEL_WIDTH = 180;
    public static final float SETTINGS_PANEL_WIDTH = 450;
    
    // ===== AUDIO CONFIGURATION =====
    public static final float VOLUME_STEP = 0.1f;
    public static final float MIN_VOLUME = 0.0f;
    public static final float MAX_VOLUME = 1.0f;
    
    // ===== TWO-PANEL LAYOUT POSITIONING =====
    
    // Category panel positioning (left side)
    public static final float CATEGORY_PANEL_X_OFFSET = -320; // Left of center
    public static final float CATEGORY_BUTTON_SPACING = 40;
    public static final float CATEGORY_BUTTONS_START_Y_OFFSET = -120;
    
    // Settings panel positioning (right side)
    public static final float SETTINGS_PANEL_X_OFFSET = 100; // Right of center
    
    // ===== BUTTON POSITIONING OFFSETS (Updated for settings panel) =====
    public static final float RESOLUTION_BUTTON_Y_OFFSET = -120;
    public static final float ARM_MODEL_BUTTON_Y_OFFSET = -80;
    public static final float CROSSHAIR_STYLE_BUTTON_Y_OFFSET = -40;
    public static final float APPLY_BUTTON_Y_OFFSET = 100;
    public static final float BACK_BUTTON_Y_OFFSET = 150;
    
    // ===== SLIDER POSITIONING OFFSETS (Updated for settings panel) =====
    public static final float VOLUME_SLIDER_Y_OFFSET = -80;
    public static final float CROSSHAIR_SIZE_SLIDER_Y_OFFSET = 0;
    
    // ===== SEPARATOR POSITIONING OFFSETS (Updated for new layout) =====
    public static final float PANEL_SEPARATOR_X_OFFSET = -10; // Separator between panels
    public static final float PANEL_SEPARATOR_HEIGHT = 300;
    public static final float SEPARATOR_WIDTH_FACTOR = 0.8f;
    
    // ===== SCROLLABLE CONTAINER CONFIGURATION =====
    public static final float SCROLL_TOP_MARGIN = 100; // Space for SETTINGS title + padding
    public static final float SCROLL_BOTTOM_MARGIN = 120; // Space for Apply/Back buttons + padding
    public static final float SCROLL_ITEM_SPACING = 80; // Space between settings items
    public static final float SCROLL_CONTENT_PADDING = 20; // Padding within scroll container
    public static final float SCROLLBAR_WIDTH = 12; // Width of the scrollbar
    
    // ===== SCROLL BEHAVIOR SETTINGS =====
    public static final float SCROLL_WHEEL_SENSITIVITY = 30; // Pixels per scroll wheel tick
    public static final float SCROLL_VELOCITY_FACTOR = 0.8f; // Multiplier for scroll velocity
    public static final float SCROLL_VELOCITY_DECAY = 0.85f; // Velocity decay rate per frame
    public static final float SCROLL_LERP_SPEED = 8.0f; // Smoothness of scroll interpolation
    
    /**
     * Gets the arm model index for the specified type.
     * @param currentArmModel the arm model type to find
     * @return the index, or 0 if not found
     */
    public static int findArmModelIndex(String currentArmModel) {
        for (int i = 0; i < ARM_MODEL_TYPES.length; i++) {
            if (ARM_MODEL_TYPES[i].equals(currentArmModel)) {
                return i;
            }
        }
        return 0; // Default to first type if not found
    }
    
    /**
     * Gets the crosshair style index for the specified style.
     * @param currentStyle the crosshair style to find
     * @return the index, or 0 if not found
     */
    public static int findCrosshairStyleIndex(String currentStyle) {
        for (int i = 0; i < CROSSHAIR_STYLES.length; i++) {
            if (CROSSHAIR_STYLES[i].equals(currentStyle)) {
                return i;
            }
        }
        return 0; // Default to first style if not found
    }
}