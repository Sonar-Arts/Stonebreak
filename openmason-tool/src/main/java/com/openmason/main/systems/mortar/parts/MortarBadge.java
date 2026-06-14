package com.openmason.main.systems.mortar.parts;

import com.openmason.main.systems.mortar.core.MortarPart;
import com.openmason.main.systems.mortar.core.PartState;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;

/**
 * A small rounded category pill: faint surface fill with dimmed label. Most
 * often painted inline inside another part (cards, preview) via the static
 * {@link #paint} helper, which sizes the pill to its text and returns the
 * width consumed; also usable as a standalone {@link MortarPart} centered in
 * the rect it's given.
 */
public final class MortarBadge implements MortarPart {

    private static final float FONT_SIZE = 11f;
    private static final float PAD_X = 8f;
    private static final float HEIGHT = 18f;

    private final String text;

    public MortarBadge(String text) {
        this.text = text;
    }

    @Override
    public void paint(MortarPainter g, float x, float y, float w, float h, PartState state) {
        if (text == null || text.isEmpty()) {
            return;
        }
        float pillW = measureWidth(g, text);
        paint(g, x + (w - pillW) / 2f, y + h / 2f, text);
    }

    /** Width a pill for {@code text} would occupy. */
    public static float measureWidth(MortarPainter g, String text) {
        return g.measureWidth(text, Weight.MEDIUM, FONT_SIZE) + PAD_X * 2f;
    }

    /**
     * Paint a pill whose left edge is at {@code x}, vertically centered on
     * {@code cy}. Returns the pill width.
     */
    public static float paint(MortarPainter g, float x, float cy, String text) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        MortarTheme theme = g.theme();
        float textW = g.measureWidth(text, Weight.MEDIUM, FONT_SIZE);
        float pillW = textW + PAD_X * 2f;
        float top = cy - HEIGHT / 2f;
        g.fillRoundRect(x, top, pillW, HEIGHT, HEIGHT / 2f, theme.badgeBg);
        g.text(text, x + PAD_X, cy, MortarPainter.Align.LEFT, Weight.MEDIUM, FONT_SIZE, theme.textDim);
        return pillW;
    }
}
