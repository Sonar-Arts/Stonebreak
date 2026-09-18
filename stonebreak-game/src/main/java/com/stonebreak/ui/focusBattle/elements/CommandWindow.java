package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleMenu;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CommandAvailability;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MSymbol;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.BattleMenuState;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.List;

/**
 * E1 — the root command window: one row per {@link BattleMenu#ROOT} entry with the hand cursor,
 * dimmed unavailable rows, Meditate's remaining charges, the Qi Arts chevron and the Focus Combo
 * row, which shows its fill percentage until the gauge is full and then glows gold with "READY".
 *
 * <p>Rows come from {@link FocusBattleLayout#rowRect}, the same slot formula the screen hit-tests.
 */
public final class CommandWindow {

    private CommandWindow() {}

    public static void paint(MasonryUI ui, Canvas canvas, float[] rect, BattleView view,
                             BattleMenuState menu, float uiScale) {
        paint(ui, canvas, rect, view, menu, uiScale, BattleHudAnimState.NEUTRAL);
    }

    /** {@code anim} drives the cursor bob, the selected-row pulse and the Focus-ready glow. */
    public static void paint(MasonryUI ui, Canvas canvas, float[] rect, BattleView view,
                             BattleMenuState menu, float uiScale, BattleHudAnimState anim) {
        if (canvas == null || view == null || menu == null) return;
        FocusBattleTheme.battleWindow(canvas, rect);

        List<BattleMenu.Row> rows = BattleMenu.ROOT;
        for (int i = 0; i < rows.size(); i++) {
            float[] row = FocusBattleLayout.rowRect(rect, i, rows.size(), uiScale);
            if (row[3] <= 0f) continue;
            paintRow(ui, canvas, row, rows.get(i), markFor(menu, i), view, uiScale, anim);
        }
    }

    private static MenuRowPainter.Mark markFor(BattleMenuState menu, int index) {
        if (index != menu.rootIndex()) return MenuRowPainter.Mark.NONE;
        return menu.submenuOpen() ? MenuRowPainter.Mark.HELD : MenuRowPainter.Mark.SELECTED;
    }

    private static void paintRow(MasonryUI ui, Canvas canvas, float[] row, BattleMenu.Row entry,
                                 MenuRowPainter.Mark mark, BattleView view, float uiScale,
                                 BattleHudAnimState anim) {
        BattleCommand command = entry.command();
        boolean focusRow = command == BattleCommand.FOCUS_COMBO;
        boolean ready = focusRow && view.focusReady();
        boolean available = command == null || isAvailable(view, command);

        if (ready) paintReadyGlow(canvas, row, anim.focusReadyGlow, uiScale);
        MenuRowPainter.paintBackground(canvas, row, mark, anim, uiScale);
        MenuRowPainter.paintCursor(canvas, row, mark, anim, uiScale);

        float right = MenuRowPainter.adornmentRight(row, uiScale);
        int color = available ? FocusBattleTheme.TEXT : FocusBattleTheme.TEXT_DISABLED;

        if (entry.opensQiArts()) {
            float s = row[3] * 0.7f;
            MSymbol.CHEVRON_RIGHT.drawWithShadow(canvas, right - s, row[1] + (row[3] - s) / 2f, s, s,
                    FocusBattleTheme.QI, FocusBattleTheme.TEXT_SHADOW);
            right -= s + 4f * uiScale;
        } else if (focusRow) {
            String tag = ready ? "READY" : Math.round(view.focusFraction() * 100f) + "%";
            int tagColor = ready ? FocusBattleTheme.FOCUS
                    : FocusBattleTheme.lerpColor(FocusBattleTheme.TEXT_DISABLED, FocusBattleTheme.FOCUS, 0.55f);
            right = paintTag(ui, canvas, row, right, tag, tagColor, uiScale);
            if (ready) color = FocusBattleTheme.FOCUS;
        } else if (command == BattleCommand.MEDITATE) {
            right = paintTag(ui, canvas, row, right, "x" + Math.max(0, view.meditateCharges()),
                    available ? FocusBattleTheme.TEXT_LABEL : FocusBattleTheme.TEXT_DISABLED, uiScale);
        } else if (command != null && command.qiCost() > 0) {
            right = MenuRowPainter.paintCostPips(canvas, row, right, command.qiCost(),
                    view.qi() >= command.qiCost(), uiScale) - 4f * uiScale;
        }

        MenuRowPainter.paintLabel(ui, canvas, row, entry.label(), color, right, uiScale);
    }

    private static boolean isAvailable(BattleView view, BattleCommand command) {
        CommandAvailability a = view.availability(command);
        return a == null || a.available();
    }

    /** Right-aligned small caption; returns the x it starts at, less a gap. */
    private static float paintTag(MasonryUI ui, Canvas canvas, float[] row, float right, String tag,
                                  int color, float uiScale) {
        Font font = FocusBattleTheme.font(ui, FocusBattleTheme.FS_VALUE, uiScale);
        if (font == null) return right;
        FocusBattleTheme.textRight(canvas, tag, right,
                FocusBattleTheme.baseline(row[1] + row[3] / 2f, font.getSize()), font, color);
        return right - MPainter.measureWidth(font, tag) - 6f * uiScale;
    }

    private static void paintReadyGlow(Canvas canvas, float[] row, float strength, float uiScale) {
        float k = FocusBattleTheme.clamp01(strength);
        if (k <= 0f) return;
        float r = 3f * uiScale;
        // Two stacked translucent fills: a wide soft halo and the row itself.
        float halo = 2f * uiScale;
        FocusBattleTheme.roundedFill(canvas, row[0] - halo, row[1] - halo, row[2] + 2f * halo,
                row[3] + 2f * halo, r + halo, FocusBattleTheme.fade(FocusBattleTheme.FOCUS_GLOW, k * 0.5f));
        FocusBattleTheme.roundedFill(canvas, row[0], row[1], row[2], row[3], r,
                FocusBattleTheme.fade(FocusBattleTheme.FOCUS_GLOW, k));
        FocusBattleTheme.roundedStroke(canvas, row[0], row[1], row[2], row[3], r,
                FocusBattleTheme.fade(FocusBattleTheme.FOCUS, k), Math.max(1f, uiScale));
    }
}
