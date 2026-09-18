package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.RRect;

/**
 * The menu's pointing hand: a gloved fist with the index finger extended to the right, built from
 * rounded rects because the UI font has no hand or arrow glyphs. White with a dark outline so it
 * reads on both the slate window and the pale row highlight.
 */
public final class HandCursor {

    private HandCursor() {}

    /** Draws the hand inside the box {@code (x, y, w, h)}, finger tip touching the right edge. */
    public static void paint(Canvas canvas, float x, float y, float w, float h, float alpha) {
        if (canvas == null || w <= 2f || h <= 2f || alpha <= 0f) return;
        int outline = FocusBattleTheme.fade(FocusBattleTheme.CURSOR_OUTLINE, alpha);
        int glove = FocusBattleTheme.fade(FocusBattleTheme.CURSOR, alpha);
        int cuff = FocusBattleTheme.fade(FocusBattleTheme.CURSOR_CUFF, alpha);

        // Outline pass: the same silhouette, grown by one pixel.
        shape(canvas, x, y, w, h, 1f, outline, outline);
        shape(canvas, x, y, w, h, 0f, glove, cuff);
    }

    private static void shape(Canvas canvas, float x, float y, float w, float h, float grow,
                              int gloveColor, int cuffColor) {
        try (Paint glove = new Paint().setColor(gloveColor).setAntiAlias(true);
             Paint cuff = new Paint().setColor(cuffColor).setAntiAlias(true)) {
            // Cuff, palm (three curled fingers), thumb on top, index finger out to the right.
            rr(canvas, x, y + 0.34f * h, 0.16f * w, 0.56f * h, 0.04f * h, grow, cuff);
            rr(canvas, x + 0.16f * w, y + 0.3f * h, 0.42f * w, 0.64f * h, 0.16f * h, grow, glove);
            rr(canvas, x + 0.22f * w, y + 0.08f * h, 0.3f * w, 0.3f * h, 0.12f * h, grow, glove);
            rr(canvas, x + 0.36f * w, y + 0.3f * h, 0.64f * w, 0.28f * h, 0.14f * h, grow, glove);
        }
    }

    private static void rr(Canvas canvas, float x, float y, float w, float h, float r, float grow, Paint p) {
        canvas.drawRRect(RRect.makeXYWH(x - grow, y - grow, w + 2f * grow, h + 2f * grow, r + grow), p);
    }
}
