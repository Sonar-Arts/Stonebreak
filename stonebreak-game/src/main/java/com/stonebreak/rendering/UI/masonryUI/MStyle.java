package com.stonebreak.rendering.UI.masonryUI;

/**
 * Central palette and default sizing for MasonryUI widgets. Constants only —
 * no instances, no runtime configuration. Keeping one source of truth for the
 * Minecraft-ish bevel/gray aesthetic means individual widgets never hard-code
 * hex values and all future menus get consistent styling for free.
 *
 * Colors use ARGB ordering (0xAARRGGBB) for Skija's {@code Paint.setColor(int)}.
 */
public final class MStyle {

    private MStyle() {}

    // ─────────────────────────────────────────────── Text
    public static final int TEXT_PRIMARY   = 0xFFF7F7F2;
    public static final int TEXT_SHADOW    = 0xFF1A1A1A;
    public static final int TEXT_SECONDARY = 0xFFB8B8B0;
    public static final int TEXT_ACCENT    = 0xFFFFCC55;
    public static final int TEXT_DISABLED  = 0xFF787878;
    public static final int TEXT_ERROR     = 0xFFFF6464;

    // ─────────────────────────────────────────────── Overlays
    public static final int OVERLAY_DARK = 0x66000000;
    public static final int OVERLAY_DEEP = 0xCC000000;

    // ─────────────────────────────────────────────── Panels (stone look)
    public static final int PANEL_FILL        = 0xFF6B6B6B;
    public static final int PANEL_FILL_DEEP   = 0xFF4E4E4E;
    public static final int PANEL_HIGHLIGHT   = 0x38FFFFFF;
    public static final int PANEL_SHADOW      = 0x55000000;
    public static final int PANEL_DROP_SHADOW = 0x66000000;
    public static final int PANEL_BORDER      = 0xFF1F1F1F;
    public static final int PANEL_NOISE_DARK  = 0x38000000;
    public static final int PANEL_NOISE_LIGHT = 0x22FFFFFF;
    public static final float PANEL_RADIUS    = 4f;

    // ─────────────────────────────────────────────── Buttons (darker stone look)
    public static final int BUTTON_FILL       = 0xFF4C4C4C;
    public static final int BUTTON_FILL_HI    = 0xFF626680;
    public static final int BUTTON_FILL_DIS   = 0xFF333333;
    public static final int BUTTON_BORDER     = 0xFF141414;
    public static final int BUTTON_HIGHLIGHT  = 0x3CFFFFFF;
    public static final int BUTTON_SHADOW     = 0x60000000;
    public static final int BUTTON_DROP_SHADOW= 0x70000000;
    public static final int BUTTON_NOISE_DARK = 0x38000000;
    public static final int BUTTON_NOISE_LIGHT= 0x1CFFFFFF;
    public static final float BUTTON_RADIUS   = 3f;

    // ─────────────────────────────────────────────── Dropdowns
    public static final int DROPDOWN_FILL         = 0xFF2E2E2E;
    public static final int DROPDOWN_ITEM_FILL    = 0x00000000;
    public static final int DROPDOWN_ITEM_HOVER   = 0xFF5060A0;
    public static final int DROPDOWN_ITEM_CURRENT = 0xFF3C5090;

    // ─────────────────────────────────────────────── Sliders
    public static final int SLIDER_TRACK      = 0xFF1E1E1E;
    public static final int SLIDER_FILL       = 0xFF6A82C8;
    public static final int SLIDER_THUMB      = 0xFFF7F7F2;
    public static final int SLIDER_THUMB_EDGE = 0xFF141414;

    // ─────────────────────────────────────────────── Scrollbar
    public static final int SCROLLBAR_TRACK       = 0xFF3C3C3C;
    public static final int SCROLLBAR_THUMB       = 0xFF8C8C8C;
    public static final int SCROLLBAR_THUMB_EDGE  = 0xFFB4B4B4;

    // ─────────────────────────────────────────────── Default font sizes
    public static final float FONT_TITLE    = 36f;
    public static final float FONT_BUTTON   = 20f;
    public static final float FONT_ITEM     = 18f;
    public static final float FONT_META     = 14f;
    public static final float FONT_DROPDOWN = 18f;
}
