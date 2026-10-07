package com.stonebreak.ui.multiplayerMenu;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
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
    private java.util.function.BiConsumer<String, float[]> layoutSink;
    private int fieldIndex;

    /**
     * Receives every button ({@code button:<label>}) and text field ({@code field:<n>}, in draw order)
     * a frame draws, as {@code [x, y, w, h]}: the fidelity gates' geometry oracle (#299).
     */
    public void setLayoutSink(java.util.function.BiConsumer<String, float[]> sink) {
        this.layoutSink = sink;
    }

    public MultiplayerUIPainter(SkijaUIBackend backend) {
        this.backend = backend;
    }

    public void drawBackground(Canvas canvas, int w, int h) {
        fieldIndex = 0; // every screen draws its backdrop first
        // the shared menu backdrop (also the documents' stonebreak:dirt-backdrop provider, #299)
        com.stonebreak.ui.runtime.providers.DirtBackdropProvider.paint(canvas, 0, 0, w, h,
                com.stonebreak.ui.runtime.providers.DirtBackdropProvider.TILE_SCALE);
        try (Paint p = new Paint().setColor(COLOR_OVERLAY)) {
            canvas.drawRect(Rect.makeXYWH(0, 0, w, h), p);
        }
    }

    public void drawButton(Canvas canvas, String label, float x, float y, float w, float h,
                           boolean highlighted, Font font) {
        if (layoutSink != null) {
            layoutSink.accept("button:" + label, new float[]{x, y, w, h});
        }
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
        if (layoutSink != null) {
            layoutSink.accept("field:" + fieldIndex, new float[]{x, y, w, h});
        }
        fieldIndex++;
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
    }
}
