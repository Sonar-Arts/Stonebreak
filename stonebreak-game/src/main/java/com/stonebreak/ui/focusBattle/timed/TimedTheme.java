package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;

/**
 * Colours, easing curves and drawing primitives of the timed-input overlays (E9, E10, E12, E14).
 *
 * <p>These elements float over the 3D scene with no window behind them, and the scene is both bright
 * snow and a near-black Archon. The one rule that keeps them readable on either: <b>every bright
 * stroke sits on a wider dark under-stroke</b> ({@link #haloCircle}, {@link #haloLine}) and every
 * word is drawn with a full dark outline ({@link #outlinedTextCentered}) rather than a drop shadow.
 *
 * <p>Colours are ARGB. Everything is deterministic: no clock, no randomness.
 */
public final class TimedTheme {

    private TimedTheme() {}

    // ─────────────────────────────────────────────── Shared
    /** The dark under-stroke / text outline. */
    public static final int UNDER         = 0xE6060A10;
    public static final int PERFECT       = FocusBattleTheme.FOCUS;   // gold
    public static final int PERFECT_HOT   = 0xFFFFF0B8;
    public static final int GOOD          = 0xFFFFFFFF;
    public static final int MISS          = 0xFFFF5A52;
    public static final int MISS_DARK     = 0xFF5A1414;
    public static final int NEUTRAL_GREY  = 0xFFB4BEC8;

    // ─────────────────────────────────────────────── E9 timing ring
    public static final int RING_TARGET      = 0xFFFFFFFF;
    public static final int RING_OUTER       = 0xFFFFFFFF;
    public static final int RING_OUTER_GOOD  = 0xFF8FE9FF;
    public static final int RING_OUTER_LATE  = 0xFFFF8A7A;
    /** Translucent thickness zones: the good band, and the narrower perfect band inside it. */
    public static final int RING_GOOD_BAND    = 0x3DFFFFFF;
    public static final int RING_PERFECT_BAND = 0x70FFC83D;

    // ─────────────────────────────────────────────── E12 parry
    public static final int PARRY_BRACKET      = 0xFFE8F6FF;
    public static final int PARRY_BRACKET_LIVE = FocusBattleTheme.PARRY_MARKER;
    public static final int PARRY_GLOW         = 0x59FFF2A8;
    public static final int PARRY_FLASH        = 0xFFD6F0FF;
    public static final int PARRY_FLASH_RING   = 0xFFFFFFFF;
    public static final int BLOCK_SHIELD       = 0xFF9FD4FF;

    // ─────────────────────────────────────────────── E10 combo strip
    public static final int CELL_FILL        = 0xF0101822;
    public static final int CELL_FILL_DIM    = 0xB30E141C;
    public static final int CELL_BORDER      = FocusBattleTheme.WINDOW_BORDER;
    public static final int CELL_BORDER_DIM  = 0x668FD3FF;
    public static final int CELL_ARROW       = 0xFFFFFFFF;
    public static final int CELL_ARROW_DIM   = 0x80C4D4E2;
    public static final int CELL_STAMP_INK   = 0xFF1A1408;
    public static final int TIMER_FULL       = 0xFF35D6F2;
    public static final int TIMER_MID        = 0xFFF2B33D;
    public static final int TIMER_EMPTY      = 0xFFF0453A;

    // ─────────────────────────────────────────────── E14 screen FX
    public static final int FX_LOW_HP     = 0xFFC8141E;
    public static final int FX_FROST      = 0xFFE4F5FF;
    public static final int FX_FROST_EDGE = 0xFFFFFFFF;
    public static final int FX_FOCUS      = 0xFFFFC83D;
    public static final int FX_LETTERBOX  = 0xFF000000;
    public static final int FX_CRIT_DEALT = 0xFFFFF6E0;
    public static final int FX_CRIT_TAKEN = 0xFFFF5040;
    public static final int FX_DEFEAT_GREY = 0xFF6A7078;
    public static final int FX_DEFEAT_DARK = 0xFF05070A;

    // ─────────────────────────────────────────────── Font sizes (× HUD scale)
    public static final float FS_GRADE   = 26f;
    public static final float FS_BIG     = 40f;
    public static final float FS_HINT    = 12f;
    public static final float FS_PROMPT  = 16f;
    public static final float FS_COUNTER = 26f;
    public static final float FS_KEY     = 11f;

