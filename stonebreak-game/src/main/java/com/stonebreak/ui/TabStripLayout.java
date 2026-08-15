package com.stonebreak.ui;

/**
 * Single source of truth for the 5-tab strip (Inventory / Character / Classes /
 * Skills / Feats) shared by the inventory screen and the character sheet screen.
 *
 * <p>The two screens are separate GameStates that each draw their own copy of
 * the strip; clicking a tab swaps screens. Computing the strip geometry here —
 * with integer rounding applied once — keeps the tabs pixel-identical across
 * the swap. The strip is centered on the SCREEN (not on either panel, whose
 * widths differ) and sits flush above the panel top edge supplied by the caller.
 */
public final class TabStripLayout {

    public static final int TAB_COUNT = 5;
    public static final int TAB_WIDTH = 84;
    public static final int TAB_HEIGHT = 28;
    public static final int TAB_GAP = 4;

    private TabStripLayout() {
    }

    private static float uiScale() {
        return com.stonebreak.config.Settings.getInstance().getUiScale();
    }

    public static int tabWidth() {
        return Math.round(TAB_WIDTH * uiScale());
    }

    public static int tabHeight() {
        return Math.round(TAB_HEIGHT * uiScale());
    }

    public static int tabGap() {
        return Math.round(TAB_GAP * uiScale());
    }

    /** Horizontal distance between the left edges of adjacent tabs. */
    public static int stride() {
        return tabWidth() + tabGap();
    }

    /** Left edge of the strip: centered on the screen. */
    public static int startX(int screenWidth) {
        int stripWidth = TAB_COUNT * tabWidth() + (TAB_COUNT - 1) * tabGap();
        return (screenWidth - stripWidth) / 2;
    }

    /** Top edge of the strip, sitting flush above the given panel top. */
    public static int tabY(int panelTopY) {
        return panelTopY - tabHeight();
    }
}
