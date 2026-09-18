package com.stonebreak.ui.focusBattle;

import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

/**
 * Every colour, font size and shared drawing primitive of the Focus battle HUD, declared once.
 *
 * <p>The Frostbound Crucible behind the HUD is bright snow under a pale sunset sky, so the game's
 * grey stone panel washes out. Battle windows are dark slate glass with an ice-blue border instead
 * ({@link #battleWindow}), every bar sits on a dark track with a 1px dark outline, and every string
 * is drawn with a shadow — the three rules that keep the HUD legible over snow.
 *
 * <p>Colours are ARGB ({@code 0xAARRGGBB}) for Skija's {@code Paint.setColor(int)}. All helpers are
 * deterministic (no clock, no randomness) so raster tests can diff two states.
 */
public final class FocusBattleTheme {

    private FocusBattleTheme() {}

    // ─────────────────────────────────────────────── Windows
    public static final int WINDOW_FILL        = 0xCC141C26;
    public static final int WINDOW_BORDER      = 0xFF8FD3FF;
    public static final int WINDOW_HIGHLIGHT   = 0x30CDEBFF;
    public static final int WINDOW_DROP_SHADOW = 0x59000000;
    public static final float WINDOW_BORDER_WIDTH = 1.5f;
    public static final float WINDOW_RADIUS    = 5f;

    // ─────────────────────────────────────────────── Text
    public static final int TEXT          = 0xFFF4F8FC;
    public static final int TEXT_LABEL    = 0xFFA9C6DC;
    public static final int TEXT_DISABLED = 0xFF7A8794;
    public static final int TEXT_WARNING  = 0xFFFFB45A;
    public static final int TEXT_SHADOW   = 0xF0060A10;

    // ─────────────────────────────────────────────── Rows + cursor
    public static final int ROW_SELECTED      = 0x478FD3FF;
    public static final int ROW_SELECTED_EDGE = 0xB38FD3FF;
    /** A root row whose submenu is open: the highlight stays, dimmer, so the path reads. */
    public static final int ROW_HELD          = 0x248FD3FF;
    public static final int CURSOR            = 0xFFFFFFFF;
    public static final int CURSOR_OUTLINE    = 0xFF0B1118;
    public static final int CURSOR_CUFF       = 0xFF8FD3FF;

    // ─────────────────────────────────────────────── Bars
    public static final int BAR_TRACK    = 0xE60A0F16;
    public static final int BAR_OUTLINE  = 0xFF04070B;
    public static final int HP_HIGH      = 0xFF4FD16A;
    public static final int HP_MID       = 0xFFF2B33D;
    public static final int HP_LOW       = 0xFFE5484D;
    /** Delayed damage trail behind the HP fill. */
    public static final int HP_GHOST     = 0xFFF4F0E0;
    public static final int ATB          = 0xFF35D6F2;
    public static final int ATB_FULL     = 0xFFFFFFFF;
    public static final int QI           = 0xFF3FD8A0;
    public static final int QI_EMPTY     = 0xFF1B2A30;
    public static final int FOCUS        = 0xFFFFC83D;
    public static final int FOCUS_GLOW   = 0x59FFC83D;
    public static final int FOCUS_SHIMMER = 0xB3FFFFFF;
    public static final int FROST        = 0xFF7CC4FF;
    public static final int TELEGRAPH_START = 0xFF4A9DFF;
    public static final int TELEGRAPH_END   = 0xFFF0453A;
    public static final int TELEGRAPH_CANCELLED = 0xFF6F7780;
    public static final int PARRY_MARKER      = 0xFFFFF2A8;
    public static final int PARRY_MARKER_FILL = 0x73FFF2A8;
    public static final int HIT_FLASH    = 0xFFFF4040;

    // ─────────────────────────────────────────────── Status chips
    public static final int CHIP_FILL    = 0xE60C131B;
    public static final int CHIP_GOOD    = 0xFF6FE3A1;
    public static final int CHIP_BAD     = 0xFFFF8A5C;

    // ─────────────────────────────────────────────── Base font sizes (× the HUD scale)
    public static final float FS_NAME   = 18f;
    public static final float FS_ROW    = 17f;
    public static final float FS_LABEL  = 13f;
    public static final float FS_VALUE  = 14f;
    public static final float FS_HELP   = 15f;
    public static final float FS_CHIP   = 12f;
    public static final float FS_TAG    = 13f;
    /** Never shrink fitted text below this share of its design size. */
    private static final float MIN_FIT  = 0.6f;

    // ─────────────────────────────────────────────── Windows

    /**
     * The one window style of the battle HUD: soft drop shadow, slate-glass fill, a subtle inner
     * highlight along the top edge and a 1.5px ice-blue border. Replaces {@link MPainter#panel}.
     */
    public static void battleWindow(Canvas canvas, float x, float y, float w, float h) {
        battleWindow(canvas, x, y, w, h, 1f);
    }

