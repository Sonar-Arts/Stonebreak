package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;

/**
 * MasonryUI widget for a single inventory slot background.
 * Renders a recessed stone slot with hover and hotbar-selection states.
 *
 * Item icon and count rendering are handled by the coordinator in separate
 * GL and Skija phases — keeping this widget pure Skija and free of GL coupling.
 */
public class MItemSlot extends MWidget {

    // Recessed / inset look: darker fill, inverted bevel vs raised panels.
    // MPainter.stoneSurface(highlight, shadow) draws highlight on top+left and
    // shadow on bottom+right.  Swapping the arguments puts the dark on top+left
    // and the light on bottom+right — the classic "pressed-in" appearance.
    private static final int   FILL          = 0xFF252525;
    private static final int   BORDER        = 0xFF151515;
    private static final int   INSET_SHADOW  = 0x55000000; // top+left → dark = inset
    private static final int   INSET_LIGHT   = 0x1AFFFFFF; // bottom+right → light = inset
    private static final float RADIUS        = 3f;
    private static final int   HOVER_OVERLAY = 0x33FFFFFF;
    private static final int   SELECTED_RING = 0xCCFFCC55; // gold accent

    private boolean hotbarSelected;

    // ─── Fluent setters ──────────────────────────────────────────────────────

    public MItemSlot hotbarSelected(boolean v) { this.hotbarSelected = v; return this; }

    // Covariant returns so callers keep the concrete type when chaining.
    @Override public MItemSlot position(float x, float y)                 { super.position(x, y);     return this; }
    @Override public MItemSlot size(float w, float h)                     { super.size(w, h);          return this; }
    @Override public MItemSlot bounds(float x, float y, float w, float h) { super.bounds(x, y, w, h); return this; }

    // ─── Rendering ───────────────────────────────────────────────────────────

    @Override
    public void render(MasonryUI ui) {
        Canvas canvas = ui.canvas();
        if (canvas == null) return;

        // Inverted bevel gives the inset/recessed appearance.
        MPainter.stoneSurface(canvas, x, y, width, height, RADIUS,
                FILL, BORDER,
                INSET_SHADOW,  // drawn on top+left edge
                INSET_LIGHT,   // drawn on bottom+right edge
                0,             // no drop shadow for slots
                MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);

        if (hovered) {
            MPainter.fillRoundedRect(canvas, x + 1, y + 1, width - 2, height - 2, RADIUS, HOVER_OVERLAY);
        }

        if (hotbarSelected) {
            MPainter.strokeRect(canvas, x + 1, y + 1, width - 2, height - 2, SELECTED_RING, 2f);
        }
    }
}
