package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;

/** E13 — the ATB mode chip ("ACTIVE"). A label parameter leaves room for a future Wait mode. */
public final class ModeTag {

    public static final String ACTIVE = "ACTIVE";

    private ModeTag() {}

    public static void paint(MasonryUI ui, Canvas canvas, float[] rect, String label, float uiScale) {
        if (canvas == null || label == null || label.isEmpty()) return;
        FocusBattleTheme.battleWindow(canvas, rect);
        float dot = Math.min(rect[3] * 0.32f, 8f * uiScale);
        float cx = rect[0] + rect[3] * 0.5f;
        float cy = rect[1] + rect[3] / 2f;
        try (Paint p = new Paint().setColor(FocusBattleTheme.ATB).setAntiAlias(true)) {
            canvas.drawCircle(cx, cy, dot / 2f, p);
        }
        float textX = cx + dot / 2f + 6f * uiScale;
        Font font = FocusBattleTheme.fitFont(ui, label, FocusBattleTheme.FS_TAG, uiScale,
                rect[0] + rect[2] - 6f * uiScale - textX);
        if (font == null) return;
        FocusBattleTheme.text(canvas, label, textX, FocusBattleTheme.baseline(cy, font.getSize()), font,
                FocusBattleTheme.TEXT);
    }
}
