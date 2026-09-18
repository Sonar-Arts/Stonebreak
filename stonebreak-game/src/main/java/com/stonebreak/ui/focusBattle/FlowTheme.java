package com.stonebreak.ui.focusBattle;

import com.stonebreak.rendering.UI.masonryUI.MPainter;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

/**
 * Colours and drawing primitives of the HUD's flow elements (action banner, target cursor,
 * floaters, encounter transition, result panel). Extends {@link FocusBattleTheme}, which is shared
 * and read-only: anything that theme already names is used from there, never redeclared.
 *
 * <p>Floating text has no window behind it and the arena is bright snow, so it is drawn with a dark
 * stroked outline rather than the windows' drop shadow.
 */
public final class FlowTheme {

    private FlowTheme() {}

    // ─────────────────────────────────────────────── Sides
    /** Archon actions and the defeat title: a cold, slightly pink red that still reads on snow. */
    public static final int FROST_RED      = 0xFFFF6B7A;
    public static final int FROST_RED_FILL = 0xCC2A1218;
    public static final int MONK_TINT      = FocusBattleTheme.WINDOW_BORDER;
    public static final int GOLD           = FocusBattleTheme.FOCUS;
    public static final int GOLD_FILL      = 0xCC2A2210;

    // ─────────────────────────────────────────────── Floaters
    public static final int FLOAT_DAMAGE  = 0xFFFFFFFF;
    public static final int FLOAT_CRIT    = 0xFFFFD75A;
    public static final int FLOAT_BLOCK   = 0xFFB8C2CC;
    public static final int FLOAT_PARRY   = 0xFF9FE0FF;
    public static final int FLOAT_HEAL    = 0xFF6BF08A;
    public static final int FLOAT_QI      = FocusBattleTheme.QI;
    public static final int FLOAT_PERFECT = 0xFFFFD75A;
    public static final int FLOAT_GOOD    = 0xFFC8F2FF;
    public static final int FLOAT_MISS    = 0xFFAEB6BF;
    public static final int FLOAT_REJECT  = 0xFFFF6B6B;
    public static final int OUTLINE       = 0xFF05080D;

    // ─────────────────────────────────────────────── Transition + result
    public static final int FLASH_WHITE   = 0xFFFFFFFF;
    public static final int WIPE_FROST    = 0xFFDDF2FF;
    public static final int CARD_BAND     = 0xB3050A12;
    public static final int CARD_TITLE    = 0xFFF4FAFF;
    public static final int CARD_SUBTITLE = 0xFFB9DDF5;
    public static final int RESULT_SCRIM  = 0x8C03060A;
    public static final int BUTTON_FILL   = 0xE60C131B;

    // ─────────────────────────────────────────────── Base font sizes (× the HUD scale)
    public static final float FS_BANNER      = 17f;
    public static final float FS_FLOAT       = 26f;
    public static final float FS_FLOAT_CRIT  = 36f;
    public static final float FS_FLOAT_SMALL = 18f;
    public static final float FS_FLOAT_WORD  = 23f;
    public static final float FS_TARGET_TAG  = 14f;
    public static final float FS_CARD_TITLE  = 46f;
    public static final float FS_CARD_SUB    = 15f;
    public static final float FS_RESULT_TITLE = 34f;
    public static final float FS_RESULT_STAT  = 15f;
    public static final float FS_RESULT_BUTTON = 15f;
    public static final float FS_HINT        = 13f;

