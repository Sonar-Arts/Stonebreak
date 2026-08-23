package com.stonebreak.rendering.UI.components.hotbar;

import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rpg.classes.AbilityIconCache;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Image;

/**
 * Cursor-style painter for the vertical HUD gauge panels beside the hotbar (class resource
 * gauges on the right, dodge cooldown on the left). Every primitive draws at the current
 * cursor and advances it, so each gauge is a linear list of header / bar / line calls sharing
 * one bar style (dark background, fill, 1px black border) and one icon-before-text rule.
 */
public final class GaugePanel {

    /** Horizontal gap between a gauge panel and the hotbar background. */
    public static final int PANEL_GAP  = 12;
    /** Vertical gap between a class gauge's header text and its first bar. */
    public static final int LABEL_GAP  = 10;
    /** Vertical gap between text status lines. */
    public static final int LINE_GAP   = 8;
    public static final int PIP_HEIGHT = 10;
    public static final int PIP_GAP    = 3;

    private static final int   BAR_BG     = 0xC83C3C3C;
    private static final int   BAR_BORDER = 0xFF000000;
    private static final float ICON_SIZE  = 20f;
    private static final float ICON_GAP   = 4f;

    private final Canvas canvas;
    private final Font   font;
    private final float  x;
    private final float  width;
    private float y;

    public GaugePanel(Canvas canvas, Font font, float x, float y, float width) {
        this.canvas = canvas;
        this.font   = font;
        this.x      = x;
        this.y      = y;
        this.width  = width;
    }

    /** Header text (accent colour) with an optional leading icon, then {@code gapBelow}. */
    public void header(String iconPath, String text, float gapBelow) {
        float labelX = iconPath == null ? x : drawIconBeforeText(iconPath, x, y, MStyle.FONT_META);
        MPainter.drawStringWithShadow(canvas, text, labelX, y + MStyle.FONT_META,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        y += MStyle.FONT_META + gapBelow;
    }

    /**
     * Full-width bar: background, a fill spanning {@code fraction} of the width in
     * {@code fillColor} (skipped when not positive), and the border; then {@code gapBelow}.
     */
    public void bar(int height, float fraction, int fillColor, float gapBelow) {
        MPainter.fillRect(canvas, x, y, width, height, BAR_BG);
        if (fraction >= 1f) {
            MPainter.fillRect(canvas, x, y, width, height, fillColor);
        } else if (fraction > 0f) {
            MPainter.fillRect(canvas, x, y, width * fraction, height, fillColor);
        }
        MPainter.strokeRect(canvas, x, y, width, height, BAR_BORDER, 1f);
        y += height + gapBelow;
    }

    /** Discrete pip: fully filled in {@code fillColor} when {@code filled}, else empty. */
    public void pip(boolean filled, int fillColor) {
        bar(PIP_HEIGHT, filled ? 1f : 0f, fillColor, PIP_GAP);
    }

    /** One text line (baseline one FONT_META below the cursor) with optional icon, then {@code gapBelow}. */
    public void line(String iconPath, String text, int color, float gapBelow) {
        y += MStyle.FONT_META;
        float lineX = iconPath == null ? x : drawIconBeforeText(iconPath, x, y - MStyle.FONT_META, MStyle.FONT_META);
        MPainter.drawStringWithShadow(canvas, text, lineX, y, font, color, MStyle.TEXT_SHADOW);
        y += gapBelow;
    }

    /** Advances the cursor by {@code amount} pixels. */
    public void space(float amount) {
        y += amount;
    }

    /**
     * Draws the icon at {@code iconPath} vertically centered against a text line spanning
     * {@code [lineTopY, lineTopY + lineHeight]} at {@code lineX}, and returns the x-coordinate
     * where the line's text should start (shifted right past the icon, or {@code lineX}
     * unchanged if the icon failed to load).
     */
    private float drawIconBeforeText(String iconPath, float lineX, float lineTopY, float lineHeight) {
        Image icon = AbilityIconCache.get(iconPath);
        if (icon == null) return lineX;
        float iconY = lineTopY + (lineHeight - ICON_SIZE) / 2f;
        MPainter.drawImage(canvas, icon, lineX, iconY, ICON_SIZE, ICON_SIZE);
        return lineX + ICON_SIZE + ICON_GAP;
    }
}
