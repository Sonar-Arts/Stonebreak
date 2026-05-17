package com.stonebreak.ui.hotbar.core;

import static org.lwjgl.nanovg.NanoVG.*;

/**
 * Font constants and helpers for the NanoVG hotbar renderers.
 * Kept only until the old hotbar is replaced by {@code MHotbarRenderer} everywhere.
 */
public final class HotbarFonts {

    // Font face name (loaded by UIRenderer)
    private static final String FONT_NAME = "minecraft";

    // Size constants (copied from the old RecipeFonts before deletion)
    public static final float TITLE_LARGE  = 32.0f;
    public static final float TITLE_MEDIUM = 24.0f;
    public static final float TITLE_SMALL  = 18.0f;
    public static final float BODY_LARGE   = 16.0f;
    public static final float BODY_MEDIUM  = 14.0f;
    public static final float BODY_SMALL   = 12.0f;
    public static final float UI_BUTTON    = 13.0f;
    public static final float UI_TINY      = 10.0f;

    private HotbarFonts() {}

    public static void setBodyFont(long vg, float size) {
        setFont(vg, FONT_NAME, size);
    }

    public static void setTitleFont(long vg, float size) {
        setFont(vg, FONT_NAME, size);
    }

    private static void setFont(long vg, String name, float size) {
        nvgFontSize(vg, size);
        nvgFontFace(vg, name);
    }
}
