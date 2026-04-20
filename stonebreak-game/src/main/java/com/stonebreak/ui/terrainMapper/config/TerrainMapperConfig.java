package com.stonebreak.ui.terrainMapper.config;

/**
 * Layout + interaction constants for the terrain mapper screen. No logic —
 * pure sizing, spacing, and clamp values so renderers and handlers share a
 * single source of truth.
 */
public final class TerrainMapperConfig {

    private TerrainMapperConfig() {}

    // ─────────────────────────────────────────────── Panels
    public static final float SIDEBAR_WIDTH = 320f;
    public static final float FOOTER_HEIGHT = 88f;
    public static final float PANEL_MARGIN = 16f;

    // ─────────────────────────────────────────────── Sidebar content
    public static final float SIDEBAR_PADDING = 20f;
    public static final float SIDEBAR_SECTION_GAP = 16f;
    public static final float TEXT_FIELD_HEIGHT = 40f;
    public static final float TEXT_FIELD_LABEL_GAP = 6f;
    public static final int MAX_WORLD_NAME_LENGTH = 32;
    public static final int MAX_SEED_LENGTH = 20;
    public static final float MODE_BUTTON_HEIGHT = 34f;
    public static final float MODE_BUTTON_SPACING = 6f;

    // ─────────────────────────────────────────────── Footer buttons
    public static final float FOOTER_BUTTON_WIDTH = 180f;
    public static final float FOOTER_BUTTON_HEIGHT = 44f;
    public static final float FOOTER_BUTTON_GAP = 14f;

    // ─────────────────────────────────────────────── Viewport
    /** Pixels-per-world-block at zoom 1.0. A block maps to a single pixel. */
    public static final float BASE_WORLD_SCALE = 1f;
    public static final float ZOOM_MIN = 0.25f;
    public static final float ZOOM_MAX = 8f;
    public static final float ZOOM_STEP = 1.15f;

    /** Noise sample spacing in viewport pixels — larger = faster, blockier. */
    public static final int SAMPLE_STEP_PX = 2;

    /** Coarser sample spacing used during drag/zoom so interaction stays 60 fps. */
    public static final int SAMPLE_STEP_INTERACTIVE_PX = 6;

    /** After a wheel-zoom, keep interactive quality this long before resampling at hi-res. */
    public static final long ZOOM_COOLDOWN_NANOS = 180_000_000L;
}

