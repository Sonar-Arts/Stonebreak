package com.stonebreak.ui.glossaryScreen;

import com.stonebreak.mobs.entities.EntityType;

/**
 * Pure layout math for the Entity Glossary's master–detail screen: an entity
 * list sidebar on the left, one large detail pane on the right. Every rect is
 * a function of {@code (windowWidth, windowHeight, uiScale)} only, shared by
 * the renderer (drawing) and {@link GlossaryScreen} (hit-testing) so the two
 * can never disagree — and unit-testable headlessly, which is what keeps the
 * layout from ever spilling outside its containers again.
 *
 * <p>The old screen hard-coded a 3-column card grid; with four glossary
 * entities the fourth card ran clean off the panel, and card content overran
 * the fixed card height. This layout instead <b>derives</b> everything from
 * the panel rect and degrades gracefully: the panel shrinks with the window,
 * list rows compress when the sidebar can't fit them at full height, and the
 * preview yields to the text columns on short panels.
 *
 * <p>All rects are {@code {x, y, w, h}} in screen pixels.
 */
public final class GlossaryLayout {

    // Base (unscaled) metrics. Scaled by uiScale, then clamped to the window.
    private static final float PANEL_MAX_W = 1100f;
    private static final float PANEL_MAX_H = 700f;
    private static final float PAD         = 20f;   // panel inner padding
    private static final float HEADER_H    = 80f;   // title + discovery progress strip
    private static final float SIDEBAR_MAX_W = 264f;
    private static final float ROW_H       = 56f;
    private static final float ROW_GAP     = 6f;
    private static final float ROW_INSET   = 8f;    // padding inside the sidebar inset
    private static final float DETAIL_GAP  = 18f;   // sidebar → detail pane gap
    private static final float DETAIL_HEADER_H = 34f;
    private static final float PREVIEW_MAX_H   = 250f;
    private static final float ARROW_SIZE  = 30f;   // variant cycler buttons
    private static final float ARROW_PAD   = 8f;    // arrow inset from preview edges
    private static final float BACK_W      = 240f;
    private static final float BACK_H      = 42f;
    private static final float BACK_MARGIN = 18f;   // back button → panel bottom
    private static final float CONTENT_GAP = 14f;   // content → back button gap

    private GlossaryLayout() {}

    public static int rowCount() {
        return EntityType.GLOSSARY_TYPES.length;
    }

    // ─────────────────────────────────────────────── Panel chrome

    /** Centered main panel, capped at its design size and at 92% of the window. */
    public static float[] panelRect(int windowWidth, int windowHeight, float scale) {
        float w = Math.min(PANEL_MAX_W * scale, windowWidth * 0.92f);
        float h = Math.min(PANEL_MAX_H * scale, windowHeight * 0.92f);
        return new float[]{(windowWidth - w) / 2f, (windowHeight - h) / 2f, w, h};
    }

    public static float[] backButtonRect(int windowWidth, int windowHeight, float scale) {
        float[] p = panelRect(windowWidth, windowHeight, scale);
        float w = Math.min(BACK_W * scale, p[2] - 2f * PAD * scale);
        float h = BACK_H * scale;
        return new float[]{p[0] + (p[2] - w) / 2f, p[1] + p[3] - BACK_MARGIN * scale - h, w, h};
    }

    /** Region between the header strip and the back button, panel-padded. */
    public static float[] contentRect(int windowWidth, int windowHeight, float scale) {
        float[] p = panelRect(windowWidth, windowHeight, scale);
        float[] back = backButtonRect(windowWidth, windowHeight, scale);
        float pad = PAD * scale;
        float top = p[1] + HEADER_H * scale;
        float bottom = back[1] - CONTENT_GAP * scale;
        return new float[]{p[0] + pad, top, p[2] - 2f * pad, Math.max(0f, bottom - top)};
    }

    // ─────────────────────────────────────────────── Sidebar (entity list)

    public static float[] sidebarRect(int windowWidth, int windowHeight, float scale) {
        float[] c = contentRect(windowWidth, windowHeight, scale);
        float w = Math.min(SIDEBAR_MAX_W * scale, c[2] * 0.34f);
        return new float[]{c[0], c[1], w, c[3]};
    }

    /**
     * Row height, compressed below the design height whenever the sidebar
     * cannot fit every entity at full size — the rows always fit by
     * construction, whatever the window/scale combination.
     */
    public static float listRowHeight(int windowWidth, int windowHeight, float scale) {
        float[] sb = sidebarRect(windowWidth, windowHeight, scale);
        int n = Math.max(1, rowCount());
        float inner = sb[3] - 2f * ROW_INSET * scale - (n - 1) * ROW_GAP * scale;
        return Math.min(ROW_H * scale, Math.max(0f, inner / n));
    }

    public static float[] listRowRect(int index, int windowWidth, int windowHeight, float scale) {
        float[] sb = sidebarRect(windowWidth, windowHeight, scale);
        float inset = ROW_INSET * scale;
        float rowH = listRowHeight(windowWidth, windowHeight, scale);
        float y = sb[1] + inset + index * (rowH + ROW_GAP * scale);
        return new float[]{sb[0] + inset, y, sb[2] - 2f * inset, rowH};
    }

    // ─────────────────────────────────────────────── Detail pane

    public static float[] detailRect(int windowWidth, int windowHeight, float scale) {
        float[] c = contentRect(windowWidth, windowHeight, scale);
        float[] sb = sidebarRect(windowWidth, windowHeight, scale);
        float x = sb[0] + sb[2] + DETAIL_GAP * scale;
        return new float[]{x, c[1], Math.max(0f, c[0] + c[2] - x), c[3]};
    }

    /** 3D preview inset: full detail width, capped so the text columns keep room. */
    public static float[] previewRect(int windowWidth, int windowHeight, float scale) {
        float[] d = detailRect(windowWidth, windowHeight, scale);
        float top = d[1] + DETAIL_HEADER_H * scale;
        float h = Math.min(PREVIEW_MAX_H * scale, d[3] * 0.42f);
        return new float[]{d[0], top, d[2], h};
    }

    public static float[] leftArrowRect(int windowWidth, int windowHeight, float scale) {
        float[] pv = previewRect(windowWidth, windowHeight, scale);
        float a = ARROW_SIZE * scale;
        return new float[]{pv[0] + ARROW_PAD * scale, pv[1] + (pv[3] - a) / 2f, a, a};
    }

    public static float[] rightArrowRect(int windowWidth, int windowHeight, float scale) {
        float[] pv = previewRect(windowWidth, windowHeight, scale);
        float a = ARROW_SIZE * scale;
        return new float[]{pv[0] + pv[2] - ARROW_PAD * scale - a, pv[1] + (pv[3] - a) / 2f, a, a};
    }

    // ─────────────────────────────────────────────── Hit helper

    public static boolean contains(float px, float py, float[] rect) {
        return px >= rect[0] && px <= rect[0] + rect[2]
            && py >= rect[1] && py <= rect[1] + rect[3];
    }
}
