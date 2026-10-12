package com.openmason.main.systems.uiEditor.view;

/**
 * Designer frame sizes in device pixels: common game window sizes and the Steam Deck. The
 * frame is what the document lays out into, exactly like the game window of that size.
 */
public enum ResolutionPreset {
    HD("1280 x 720", "HD 16:9", 1280, 720),
    DECK("1280 x 800", "Steam Deck 16:10", 1280, 800),
    WXGA("1366 x 768", "Laptop", 1366, 768),
    FHD("1920 x 1080", "Full HD 16:9", 1920, 1080),
    WUXGA("1920 x 1200", "16:10", 1920, 1200),
    QHD("2560 x 1440", "QHD 16:9", 2560, 1440),
    UW("3440 x 1440", "Ultrawide 21:9", 3440, 1440),
    UHD("3840 x 2160", "4K UHD", 3840, 2160),
    CUSTOM("Custom", "Any size", 0, 0);

    public final String label;
    public final String note;
    public final int width;
    public final int height;

    ResolutionPreset(String label, String note, int width, int height) {
        this.label = label;
        this.note = note;
        this.width = width;
        this.height = height;
    }

    /** The preset matching a size, else {@link #CUSTOM}. */
    public static ResolutionPreset of(int w, int h) {
        for (ResolutionPreset p : values()) {
            if (p.width == w && p.height == h) {
                return p;
            }
        }
        return CUSTOM;
    }

    /** UI scale presets offered next to the size (the game's UI scale setting). */
    public static final float[] UI_SCALES = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f};
    /** Device pixel ratios (DPI): 1x standard, 2x high density. */
    public static final float[] PIXEL_RATIOS = {1f, 1.25f, 1.5f, 2f};
}
