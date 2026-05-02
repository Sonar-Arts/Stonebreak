package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

/**
 * Reusable single-line text input — visual only. The caller owns the text
 * buffer (so it can plug into existing state objects like {@code SearchState})
 * and pushes the current value into the field each frame via {@link #text}.
 *
 * Caret blink is driven off wall-clock time so screens don't have to manage
 * a tick counter just for this widget.
 *
 * <h3>Hit-testing</h3>
 * Inherits {@code contains(mx, my)} from {@link MWidget} — coordinator code
 * checks the bounds itself and toggles the {@code active} flag accordingly,
 * matching the convention used by other Stonebreak MasonryUI screens.
 */
public final class MSearchField extends MWidget {

    private static final int FILL          = 0xFF1F1F1F; // recessed dark
    private static final int FILL_ACTIVE   = 0xFF2A2A2A;
    private static final int BORDER        = 0xFF0F0F0F;
    private static final int BORDER_ACTIVE = MStyle.SLIDER_FILL;
    private static final int CARET_COLOR   = MStyle.TEXT_PRIMARY;
    private static final float RADIUS      = 3f;
    private static final float PAD_X       = 10f;

    private String text = "";
    private String placeholder = "Search...";
    private boolean active;
    private float fontSize = MStyle.FONT_ITEM;

    public MSearchField text(String text)              { this.text = text != null ? text : ""; return this; }
    public MSearchField placeholder(String placeholder){ this.placeholder = placeholder != null ? placeholder : ""; return this; }
    public MSearchField active(boolean active)         { this.active = active; return this; }
    public MSearchField fontSize(float size)           { this.fontSize = size; return this; }

    public String text()        { return text; }
    public boolean isActive()   { return active; }

    @Override
    public void render(MasonryUI ui) {
        if (ui == null) return;
        Canvas canvas = ui.canvas();
        if (canvas == null) return;

        // Background fill (slightly lighter when focused to confirm input).
        try (Paint fill = new Paint().setColor(active ? FILL_ACTIVE : FILL).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, width, height, RADIUS), fill);
        }
        // Border accent on focus.
        try (Paint border = new Paint().setColor(active ? BORDER_ACTIVE : BORDER)
                .setMode(PaintMode.STROKE).setStrokeWidth(active ? 1.5f : 1f).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x + 0.5f, y + 0.5f, width - 1f, height - 1f, RADIUS), border);
        }

        Font font = ui.fonts().get(fontSize);
        boolean empty = text.isEmpty();
        String display = empty ? placeholder : text;
        int textColor  = empty ? MStyle.TEXT_DISABLED : MStyle.TEXT_PRIMARY;

        // Vertically center cap-height inside the field.
        float baseline = y + height / 2f + fontSize * 0.35f;
        MPainter.drawString(canvas, display, x + PAD_X, baseline, font, textColor);

        // Caret blink at 2 Hz when focused.
        if (active && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            float caretX = x + PAD_X + MPainter.measureWidth(font, text) + 1f;
            float caretTop    = y + 6f;
            float caretBottom = y + height - 6f;
            try (Paint p = new Paint().setColor(CARET_COLOR).setStrokeWidth(1.5f)
                    .setMode(PaintMode.STROKE).setAntiAlias(false)) {
                canvas.drawRect(Rect.makeXYWH(caretX, caretTop, 1f, caretBottom - caretTop), p);
            }
        }
    }
}
