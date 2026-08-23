package com.stonebreak.rendering.UI.components.hotbar;

import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Fading stone-surface tooltip centred above the selected hotbar slot and clamped to the
 * screen.
 */
public final class HotbarTooltipRenderer {

    private final MasonryUI ui;

    public HotbarTooltipRenderer(MasonryUI ui) {
        this.ui = ui;
    }

    public void draw(Canvas canvas, String text, float alpha,
                     int selectedIndex,
                     HotbarLayoutCalculator.HotbarLayout layout,
                     int sw, int sh) {
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        Font  font  = ui.fonts().getScaled(MStyle.FONT_ITEM);
        float textW = MPainter.measureWidth(font, text);
        float pad   = 8f * scale;
        float boxW  = textW + pad * 2.5f;
        float boxH  = MStyle.FONT_ITEM * scale + pad * 2f;

        // Centre tooltip above the selected slot
        HotbarLayoutCalculator.SlotPosition slotPos =
                HotbarLayoutCalculator.calculateSlotPosition(selectedIndex, layout);
        float bx  = slotPos.centerX - boxW / 2f;
        float gap = 8f * scale;
        float by  = layout.backgroundY - boxH - gap;

        // Keep within screen
        float margin = 8f * scale;
        bx = Math.max(margin, Math.min(bx, sw - boxW - margin));
        by = Math.max(margin, Math.min(by, sh - boxH - margin));

        MPainter.stoneSurface(canvas, bx, by, boxW, boxH, MStyle.PANEL_RADIUS,
                a(MStyle.PANEL_FILL_DEEP, alpha), a(MStyle.PANEL_BORDER, alpha),
                a(MStyle.PANEL_HIGHLIGHT, alpha), a(MStyle.PANEL_SHADOW, alpha),
                a(MStyle.PANEL_DROP_SHADOW, alpha),
                a(MStyle.PANEL_NOISE_DARK, alpha), a(MStyle.PANEL_NOISE_LIGHT, alpha));

        float textBaseline = by + boxH / 2f + MStyle.FONT_ITEM * 0.35f * scale;
        MPainter.drawCenteredStringWithShadow(canvas, text, bx + boxW / 2f, textBaseline,
                font, a(MStyle.TEXT_PRIMARY, alpha), a(MStyle.TEXT_SHADOW, alpha));
    }

    /** Multiply the alpha channel of an ARGB colour by {@code factor} (0–1). */
    private static int a(int argb, float factor) {
        int   newA = Math.min(255, (int)(((argb >>> 24) & 0xFF) * factor));
        return (newA << 24) | (argb & 0x00FFFFFF);
    }
}
