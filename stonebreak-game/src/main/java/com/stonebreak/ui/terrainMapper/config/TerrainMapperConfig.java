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
    /**
     * Furthest zoom-out: 1/16 means one pixel stands for 16 world blocks, so a ~1200 px map
     * spans ~19k blocks. What bounds this is not the sample count — that is fixed by the
     * viewport size and {@link #SAMPLE_STEP_PX} — but the number of distinct 256-block bridge
     * tiles the samples land in, since an uncached tile costs a diffusion inference. Sampling
     * runs off the render thread (see TerrainPreviewLoader), so a wide view degrades into a
     * slow "Sampling terrain..." rather than a freeze.
     */
    public static final float ZOOM_MIN = 0.0625f;
    public static final float ZOOM_MAX = 8f;
    public static final float ZOOM_STEP = 1.15f;

    /** Noise sample spacing in viewport pixels — larger = faster, blockier. */
    public static final int SAMPLE_STEP_PX = 2;

    /** Coarser sample spacing used during drag/zoom so interaction stays 60 fps. */
    public static final int SAMPLE_STEP_INTERACTIVE_PX = 6;

    /** After a wheel-zoom, keep interactive quality this long before resampling at hi-res. */
    public static final long ZOOM_COOLDOWN_NANOS = 180_000_000L;

    /**
     * Quiet period after the last seed edit before the visualizers are rebuilt. Rebuilding
     * restarts the two local terrain-diffusion processes (they can't be reseeded in place —
     * see TerrainServiceProcessManager), so typing a seed character-by-character must not
     * trigger one restart per keystroke.
     */
    public static final long SEED_APPLY_DELAY_NANOS = 500_000_000L;

    // ─────────────────────────────────────────────── Topography visualizer
    /**
     * Block height that maps to the top (white) of the topography land ramp; anything higher
     * clamps to white. Deliberately well below WORLD_HEIGHT: the bridge maps elevation as
     * {@code SEA_LEVEL + elev_m / 15} (terrain-bridge/bridge/height_mapping.py), so real peaks
     * land far short of 1023 and ramping to the world ceiling would waste most of the palette.
     */
    public static final int TOPO_LAND_CEILING = 700;

    /**
     * Blocks of contour interval to allow per block of sample-cell width. A paper map picks a
     * coarser interval as its scale drops for exactly this reason: lines land roughly
     * {@code interval / (slope * blocksPerSample)} samples apart, so holding the interval fixed
     * while zooming out multiplies their on-screen density until they merge into black mush.
     *
     * <p>2.5 is "keep lines ~5 samples apart on a half-block-per-block slope", which reproduces
     * the familiar 5/10-block interval at close zoom and backs off to 100/200 at ZOOM_MIN.
     */
    public static final float TOPO_CONTOUR_INTERVAL_SCALE = 2.5f;
}