    /** {@link FocusBattleTheme#battleWindow} with its fill and border recoloured and faded. */
    public static void tintedWindow(Canvas canvas, float x, float y, float w, float h, int fill, int border,
                                    float alpha) {
        if (canvas == null || w <= 0f || h <= 0f || alpha <= 0f) return;
        float r = Math.min(FocusBattleTheme.WINDOW_RADIUS, Math.min(w, h) / 2f);
        try (Paint p = new Paint().setColor(FocusBattleTheme.fade(FocusBattleTheme.WINDOW_DROP_SHADOW, alpha))
                .setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x + 2f, y + 3f, w, h, r), p);
        }
        try (Paint p = new Paint().setColor(FocusBattleTheme.fade(fill, alpha)).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, w, h, r), p);
        }
        try (Paint p = new Paint().setColor(FocusBattleTheme.fade(border, alpha)).setAntiAlias(true)
                .setMode(PaintMode.STROKE).setStrokeWidth(FocusBattleTheme.WINDOW_BORDER_WIDTH)) {
            float half = FocusBattleTheme.WINDOW_BORDER_WIDTH / 2f;
            canvas.drawRRect(RRect.makeXYWH(x + half, y + half, w - FocusBattleTheme.WINDOW_BORDER_WIDTH,
                    h - FocusBattleTheme.WINDOW_BORDER_WIDTH, r), p);
        }
    }

    /** Text centred on {@code cx} with a dark stroked outline; {@code alpha} fades both. */
    public static void outlinedTextCentered(Canvas canvas, String text, float cx, float baselineY, Font font,
                                            int color, float alpha) {
        if (canvas == null || font == null || text == null || text.isEmpty() || alpha <= 0f) return;
        float x = cx - MPainter.measureWidth(font, text) / 2f;
        float stroke = Math.max(2f, font.getSize() * 0.16f);
        try (Paint outline = new Paint().setColor(FocusBattleTheme.fade(OUTLINE, alpha)).setAntiAlias(true)
                .setMode(PaintMode.STROKE).setStrokeWidth(stroke).setStrokeJoin(PaintStrokeJoin.ROUND);
             Paint fill = new Paint().setColor(FocusBattleTheme.fade(color, alpha)).setAntiAlias(true)) {
            canvas.drawString(text, x, baselineY + 1f, font, outline);
            canvas.drawString(text, x, baselineY, font, fill);
        }
    }

    /** Width of {@code text} drawn with {@code tracking} extra pixels between glyphs. */
    public static float spacedWidth(Font font, String text, float tracking) {
        if (font == null || text == null || text.isEmpty()) return 0f;
        float width = 0f;
        for (int i = 0; i < text.length(); i++) {
            width += MPainter.measureWidth(font, String.valueOf(text.charAt(i)));
        }
        return width + tracking * (text.length() - 1);
    }

    /** Letter-spaced, outlined text centred on {@code cx} (the encounter name card). */
    public static void spacedTextCentered(Canvas canvas, String text, float cx, float baselineY, Font font,
                                          float tracking, int color, float alpha) {
        if (canvas == null || font == null || text == null || text.isEmpty() || alpha <= 0f) return;
        float x = cx - spacedWidth(font, text, tracking) / 2f;
        for (int i = 0; i < text.length(); i++) {
            String glyph = String.valueOf(text.charAt(i));
            float w = MPainter.measureWidth(font, glyph);
            outlinedTextCentered(canvas, glyph, x + w / 2f, baselineY, font, color, alpha);
            x += w + tracking;
        }
    }

    /** Horizontal band that is {@code color} in the middle and transparent at both ends. */
    public static void fadedBand(Canvas canvas, float x, float y, float w, float h, int color, float alpha) {
        if (canvas == null || w <= 0f || h <= 0f || alpha <= 0f) return;
        int solid = FocusBattleTheme.fade(color, alpha);
        int clear = solid & 0x00FFFFFF;
        try (Shader shader = Shader.makeLinearGradient(x, y, x + w, y, new int[]{clear, solid, solid, clear},
                new float[]{0f, 0.25f, 0.75f, 1f});
             Paint p = new Paint().setShader(shader)) {
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), p);
        }
    }

    /** Stroked circle (iris edge, pulse rings). */
    public static void ring(Canvas canvas, float cx, float cy, float radius, float width, int color) {
        if (canvas == null || radius <= 0f || width <= 0f || (color & 0xFF000000) == 0) return;
        try (Paint p = new Paint().setColor(color).setAntiAlias(true).setMode(PaintMode.STROKE)
                .setStrokeWidth(width)) {
            canvas.drawCircle(cx, cy, radius, p);
        }
    }
}