    public static void battleWindow(Canvas canvas, float[] rect) {
        battleWindow(canvas, rect[0], rect[1], rect[2], rect[3], 1f);
    }

    /** {@code alpha} 0..1 fades the whole window (slide/fade transitions). */
    public static void battleWindow(Canvas canvas, float x, float y, float w, float h, float alpha) {
        if (canvas == null || w <= 0f || h <= 0f || alpha <= 0f) return;
        float r = Math.min(WINDOW_RADIUS, Math.min(w, h) / 2f);
        try (Paint p = new Paint().setColor(fade(WINDOW_DROP_SHADOW, alpha)).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x + 2f, y + 3f, w, h, r), p);
        }
        try (Paint p = new Paint().setColor(fade(WINDOW_FILL, alpha)).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, w, h, r), p);
        }
        if (w > 6f && h > 6f) {
            try (Paint p = new Paint().setColor(fade(WINDOW_HIGHLIGHT, alpha)).setAntiAlias(true)
                    .setMode(PaintMode.STROKE).setStrokeWidth(1f)) {
                canvas.drawRRect(RRect.makeXYWH(x + 2.5f, y + 2.5f, w - 5f, h - 5f, Math.max(0f, r - 2f)), p);
            }
        }
        try (Paint p = new Paint().setColor(fade(WINDOW_BORDER, alpha)).setAntiAlias(true)
                .setMode(PaintMode.STROKE).setStrokeWidth(WINDOW_BORDER_WIDTH)) {
            float half = WINDOW_BORDER_WIDTH / 2f;
            canvas.drawRRect(RRect.makeXYWH(x + half, y + half, w - WINDOW_BORDER_WIDTH,
                    h - WINDOW_BORDER_WIDTH, r), p);
        }
    }

    // ─────────────────────────────────────────────── Bars + pips

    /** Dark track, a fill spanning {@code fraction} of the width, then the 1px dark outline. */
    public static void bar(Canvas canvas, float x, float y, float w, float h, float fraction, int fillColor) {
        bar(canvas, x, y, w, h, fraction, fillColor, -1f, 0);
    }

    /**
     * Bar with an optional ghost trail: when {@code ghostFraction} exceeds {@code fraction} the span
     * between them is painted in {@code ghostColor} (the delayed damage trail). A negative ghost
     * fraction means none.
     */
    public static void bar(Canvas canvas, float x, float y, float w, float h, float fraction, int fillColor,
                           float ghostFraction, int ghostColor) {
        if (canvas == null || w <= 0f || h <= 0f) return;
        float f = clamp01(fraction);
        MPainter.fillRect(canvas, x, y, w, h, BAR_TRACK);
        float g = clamp01(ghostFraction);
        if (ghostFraction >= 0f && g > f) {
            MPainter.fillRect(canvas, x + w * f, y, w * (g - f), h, ghostColor);
        }
        if (f > 0f) {
            MPainter.fillRect(canvas, x, y, w * f, h, fillColor);
            // A lighter top band gives the fill a little volume without a gradient shader.
            MPainter.fillRect(canvas, x, y, w * f, Math.max(1f, h * 0.3f), 0x30FFFFFF);
        }
        MPainter.strokeRect(canvas, x + 0.5f, y + 0.5f, w - 1f, h - 1f, BAR_OUTLINE, 1f);
    }

    /** One diamond pip, centred in the square at {@code (x, y)} of side {@code size}. */
    public static void pip(Canvas canvas, float x, float y, float size, boolean filled, int fillColor) {
        if (canvas == null || size <= 0f) return;
        float cx = x + size / 2f, cy = y + size / 2f, r = size / 2f;
        diamond(canvas, cx, cy, r, BAR_OUTLINE);
        diamond(canvas, cx, cy, Math.max(0.5f, r - 1.5f), filled ? fillColor : QI_EMPTY);
        if (filled && r > 3f) {
            diamond(canvas, cx, cy - r * 0.22f, r * 0.3f, 0x66FFFFFF);
        }
    }

    private static void diamond(Canvas canvas, float cx, float cy, float r, int color) {
        try (PathBuilder pb = new PathBuilder()) {
            pb.moveTo(cx, cy - r);
            pb.lineTo(cx + r, cy);
            pb.lineTo(cx, cy + r);
            pb.lineTo(cx - r, cy);
            pb.closePath();
            try (Path path = pb.build(); Paint p = new Paint().setColor(color).setAntiAlias(true)) {
                canvas.drawPath(path, p);
            }
        }
    }

    /** Anti-aliased rounded fill (MPainter's variant is aliased, which shows on glass). */
    public static void roundedFill(Canvas canvas, float x, float y, float w, float h, float r, int color) {
        if (canvas == null || w <= 0f || h <= 0f || (color & 0xFF000000) == 0) return;
        try (Paint p = new Paint().setColor(color).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, w, h, Math.min(r, Math.min(w, h) / 2f)), p);
        }
    }

    public static void roundedStroke(Canvas canvas, float x, float y, float w, float h, float r,
                                     int color, float width) {
        if (canvas == null || w <= width || h <= width || (color & 0xFF000000) == 0) return;
        MPainter.strokeRoundedRect(canvas, x, y, w, h, Math.min(r, Math.min(w, h) / 2f), color, width);
    }

    public static void fillRect(Canvas canvas, float x, float y, float w, float h, int color) {
        if (canvas == null || w <= 0f || h <= 0f || (color & 0xFF000000) == 0) return;
        try (Paint p = new Paint().setColor(color)) {
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), p);
        }
    }

    // ─────────────────────────────────────────────── Colour ramps

    /** HP fill: green above half, blending to amber at half and to red near empty. */
    public static int hpColor(float fraction) {
        float f = clamp01(fraction);
        if (f >= 0.6f) return HP_HIGH;
        if (f >= 0.35f) return lerpColor(HP_MID, HP_HIGH, (f - 0.35f) / 0.25f);
        if (f >= 0.15f) return lerpColor(HP_LOW, HP_MID, (f - 0.15f) / 0.2f);
        return HP_LOW;
    }

    /** Cast-bar fill: calm blue at the start of the windup, alarm red as the blow lands. */
    public static int telegraphColor(float progress) {
        return lerpColor(TELEGRAPH_START, TELEGRAPH_END, clamp01(progress));
    }

    /** Component-wise ARGB blend, {@code t} 0 = {@code a}, 1 = {@code b}. */
    public static int lerpColor(int a, int b, float t) {
        float k = clamp01(t);
        int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * k) << 24) | (Math.round(ar + (br - ar) * k) << 16)
                | (Math.round(ag + (bg - ag) * k) << 8) | Math.round(ab + (bb - ab) * k);
    }

    /** Scales a colour's alpha by {@code alpha} 0..1. */
    public static int fade(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * clamp01(alpha));
        return (a << 24) | (color & 0xFFFFFF);
    }

    public static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    // ─────────────────────────────────────────────── Text

    /**
     * Font at {@code baseSize × scale}. The HUD scale is the layout's <em>effective</em> scale
     * ({@link FocusBattleLayout#effectiveScale}), which can be below the user's UI scale on a small
     * window, so the font is sized from the scale the geometry used rather than read from Settings.
     */
    public static Font font(MasonryUI ui, float baseSize, float scale) {
        return ui.fonts().get(quantize(Math.max(6f, baseSize * scale)));
    }

    /**
     * Half-pixel steps. The font cache never evicts, and several elements animate their text size
     * every frame (combo cell pulse, stamp pop, FLAWLESS pop) on top of a HUD scale that varies
     * continuously with the window: unquantised, that mints thousands of native fonts.
     */
    public static float quantize(float size) {
        return Math.round(size * 2f) / 2f;
    }

    /** Largest font at or below the design size whose rendering of {@code text} fits {@code maxWidth}. */
    public static Font fitFont(MasonryUI ui, String text, float baseSize, float scale, float maxWidth) {
        float size = quantize(Math.max(6f, baseSize * scale)); // stays on the half-pixel grid as it shrinks
        float floor = Math.max(6f, size * MIN_FIT);
        Font font = ui.fonts().get(size);
        while (font != null && size > floor && MPainter.measureWidth(font, text) > maxWidth) {
            size -= 1f;
            font = ui.fonts().get(size);
        }
        return font;
    }

    /** Baseline that vertically centres a line of {@code fontPx} text on {@code centreY}. */
    public static float baseline(float centreY, float fontPx) {
        return centreY + fontPx * 0.36f;
    }

    public static void text(Canvas canvas, String s, float x, float baselineY, Font font, int color) {
        MPainter.drawStringWithShadow(canvas, s, x, baselineY, font, color, shadowFor(color));
    }

    public static void textCentered(Canvas canvas, String s, float cx, float baselineY, Font font, int color) {
        MPainter.drawCenteredStringWithShadow(canvas, s, cx, baselineY, font, color, shadowFor(color));
    }

    public static void textRight(Canvas canvas, String s, float rightX, float baselineY, Font font, int color) {
        text(canvas, s, rightX - MPainter.measureWidth(font, s), baselineY, font, color);
    }

    // A faded label must not keep a full-strength shadow, or the shadow outlives the text.
    private static int shadowFor(int color) {
        return fade(TEXT_SHADOW, ((color >>> 24) & 0xFF) / 255f);
    }
}
