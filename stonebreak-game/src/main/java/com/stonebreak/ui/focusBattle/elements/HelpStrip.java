package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHelpText;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * E4 — one line above the bottom windows describing the highlighted command, or why it cannot be
 * used (amber). Draws nothing at all for an empty line, so the strip never sits there blank.
 */
public final class HelpStrip {

    private HelpStrip() {}

    public static void paint(MasonryUI ui, Canvas canvas, float[] rect, BattleHelpText.Line line, float uiScale) {
        paint(ui, canvas, rect, line, uiScale, 1f);
    }

    /** {@code fade} 0..1 is the text opacity (cross-fade hook); the window itself stays solid. */
    public static void paint(MasonryUI ui, Canvas canvas, float[] rect, BattleHelpText.Line line,
                             float uiScale, float fade) {
        if (canvas == null || line == null || line.isEmpty()) return;
        FocusBattleTheme.battleWindow(canvas, rect);
        float pad = 12f * uiScale;
        Font font = FocusBattleTheme.fitFont(ui, line.text(), FocusBattleTheme.FS_HELP, uiScale, rect[2] - 2f * pad);
        if (font == null) return;
        int color = line.warning() ? FocusBattleTheme.TEXT_WARNING : FocusBattleTheme.TEXT;
        FocusBattleTheme.text(canvas, line.text(), rect[0] + pad,
                FocusBattleTheme.baseline(rect[1] + rect[3] / 2f, font.getSize()), font,
                FocusBattleTheme.fade(color, fade));
    }
}