    // ─────────────────────────────────────────────── Easing

    public static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    public static float smoothstep(float t) {
        float k = clamp01(t);
        return k * k * (3f - 2f * k);
    }

    public static float easeOutCubic(float t) {
        float k = 1f - clamp01(t);
        return 1f - k * k * k;
    }

    /** 0 → 1 → 0 over {@code t} 0..1 with a fast attack (first fifth) and an eased release. */
    public static float flashEnvelope(float t) {
        float k = clamp01(t);
        if (k <= 0f || k >= 1f) return 0f;
        float attack = 0.2f;
        return k < attack ? k / attack : 1f - smoothstep((k - attack) / (1f - attack));
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ─────────────────────────────────────────────── Strokes

    /** A circle outline on a wider dark under-stroke. {@code alpha} fades both. */
    public static void haloCircle(Canvas canvas, float cx, float cy, float radius, float width, int color,
                                  float alpha, float scale) {
        if (canvas == null || radius <= 0f || alpha <= 0f) return;
        float under = width + Math.max(3f, 3f * scale);
        strokeCircle(canvas, cx, cy, radius, under, FocusBattleTheme.fade(UNDER, alpha));
        strokeCircle(canvas, cx, cy, radius, width, FocusBattleTheme.fade(color, alpha));
    }

    public static void strokeCircle(Canvas canvas, float cx, float cy, float radius, float width, int color) {
        if (canvas == null || radius <= 0f || width <= 0f || (color & 0xFF000000) == 0) return;
        try (Paint p = new Paint().setColor(color).setAntiAlias(true).setMode(PaintMode.STROKE)
                .setStrokeWidth(width)) {
            canvas.drawCircle(cx, cy, radius, p);
        }
    }

    /** The annulus between two radii, as one wide stroke. */
    public static void annulus(Canvas canvas, float cx, float cy, float inner, float outer, int color) {
        float lo = Math.max(0f, Math.min(inner, outer)), hi = Math.max(inner, outer);
        if (hi - lo <= 0f) return;
        strokeCircle(canvas, cx, cy, (lo + hi) / 2f, hi - lo, color);
    }

    /** A straight segment on a wider dark under-stroke. */
    public static void haloLine(Canvas canvas, float x0, float y0, float x1, float y1, float width, int color,
                                float alpha, float scale) {
        if (canvas == null || alpha <= 0f) return;
        line(canvas, x0, y0, x1, y1, width + Math.max(3f, 3f * scale), FocusBattleTheme.fade(UNDER, alpha));
        line(canvas, x0, y0, x1, y1, width, FocusBattleTheme.fade(color, alpha));
    }

    public static void line(Canvas canvas, float x0, float y0, float x1, float y1, float width, int color) {
        if (canvas == null || (color & 0xFF000000) == 0) return;
        try (Paint p = new Paint().setColor(color).setAntiAlias(true).setMode(PaintMode.STROKE)
                .setStrokeWidth(width).setStrokeCap(PaintStrokeCap.SQUARE)) {
            canvas.drawLine(x0, y0, x1, y1, p);
        }
    }

    /** Open polyline through {@code xy} = {x0,y0,x1,y1,…} on a dark under-stroke. */
    public static void haloPolyline(Canvas canvas, float[] xy, float width, int color, float alpha, float scale) {
        if (canvas == null || xy == null || xy.length < 4 || alpha <= 0f) return;
        try (PathBuilder pb = new PathBuilder()) {
            pb.moveTo(xy[0], xy[1]);
            for (int i = 2; i + 1 < xy.length; i += 2) pb.lineTo(xy[i], xy[i + 1]);
            try (Path path = pb.build()) {
                strokePath(canvas, path, width + Math.max(3f, 3f * scale), FocusBattleTheme.fade(UNDER, alpha));
                strokePath(canvas, path, width, FocusBattleTheme.fade(color, alpha));
            }
        }
    }

    private static void strokePath(Canvas canvas, Path path, float width, int color) {
        try (Paint p = new Paint().setColor(color).setAntiAlias(true).setMode(PaintMode.STROKE)
                .setStrokeWidth(width).setStrokeCap(PaintStrokeCap.SQUARE)) {
            canvas.drawPath(path, p);
        }
    }

    /** Closed filled polygon through {@code xy}. */
    public static void fillPolygon(Canvas canvas, float[] xy, int color) {
        if (canvas == null || xy == null || xy.length < 6 || (color & 0xFF000000) == 0) return;
        try (PathBuilder pb = new PathBuilder()) {
            pb.moveTo(xy[0], xy[1]);
            for (int i = 2; i + 1 < xy.length; i += 2) pb.lineTo(xy[i], xy[i + 1]);
            pb.closePath();
            try (Path path = pb.build(); Paint p = new Paint().setColor(color).setAntiAlias(true)) {
                canvas.drawPath(path, p);
            }
        }
    }

    public static void strokePolygon(Canvas canvas, float[] xy, float width, int color) {
        if (canvas == null || xy == null || xy.length < 6 || (color & 0xFF000000) == 0) return;
        try (PathBuilder pb = new PathBuilder()) {
            pb.moveTo(xy[0], xy[1]);
            for (int i = 2; i + 1 < xy.length; i += 2) pb.lineTo(xy[i], xy[i + 1]);
            pb.closePath();
            try (Path path = pb.build(); Paint p = new Paint().setColor(color).setAntiAlias(true)
                    .setMode(PaintMode.STROKE).setStrokeWidth(width)) {
                canvas.drawPath(path, p);
            }
        }
    }

    // ─────────────────────────────────────────────── Text

    /**
     * Centred text with a dark outline on all eight sides. A drop shadow is not enough here: these
     * words sit directly on the scene, and white text over snow needs dark pixels on every side.
     */
    public static void outlinedTextCentered(Canvas canvas, String text, float cx, float baselineY, Font font,
                                            int color, float alpha, float scale) {
        if (canvas == null || font == null || text == null || text.isEmpty() || alpha <= 0f) return;
        float x = cx - MPainter.measureWidth(font, text) / 2f;
        float o = Math.max(1f, Math.round(font.getSize() / 14f));
        int under = FocusBattleTheme.fade(UNDER, alpha * alpha); // outline falls off faster so a fading word never leaves a dark ghost
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                MPainter.drawString(canvas, text, x + dx * o, baselineY + dy * o, font, under);
            }
        }
        MPainter.drawString(canvas, text, x, baselineY + 2f * o, font, under);
        MPainter.drawString(canvas, text, x, baselineY, font, FocusBattleTheme.fade(color, alpha));
    }

    /**
     * A key hint: a dark keycap holding {@code key}, then {@code label}; the pair is centred on
     * {@code cx}. Returns the total width drawn.
     */
    public static float keyHint(Canvas canvas, Font font, String key, String label, float cx, float centreY,
                                float alpha, float scale) {
        if (canvas == null || font == null || alpha <= 0f) return 0f;
        float padX = 5f * scale, gap = 6f * scale;
        float keyW = MPainter.measureWidth(font, key) + 2f * padX;
        float labelW = MPainter.measureWidth(font, label);
        float capH = font.getSize() + 6f * scale;
        float total = keyW + gap + labelW;
        float x = cx - total / 2f;
        FocusBattleTheme.roundedFill(canvas, x, centreY - capH / 2f + scale, keyW, capH, 3f * scale,
                FocusBattleTheme.fade(0xFF000000, 0.6f * alpha));
        FocusBattleTheme.roundedFill(canvas, x, centreY - capH / 2f, keyW, capH, 3f * scale,
                FocusBattleTheme.fade(0xF01A2430, alpha));
        FocusBattleTheme.roundedStroke(canvas, x, centreY - capH / 2f, keyW, capH, 3f * scale,
                FocusBattleTheme.fade(FocusBattleTheme.WINDOW_BORDER, alpha), Math.max(1f, scale));
        float baseline = FocusBattleTheme.baseline(centreY, font.getSize());
        MPainter.drawString(canvas, key, x + padX, baseline, font, FocusBattleTheme.fade(FocusBattleTheme.TEXT, alpha));
        outlinedTextLeft(canvas, label, x + keyW + gap, baseline, font, FocusBattleTheme.TEXT, alpha);
        return total;
    }

    private static void outlinedTextLeft(Canvas canvas, String text, float x, float baselineY, Font font,
                                         int color, float alpha) {
        int under = FocusBattleTheme.fade(UNDER, alpha * alpha);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) MPainter.drawString(canvas, text, x + dx, baselineY + dy, font, under);
            }
        }
        MPainter.drawString(canvas, text, x, baselineY, font, FocusBattleTheme.fade(color, alpha));
    }
}
