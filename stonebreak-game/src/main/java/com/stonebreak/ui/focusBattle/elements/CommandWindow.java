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
        paint(ui, canvas, rect, view, menu, uiScale, anim, true, 0f);
    }

    /** Veil over the resting window: enough to read as "not your turn" without hiding the rows. */
    static final int STATIC_VEIL = 0x80101820;

    /**
     * The window is always on screen during a fight. {@code active} = the player may choose now:
     * cursor and selection are shown. Otherwise it rests in its STATIC state: every row listed, no
     * cursor, nothing animating, under a veil. Rows a resource shortfall will make unusable (no Qi,
     * no charges, Focus not full) are dimmed in BOTH states, so nothing changes colour on waking.
     *
     * @param veil 0 = awake, 1 = fully veiled; the renderer fades it out as the window wakes
     */
    public static void paint(MasonryUI ui, Canvas canvas, float[] rect, BattleView view,
                             BattleMenuState menu, float uiScale, BattleHudAnimState anim,
                             boolean active, float veil) {
        if (canvas == null || view == null || menu == null) return;
        FocusBattleTheme.battleWindow(canvas, rect);

        List<BattleMenu.Row> rows = BattleMenu.ROOT;
        for (int i = 0; i < rows.size(); i++) {
            float[] row = FocusBattleLayout.rowRect(rect, i, rows.size(), uiScale);
            if (row[3] <= 0f) continue;
            MenuRowPainter.Mark mark = active ? markFor(menu, i) : MenuRowPainter.Mark.NONE;
            paintRow(ui, canvas, row, rows.get(i), mark, view, uiScale, anim, active);
        }

        float amount = FocusBattleTheme.clamp01(veil);
        if (amount > 0f) {
            // Same rounded shape as the window's glass, inside its border, at any scale.
            float inset = FocusBattleTheme.WINDOW_BORDER_WIDTH;
            float radius = Math.max(0f, Math.min(FocusBattleTheme.WINDOW_RADIUS,
                    Math.min(rect[2], rect[3]) / 2f) - inset);
            try (io.github.humbleui.skija.Paint paint = new io.github.humbleui.skija.Paint()
                    .setColor(FocusBattleTheme.fade(STATIC_VEIL, amount)).setAntiAlias(true)) {
                canvas.drawRRect(io.github.humbleui.types.RRect.makeXYWH(rect[0] + inset, rect[1] + inset,
                        rect[2] - 2f * inset, rect[3] - 2f * inset, radius), paint);
            }
        }
    }

    /** Steady READY-glow intensity of the resting window (the live one pulses around this). */
    static final float RESTING_READY_GLOW = 0.7f;

    private static boolean isUsable(BattleView view, BattleCommand command, boolean active) {
        com.stonebreak.battle.api.CommandAvailability availability = view.availability(command);
        if (availability == null || availability.available()) return true;
        return !active && availability.onlyWaitingForTurn();
    }

    private static MenuRowPainter.Mark markFor(BattleMenuState menu, int index) {
        if (index != menu.rootIndex()) return MenuRowPainter.Mark.NONE;
        return menu.submenuOpen() ? MenuRowPainter.Mark.HELD : MenuRowPainter.Mark.SELECTED;
    }

    private static void paintRow(MasonryUI ui, Canvas canvas, float[] row, BattleMenu.Row entry,
                                 MenuRowPainter.Mark mark, BattleView view, float uiScale,
                                 BattleHudAnimState anim, boolean active) {
        BattleCommand command = entry.command();
        boolean focusRow = command == BattleCommand.FOCUS_COMBO;
        boolean ready = focusRow && view.focusReady();
        // Resting, affordable commands report only "not your turn"; the veil already says that, so
        // those rows keep their normal colour. Rows short of a resource dim in both states.
        boolean available = command == null || isUsable(view, command, active);

        // Static means static: the READY glow holds steady while resting and only pulses when live.
        if (ready) paintReadyGlow(canvas, row, active ? anim.focusReadyGlow : RESTING_READY_GLOW, uiScale);
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
