package com.stonebreak.ui.worldSelect.config;

/**
 * Configuration constants for the WorldSelectScreen layout and styling.
 * Defines dimensions, spacing, colors, and other UI parameters.
 */
public class WorldSelectConfig {

    // ===== SCREEN LAYOUT =====
    public static final float SCREEN_TITLE_Y_OFFSET = -200; // Offset from center for screen title

    // ===== WORLD LIST DIMENSIONS =====
    public static final float LIST_WIDTH = 600;
    public static final float LIST_HEIGHT = 320; // 8 items * 40 pixels per item
    public static final float ITEM_HEIGHT = 40;
    public static final int ITEMS_PER_PAGE = 8;

    // ===== SCROLLING =====
    public static final int SCROLL_SPEED = 1;
    public static final float SCROLL_BAR_WIDTH = 8;
    public static final float SCROLL_BAR_MARGIN = 10;

    // ===== BUTTONS =====
    public static final float BUTTON_WIDTH = 200;
    public static final float BUTTON_HEIGHT = 40;
    public static final float BUTTON_MARGIN = 30; // Space between list and first button
    public static final float BUTTON_SPACING = 10; // Space between buttons

    // ===== DIALOG DIMENSIONS =====
    public static final float DIALOG_WIDTH = 400;
    public static final float DIALOG_HEIGHT = 300;
    public static final float DIALOG_CORNER_RADIUS = 8;

    // ===== TEXT INPUT FIELDS =====
    public static final float INPUT_FIELD_WIDTH = 360; // Dialog width - padding
    public static final float INPUT_FIELD_HEIGHT = 30;
    public static final float INPUT_FIELD_PADDING = 10;
    public static final float INPUT_FIELD_SPACING = 20; // Space between fields

    // ===== COLORS =====

    // Background colors
    public static final int BG_COLOR_R = 32;
    public static final int BG_COLOR_G = 32;
    public static final int BG_COLOR_B = 32;
    public static final int BG_COLOR_A = 200;

    // World list item colors
    public static final int ITEM_BG_COLOR_R = 64;
    public static final int ITEM_BG_COLOR_G = 64;
    public static final int ITEM_BG_COLOR_B = 64;
    public static final int ITEM_BG_COLOR_A = 180;

    public static final int ITEM_SELECTED_COLOR_R = 100;
    public static final int ITEM_SELECTED_COLOR_G = 100;
    public static final int ITEM_SELECTED_COLOR_B = 255;
    public static final int ITEM_SELECTED_COLOR_A = 200;

    public static final int ITEM_HOVERED_COLOR_R = 80;
    public static final int ITEM_HOVERED_COLOR_G = 80;
    public static final int ITEM_HOVERED_COLOR_B = 120;
    public static final int ITEM_HOVERED_COLOR_A = 190;

    // Button colors
    public static final int BUTTON_COLOR_R = 85;
    public static final int BUTTON_COLOR_G = 85;
    public static final int BUTTON_COLOR_B = 85;
    public static final int BUTTON_COLOR_A = 255;

    public static final int BUTTON_HOVERED_COLOR_R = 120;
    public static final int BUTTON_HOVERED_COLOR_G = 120;
    public static final int BUTTON_HOVERED_COLOR_B = 120;
    public static final int BUTTON_HOVERED_COLOR_A = 255;

    public static final int BUTTON_PRESSED_COLOR_R = 60;
    public static final int BUTTON_PRESSED_COLOR_G = 60;
    public static final int BUTTON_PRESSED_COLOR_B = 60;
    public static final int BUTTON_PRESSED_COLOR_A = 255;

    // Dialog colors
    public static final int DIALOG_BG_COLOR_R = 48;
    public static final int DIALOG_BG_COLOR_G = 48;
    public static final int DIALOG_BG_COLOR_B = 48;
    public static final int DIALOG_BG_COLOR_A = 240;

    public static final int DIALOG_BORDER_COLOR_R = 128;
    public static final int DIALOG_BORDER_COLOR_G = 128;
    public static final int DIALOG_BORDER_COLOR_B = 128;
    public static final int DIALOG_BORDER_COLOR_A = 255;

