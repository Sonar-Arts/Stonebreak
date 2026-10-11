package com.stonebreak.ui.hotbar.styling;

/**
 * The hotbar's tooltip timing, read by the selected item's tooltip ({@code HotbarScreen}).
 */
public final class HotbarTheme {

    private HotbarTheme() {
        // Utility class - prevent instantiation
    }

    // ==================== ANIMATION ====================

    public static final class Animation {
        public static final float TOOLTIP_DISPLAY_DURATION = 1.5f; // Tooltip display time
        public static final float TOOLTIP_FADE_DURATION = 0.5f;    // Tooltip fade time
    }
}
