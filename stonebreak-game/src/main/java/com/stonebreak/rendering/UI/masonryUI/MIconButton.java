package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;

/**
 * Square-ish stone button that draws an {@link MSymbol} instead of a text
 * label — close buttons, settings gears, add/remove rows, audio previews.
 *
 * <p>Extends {@link MButton} to inherit the stone body, enable/hover states,
 * and click plumbing; only the face changes. The icon takes its color from
 * the same state ladder as button text (disabled → hover accent → primary),
 * so icon and text buttons sitting side by side always agree.
 */
public class MIconButton extends MButton {

    private MSymbol symbol;
    private float iconScale = 0.55f;

    public MIconButton(MSymbol symbol) {
        super("");
        this.symbol = symbol;
    }

    // ─────────────────────────────────────────────── Fluent config

    public MIconButton symbol(MSymbol s) { this.symbol = s; return this; }

    /** Icon box as a fraction of the button's short side (default 0.55). */
    public MIconButton iconScale(float fraction) { this.iconScale = fraction; return this; }

    public MSymbol symbol() { return symbol; }

    // Covariant returns keep fluent chains typed as MIconButton.
    @Override public MIconButton onClick(Runnable action) { super.onClick(action); return this; }
    @Override public MIconButton enabled(boolean v) { super.enabled(v); return this; }
    @Override public MIconButton position(float x, float y) { super.position(x, y); return this; }
    @Override public MIconButton size(float w, float h) { super.size(w, h); return this; }
    @Override public MIconButton bounds(float x, float y, float w, float h) {
        super.bounds(x, y, w, h); return this;
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        Canvas canvas = ui.canvas();
        if (canvas == null) return;
        drawBody(canvas);
        if (symbol == null) return;

        int color = !enabled ? MStyle.TEXT_DISABLED
                : (hovered || selected) ? MStyle.TEXT_ACCENT
                : MStyle.TEXT_PRIMARY;
        float box = Math.min(width, height) * iconScale;
        symbol.drawWithShadow(canvas,
                x + (width - box) / 2f, y + (height - box) / 2f, box, box,
                color, MStyle.TEXT_SHADOW);
    }
}
