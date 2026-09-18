package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * What the command window and the Qi Arts submenu share: the row highlight, the hand cursor in its
 * gutter and the fitted, shadowed label. Row-specific adornments (costs, charges, chevron) are drawn
 * by the caller into the space {@link #adornmentRight} leaves on the right.
 */
final class MenuRowPainter {

    /** How a row relates to the cursor. */
    enum Mark {
        NONE,
        /** The cursor is on this row. */
        SELECTED,
        /** A root row whose submenu is open: highlighted, but the live cursor is elsewhere. */
        HELD
    }

    private static final float CURSOR_BOB_PX = 3f;

    private MenuRowPainter() {}

    /** Width of the cursor gutter on the left of every row. */
    static float gutter(float[] row, float uiScale) {
        return Math.min(row[3] * 1.35f, 40f * uiScale);
    }

    /** Right edge available to adornments. */
    static float adornmentRight(float[] row, float uiScale) {
        return row[0] + row[2] - 8f * uiScale;
    }

    static void paintBackground(Canvas canvas, float[] row, Mark mark, BattleHudAnimState anim, float uiScale) {
        if (mark == Mark.NONE) return;
        float r = 3f * uiScale;
        if (mark == Mark.HELD) {
            FocusBattleTheme.roundedFill(canvas, row[0], row[1], row[2], row[3], r, FocusBattleTheme.ROW_HELD);
            return;
        }
        int fill = FocusBattleTheme.lerpColor(FocusBattleTheme.ROW_SELECTED, FocusBattleTheme.ROW_SELECTED_EDGE,
                anim.selectedPulse * 0.5f);
        FocusBattleTheme.roundedFill(canvas, row[0], row[1], row[2], row[3], r, fill);
        FocusBattleTheme.roundedStroke(canvas, row[0], row[1], row[2], row[3], r,
                FocusBattleTheme.ROW_SELECTED_EDGE, 1f);
    }

    static void paintCursor(Canvas canvas, float[] row, Mark mark, BattleHudAnimState anim, float uiScale) {
        if (mark == Mark.NONE) return;
        float gutter = gutter(row, uiScale);
        float h = row[3] * 0.66f;
        float tipGap = 6f * uiScale;   // air between the fingertip and the label, bob included
        float w = Math.min(gutter - tipGap - 2f * uiScale, h * 1.45f);
        float bob = mark == Mark.SELECTED ? anim.cursorBob * CURSOR_BOB_PX * uiScale : 0f;
        float x = row[0] + gutter - tipGap - w + Math.min(bob, tipGap - 1f);
        HandCursor.paint(canvas, x, row[1] + (row[3] - h) / 2f, w, h, mark == Mark.SELECTED ? 1f : 0.45f);
    }

    /** Label after the gutter, shrunk if needed to end before {@code rightLimit}. */
    static void paintLabel(MasonryUI ui, Canvas canvas, float[] row, String label, int color,
                           float rightLimit, float uiScale) {
        float x = row[0] + gutter(row, uiScale);
        // A compressed row must shrink its text too, or labels collide vertically.
        float size = Math.min(FocusBattleTheme.FS_ROW, row[3] / Math.max(0.01f, uiScale) * 0.72f);
        Font font = FocusBattleTheme.fitFont(ui, label, size, uiScale, Math.max(0f, rightLimit - x));
        if (font == null) return;
        FocusBattleTheme.text(canvas, label, x, FocusBattleTheme.baseline(row[1] + row[3] / 2f, font.getSize()),
                font, color);
    }

    /**
     * Qi cost as jade diamond pips, right-aligned at {@code right}.
     * @return the x where the pips start
     */
    static float paintCostPips(Canvas canvas, float[] row, float right, int cost, boolean affordable,
                               float uiScale) {
        if (cost <= 0) return right;
        float size = Math.min(row[3] * 0.5f, 14f * uiScale);
        float gap = 2f * uiScale;
        float x = right - cost * size - (cost - 1) * gap;
        float y = row[1] + (row[3] - size) / 2f;
        int color = affordable ? FocusBattleTheme.QI : FocusBattleTheme.TEXT_DISABLED;
        for (int i = 0; i < cost; i++) {
            FocusBattleTheme.pip(canvas, x + i * (size + gap), y, size, true, color);
        }
        return x;
    }
}
