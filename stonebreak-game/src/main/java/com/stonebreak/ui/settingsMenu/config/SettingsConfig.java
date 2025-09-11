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
    
    // ===== AUDIO CONFIGURATION =====
    public static final float VOLUME_STEP = 0.1f;
    public static final float MIN_VOLUME = 0.0f;
    public static final float MAX_VOLUME = 1.0f;
    
    // ===== BUTTON POSITIONING OFFSETS =====
    public static final float RESOLUTION_BUTTON_Y_OFFSET = -170;
    public static final float ARM_MODEL_BUTTON_Y_OFFSET = -50;
    public static final float CROSSHAIR_STYLE_BUTTON_Y_OFFSET = 10;
    public static final float APPLY_BUTTON_Y_OFFSET = 130;
    public static final float BACK_BUTTON_Y_OFFSET = 190;
    
    // ===== SLIDER POSITIONING OFFSETS =====
    public static final float VOLUME_SLIDER_Y_OFFSET = -110;
    public static final float CROSSHAIR_SIZE_SLIDER_Y_OFFSET = 70;
    
    // ===== SEPARATOR POSITIONING OFFSETS =====
    public static final float DISPLAY_SEPARATOR_Y_OFFSET = -140;
    public static final float AUDIO_SEPARATOR_Y_OFFSET = -80;
    public static final float ARM_MODEL_SEPARATOR_Y_OFFSET = -20;
    public static final float CROSSHAIR_SEPARATOR_Y_OFFSET = 70;
    
    public static final float SEPARATOR_WIDTH_FACTOR = 0.8f;
    
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