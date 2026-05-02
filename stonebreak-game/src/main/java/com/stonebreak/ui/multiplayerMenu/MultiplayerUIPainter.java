package com.stonebreak.ui.multiplayerMenu;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

/**
 * Reusable Skija drawing primitives for the multiplayer menu screens.
 * Encapsulates dirt background, button drawing, and centered text so each
 * multiplayer screen stays small.
 */
public final class MultiplayerUIPainter {

    public static final int   COLOR_TEXT      = 0xFFFFFFF0;
    public static final int   COLOR_TEXT_HI   = 0xFFFFCC55;
    public static final int   COLOR_TEXT_DIM  = 0xFFAAAAAA;
    public static final int   COLOR_SHADOW    = 0xFF1A1A1A;
    public static final int   COLOR_OVERLAY   = 0x90000000;
    public static final int   COLOR_FIELD_BG  = 0xFF101010;
    public static final int   COLOR_FIELD_BG_FOCUS = 0xFF202030;
    public static final int   COLOR_FIELD_BORDER = 0xFFFFFFFF;

    private final SkijaUIBackend backend;
    private Shader dirtShader;

    public MultiplayerUIPainter(SkijaUIBackend backend) {
        this.backend = backend;
    }

    public void ensureDirtShader() {
        if (dirtShader != null) return;
        Image dirt = backend.getDirtTexture();
        if (dirt == null) return;
        dirtShader = dirt.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, SamplingMode.DEFAULT, null);
    }

    public void drawBackground(Canvas canvas, int w, int h) {
        ensureDirtShader();
        try (Paint p = new Paint().setColor(0xFF2C2C2C)) {
            canvas.drawRect(Rect.makeXYWH(0, 0, w, h), p);
        }
        if (dirtShader != null) {
            try (Paint p = new Paint().setShader(dirtShader)) {
                canvas.save();
                canvas.scale(4f, 4f);
                canvas.drawRect(Rect.makeXYWH(0, 0, w / 4f, h / 4f), p);
                canvas.restore();
            }
        }
        try (Paint p = new Paint().setColor(COLOR_OVERLAY)) {
            canvas.drawRect(Rect.makeXYWH(0, 0, w, h), p);
        }
    }

    public void drawButton(Canvas canvas, String label, float x, float y, float w, float h,
                           boolean highlighted, Font font) {
        int fill = highlighted ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
        MPainter.stoneSurface(canvas, x, y, w, h, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
        int textColor = highlighted ? COLOR_TEXT_HI : COLOR_TEXT;
        float tx = x + w / 2f;
        float ty = y + h / 2f + 7f;
        MPainter.drawCenteredStringWithShadow(canvas, label, tx, ty, font, textColor, COLOR_SHADOW);
    }

    public void drawCentered(Canvas canvas, String text, float cx, float y, Font font, int color) {
        if (text == null || text.isEmpty()) return;
        float width = font.measureTextWidth(text);
        try (Paint p = new Paint().setColor(color)) {
            canvas.drawString(text, cx - width / 2f, y, font, p);
        }
    }

    public void drawLeft(Canvas canvas, String text, float x, float y, Font font, int color) {
        if (text == null) return;
        try (Paint p = new Paint().setColor(color)) {
            canvas.drawString(text, x, y, font, p);
        }
    }

    public void drawTextField(Canvas canvas, String text, boolean focused, boolean showCaret,
                              float x, float y, float w, float h, Font font) {
        try (Paint bg = new Paint().setColor(focused ? COLOR_FIELD_BG_FOCUS : COLOR_FIELD_BG)) {
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), bg);
        }
        try (Paint border = new Paint().setColor(focused ? COLOR_TEXT_HI : COLOR_FIELD_BORDER)
                .setMode(io.github.humbleui.skija.PaintMode.STROKE).setStrokeWidth(focused ? 2f : 1f)) {
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), border);
        }
        String display = (text == null) ? "" : text;
        if (focused && showCaret) display += "_";
        try (Paint p = new Paint().setColor(COLOR_TEXT)) {
            canvas.drawString(display, x + 8f, y + h / 2f + 6f, font, p);
        }
    }

    public boolean hits(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    public void dispose() {
        if (dirtShader != null) { dirtShader.close(); dirtShader = null; }
    }
}
