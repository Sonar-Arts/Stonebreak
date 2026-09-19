package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHelpText;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * E4: one line above the bottom windows describing the highlighted command, or why it cannot be
 * used ({@link MStyle#TEXT_WARN}). Draws nothing at all for an empty line, so the strip never sits
 * there blank. Stateless: the cross-fade amount comes from the caller.
 */
public final class HelpStrip {

    private static final float PAD = 12f;

    private HelpStrip() {}

    /** {@code fade} 0..1 is the text opacity (cross-fade); the frame itself stays solid. */
    public static void render(MasonryUI ui, float[] rect, BattleHelpText.Line line, float scale, float fade) {
        Canvas canvas = ui == null ? null : ui.canvas();
        if (canvas == null || rect == null || line == null || line.isEmpty()) return;
        if (!(rect[2] > 0f) || !(rect[3] > 0f) || !(scale > 0f)) return;
        MPainter.hudFrame(canvas, rect[0], rect[1], rect[2], rect[3]);
        float pad = PAD * scale;
        Font font = ui.fonts().fit(line.text(), MStyle.FONT_META * scale, rect[2] - 2f * pad, 0.6f);
        if (font == null) return;
        int color = line.warning() ? MStyle.TEXT_WARN : MStyle.TEXT_PRIMARY;
        MPainter.drawText(canvas, line.text(), rect[0] + pad,
                MPainter.baselineFor(rect[1] + rect[3] / 2f, font.getSize()), font, MColor.fade(color, fade),
                MPainter.Align.LEFT);
    }
}