    // Input field colors
    public static final int INPUT_BG_COLOR_R = 32;
    public static final int INPUT_BG_COLOR_G = 32;
    public static final int INPUT_BG_COLOR_B = 32;
    public static final int INPUT_BG_COLOR_A = 255;

    public static final int INPUT_BORDER_COLOR_R = 64;
    public static final int INPUT_BORDER_COLOR_G = 64;
    public static final int INPUT_BORDER_COLOR_B = 64;
    public static final int INPUT_BORDER_COLOR_A = 255;

    public static final int INPUT_FOCUSED_BORDER_COLOR_R = 100;
    public static final int INPUT_FOCUSED_BORDER_COLOR_G = 150;
    public static final int INPUT_FOCUSED_BORDER_COLOR_B = 255;
    public static final int INPUT_FOCUSED_BORDER_COLOR_A = 255;

    // Text colors
    public static final int TEXT_COLOR_R = 255;
    public static final int TEXT_COLOR_G = 255;
    public static final int TEXT_COLOR_B = 255;
    public static final int TEXT_COLOR_A = 255;

    public static final int TEXT_SECONDARY_COLOR_R = 180;
    public static final int TEXT_SECONDARY_COLOR_G = 180;
    public static final int TEXT_SECONDARY_COLOR_B = 180;
    public static final int TEXT_SECONDARY_COLOR_A = 255;

    public static final int TEXT_DISABLED_COLOR_R = 120;
    public static final int TEXT_DISABLED_COLOR_G = 120;
    public static final int TEXT_DISABLED_COLOR_B = 120;
    public static final int TEXT_DISABLED_COLOR_A = 255;

    // Overlay colors
    public static final int OVERLAY_COLOR_R = 0;
    public static final int OVERLAY_COLOR_G = 0;
    public static final int OVERLAY_COLOR_B = 0;
    public static final int OVERLAY_COLOR_A = 120;

    // ===== FONT SIZES =====
    public static final float TITLE_FONT_SIZE = 48;
    public static final float BUTTON_FONT_SIZE = 18;
    public static final float ITEM_FONT_SIZE = 16;
    public static final float DIALOG_TITLE_FONT_SIZE = 24;
    public static final float LABEL_FONT_SIZE = 14;
    public static final float INPUT_FONT_SIZE = 14;

    // ===== ANIMATIONS =====
    public static final float HOVER_ANIMATION_SPEED = 0.1f;
    public static final float FADE_ANIMATION_SPEED = 0.05f;

    // ===== BORDERS AND CORNERS =====
    public static final float ITEM_CORNER_RADIUS = 4;
    public static final float BUTTON_CORNER_RADIUS = 6;
    public static final float INPUT_CORNER_RADIUS = 4;
    public static final float BORDER_WIDTH = 2;

    // ===== SPACING =====
    public static final float PADDING_SMALL = 8;
    public static final float PADDING_MEDIUM = 16;
    public static final float PADDING_LARGE = 24;

    // ===== SHADOW AND EFFECTS =====
    public static final float SHADOW_OFFSET_X = 2;
    public static final float SHADOW_OFFSET_Y = 2;
    public static final float SHADOW_BLUR = 4;
    public static final int SHADOW_COLOR_A = 100;

    // ===== CURSOR =====
    public static final float CURSOR_BLINK_SPEED = 1.0f; // blinks per second
    public static final float CURSOR_WIDTH = 2;

    // ===== EMPTY STATE =====
    public static final String EMPTY_LIST_TEXT = "No worlds found. Create your first world!";
    public static final float EMPTY_TEXT_FONT_SIZE = 18;

    // ===== WORLD INFO =====
    public static final boolean SHOW_WORLD_INFO = true;
    public static final float WORLD_INFO_FONT_SIZE = 12;
    public static final float WORLD_INFO_Y_OFFSET = 20; // Below world name

    private WorldSelectConfig() {
        // Private constructor to prevent instantiation
    }
}