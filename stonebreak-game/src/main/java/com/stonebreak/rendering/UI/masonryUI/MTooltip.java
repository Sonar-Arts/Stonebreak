package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Single-line item-name tooltip rendered as a stone-surface box anchored
 * near the cursor. Keeps the box on-screen, regardless of cursor position.
 *
 * Shared by the inventory, workbench, and recipe screens so the tooltip
 * style stays consistent and only needs tuning in one place.
 */
public final class MTooltip {

    private MTooltip() {}

    /**
     * Draw a tooltip at {@code (x, y)} (typically {@code mouseX + 15},
     * {@code mouseY + 15}). Caller is responsible for opening the Skija frame.
     */
    public static void draw(MasonryUI ui, String text, float x, float y,
                            int screenWidth, int screenHeight) {
        if (ui == null || text == null || text.isEmpty()) return;
        Canvas canvas = ui.canvas();
        if (canvas == null) return;

        Font font  = ui.fonts().get(MStyle.FONT_ITEM);
        float textW = MPainter.measureWidth(font, text);
        float pad   = 8f;
        float boxW  = textW + pad * 2.5f;
        float boxH  = MStyle.FONT_ITEM + pad * 2f;

        float margin = 8f;
        float bx = Math.max(margin, Math.min(x, screenWidth  - boxW - margin));
        float by = Math.max(margin, Math.min(y, screenHeight - boxH - margin));

        MPainter.stoneSurface(canvas, bx, by, boxW, boxH, MStyle.PANEL_RADIUS,
                MStyle.PANEL_FILL_DEEP, MStyle.PANEL_BORDER,
                MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, MStyle.PANEL_DROP_SHADOW,
                MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);

        // Center the cap-height inside the box (≈ FONT_ITEM * 0.7 for cap height).
        float baseline = by + boxH / 2f + MStyle.FONT_ITEM * 0.35f;
        MPainter.drawCenteredStringWithShadow(canvas, text, bx + boxW / 2f, baseline,
                font, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
    }
}
