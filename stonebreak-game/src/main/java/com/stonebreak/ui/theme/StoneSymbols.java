package com.stonebreak.ui.theme;

import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.theme.SymbolPainter;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;

/**
 * Stonebreak's {@code -sb-symbol}s (moved out of the engine's {@code MSymbol}, #384). Like the
 * engine's symbols each ignores degenerate boxes and fully transparent colours, so the shadow
 * pass of {@link SymbolPainter#drawWithShadow} draws exactly what the legacy symbols drew.
 */
final class StoneSymbols {

    private StoneSymbols() {
    }

    /**
     * The container screens' crafting arrow ({@link StonePainter#craftingArrow}): a shaft and head
     * spanning the whole box, not centred in its square like the others (#300).
     */
    static final SymbolPainter CRAFTING_ARROW = guarded((c, x, y, w, h, color) ->
        StonePainter.craftingArrow(c, x, y, w, h, color));

    /**
     * The chat's emoji-button face (#301): a 1.5 px circle of radius {@code min(w, h) / 2 - 4}, two
     * 1.3 px dot eyes and a bottom-arc smile, as the legacy chat renderer drew it.
     */
    static final SymbolPainter SMILEY = guarded((c, x, y, w, h, color) ->
        drawSmiley(c, x + w / 2f, y + h / 2f, Math.min(w, h) / 2f - 4f, color));

    /** The health heart: two round lobes joined by a triangle down to the point. */
    static final SymbolPainter HEART = guarded((c, x, y, w, h, color) ->
        drawHeart(c, x + w / 2f, y + h / 2f, Math.min(w, h), color));

    /**
     * The stone buttons' navigation arrows ({@link MPainter#navArrow}, {@code MButton.Arrow}): a shaft
     * and open head spanning the box (#301). With {@code drawWithShadow} they are exactly the legacy
     * two-pass arrow.
     */
    static final SymbolPainter NAV_LEFT = guarded((c, x, y, w, h, color) ->
        MPainter.navArrow(c, x, y, w, h, true, color, 0));
    static final SymbolPainter NAV_RIGHT = guarded((c, x, y, w, h, color) ->
        MPainter.navArrow(c, x, y, w, h, false, color, 0));

    private static SymbolPainter guarded(SymbolPainter p) {
        return (c, x, y, w, h, color) -> {
            if (c == null || w <= 0f || h <= 0f || (color & 0xFF000000) == 0) return;
            p.draw(c, x, y, w, h, color);
        };
    }

    private static void drawHeart(Canvas canvas, float cx, float cy, float s, int color) {
        // Two round lobes joined by a triangle down to the point — a curve-free
        // approximation that reads as a heart at UI icon sizes.
        fillCircle(canvas, cx - 0.15f * s, cy - 0.11f * s, 0.17f * s, color);
        fillCircle(canvas, cx + 0.15f * s, cy - 0.11f * s, 0.17f * s, color);
        try (PathBuilder pb = new PathBuilder()) {
            pb.moveTo(cx - 0.3f * s, cy - 0.05f * s);
            pb.lineTo(cx + 0.3f * s, cy - 0.05f * s);
            pb.lineTo(cx, cy + 0.34f * s);
            pb.lineTo(cx - 0.3f * s, cy - 0.05f * s);
            try (Path path = pb.build();
                 Paint paint = new Paint().setColor(color).setAntiAlias(true)) {
                canvas.drawPath(path, paint);
            }
        }
    }

    private static void fillCircle(Canvas canvas, float cx, float cy, float r, int color) {
        try (Paint p = new Paint().setColor(color).setAntiAlias(true)) {
            canvas.drawCircle(cx, cy, r, p);
        }
    }

    private static void drawSmiley(Canvas canvas, float cx, float cy, float fr, int color) {
        try (Paint p = new Paint().setColor(color).setMode(PaintMode.STROKE).setStrokeWidth(1.5f).setAntiAlias(true)) {
            canvas.drawCircle(cx, cy, fr, p);
        }
        try (Paint p = new Paint().setColor(color).setAntiAlias(true)) {
            canvas.drawCircle(cx - fr * 0.28f, cy - fr * 0.22f, 1.3f, p);
            canvas.drawCircle(cx + fr * 0.28f, cy - fr * 0.22f, 1.3f, p);
        }
        // the bottom arc of a small oval below the face's centre (0 to 180 degrees from 3 o'clock)
        float smileW = fr * 0.75f;
        float smileH = fr * 0.4f;
        float smileL = cx - smileW / 2f;
        float smileT = cy + fr * 0.1f;
        try (Paint p = new Paint().setColor(color).setMode(PaintMode.STROKE).setStrokeWidth(1.5f).setAntiAlias(true)) {
            canvas.drawArc(smileL, smileT, smileL + smileW, smileT + smileH, 0f, 180f, false, p);
        }
    }
}
