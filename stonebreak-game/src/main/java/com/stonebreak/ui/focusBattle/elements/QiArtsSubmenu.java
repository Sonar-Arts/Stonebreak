package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleMenu;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CommandAvailability;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.BattleMenuState;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;

import java.util.List;

/**
 * E2 — the Qi Arts submenu: one row per {@link BattleMenu#QI_ARTS} entry with its cost as jade
 * pips. A row dims when the monk cannot afford it or the model reports it unavailable.
 */
public final class QiArtsSubmenu {

    private QiArtsSubmenu() {}

    public static void paint(MasonryUI ui, Canvas canvas, float[] rect, BattleView view,
                             BattleMenuState menu, float uiScale) {
        paint(ui, canvas, rect, view, menu, uiScale, BattleHudAnimState.NEUTRAL);
    }

    public static void paint(MasonryUI ui, Canvas canvas, float[] rect, BattleView view,
                             BattleMenuState menu, float uiScale, BattleHudAnimState anim) {
        if (canvas == null || view == null || menu == null) return;
        FocusBattleTheme.battleWindow(canvas, rect);

        List<BattleMenu.Row> rows = BattleMenu.QI_ARTS;
        for (int i = 0; i < rows.size(); i++) {
            float[] row = FocusBattleLayout.rowRect(rect, i, rows.size(), uiScale);
            if (row[3] <= 0f) continue;
            BattleCommand command = rows.get(i).command();
            boolean affordable = view.qi() >= command.qiCost();
            boolean usable = affordable && isAvailable(view, command);
            MenuRowPainter.Mark mark = menu.submenuOpen() && i == menu.submenuIndex()
                    ? MenuRowPainter.Mark.SELECTED : MenuRowPainter.Mark.NONE;

            MenuRowPainter.paintBackground(canvas, row, mark, anim, uiScale);
            MenuRowPainter.paintCursor(canvas, row, mark, anim, uiScale);
            float right = MenuRowPainter.paintCostPips(canvas, row, MenuRowPainter.adornmentRight(row, uiScale),
                    command.qiCost(), affordable, uiScale) - 4f * uiScale;
            MenuRowPainter.paintLabel(ui, canvas, row, rows.get(i).label(),
                    usable ? FocusBattleTheme.TEXT : FocusBattleTheme.TEXT_DISABLED, right, uiScale);
        }
    }

    private static boolean isAvailable(BattleView view, BattleCommand command) {
        CommandAvailability a = view.availability(command);
        return a == null || a.available();
    }
}
