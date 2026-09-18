package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * E3 — the monk's status window: name and status chips, HP bar with exact numbers, Qi pips, the
 * ATB gauge (flash colour when full) and the Focus gauge with its percentage.
 *
 * <p>Five equal rows from {@link FocusBattleLayout#partyRowRect}, each split into caption / gauge /
 * value columns, so the bars line up and the window compresses evenly.
 */
public final class PartyStatusWindow {

    private PartyStatusWindow() {}

    public static void paint(MasonryUI ui, Canvas canvas, float[] rect, BattleView view, float uiScale) {
        paint(ui, canvas, rect, view, uiScale, BattleHudAnimState.NEUTRAL);
    }

    /**
     * {@code anim} drives the window shake and hit flash, the HP ghost trail, the full-ATB flash,
     * the Focus shimmer and the Qi pip pop.
     */
    public static void paint(MasonryUI ui, Canvas canvas, float[] rect, BattleView view, float uiScale,
                             BattleHudAnimState anim) {
        if (canvas == null || view == null || view.monk() == null) return;
        float[] r = FocusBattleLayout.offset(rect, anim.partyShakeX, anim.partyShakeY);
        CombatantView monk = view.monk();

        FocusBattleTheme.battleWindow(canvas, r);
        paintNameRow(ui, canvas, r, view, monk, uiScale);
        paintHp(ui, canvas, r, monk, uiScale, anim);
        paintQi(ui, canvas, r, view, uiScale, anim);
        paintAtb(ui, canvas, r, monk, uiScale, anim);
        paintFocus(ui, canvas, r, view, uiScale, anim);

        if (anim.partyHitFlash > 0f) {
            FocusBattleTheme.roundedFill(canvas, r[0], r[1], r[2], r[3], FocusBattleTheme.WINDOW_RADIUS,
                    FocusBattleTheme.fade(FocusBattleTheme.HIT_FLASH, anim.partyHitFlash * 0.35f));
        }
    }

    private static void paintNameRow(MasonryUI ui, Canvas canvas, float[] rect, BattleView view,
                                     CombatantView monk, float uiScale) {
        float[] row = FocusBattleLayout.partyRowRect(rect, FocusBattleLayout.PARTY_ROW_NAME, uiScale);
        Font font = FocusBattleTheme.font(ui, nameSize(row, uiScale), uiScale);
        if (font == null) return;
        String name = monk.displayName() == null ? "" : monk.displayName();
        FocusBattleTheme.text(canvas, name, row[0],
                FocusBattleTheme.baseline(row[1] + row[3] / 2f, font.getSize()), font, FocusBattleTheme.TEXT);

        // The model may list SURGE as a status too; the queued-hit count is the authoritative chip.
        List<StatusChips.Chip> chips = new ArrayList<>();
        if (view.queuedSurgeHits() > 0) chips.add(StatusChips.surge(view.queuedSurgeHits()));
        for (StatusView s : monk.statuses()) {
            if (s.status() == BattleStatus.SURGE && view.queuedSurgeHits() > 0) continue;
            chips.add(StatusChips.of(s));
        }
        float minX = row[0] + MPainter.measureWidth(font, name) + 10f * uiScale;
        StatusChips.paintRightAligned(ui, canvas, row, minX, chips, uiScale);
    }

    private static void paintHp(MasonryUI ui, Canvas canvas, float[] rect, CombatantView monk,
                                float uiScale, BattleHudAnimState anim) {
        int row = FocusBattleLayout.PARTY_ROW_HP;
        caption(ui, canvas, rect, row, "HP", uiScale);
        float[] bar = inset(FocusBattleLayout.partyBarRect(rect, row, uiScale), 14f * uiScale);
        float f = monk.hpFraction();
        FocusBattleTheme.bar(canvas, bar[0], bar[1], bar[2], bar[3], f, FocusBattleTheme.hpColor(f),
                anim.monkGhostHpFraction, FocusBattleTheme.HP_GHOST);
        value(ui, canvas, rect, row, Math.round(Math.max(0f, monk.hp())) + "/" + Math.round(monk.maxHp()),
                f <= 0.25f ? FocusBattleTheme.HP_LOW : FocusBattleTheme.TEXT, uiScale);
    }

    private static void paintQi(MasonryUI ui, Canvas canvas, float[] rect, BattleView view,
                                float uiScale, BattleHudAnimState anim) {
        int row = FocusBattleLayout.PARTY_ROW_QI;
        caption(ui, canvas, rect, row, "Qi", uiScale);
        float[] area = FocusBattleLayout.partyBarRect(rect, row, uiScale);
        int slots = Math.max(0, view.maxQi());
        if (slots == 0) return;
        float gap = 4f * uiScale;
        float size = Math.min(area[3], Math.min(18f * uiScale, (area[2] - (slots - 1) * gap) / slots));
        if (size <= 0f) return;
        float y = area[1] + (area[3] - size) / 2f;
        int filled = Math.max(0, Math.min(slots, view.qi()));
        for (int i = 0; i < slots; i++) {
            // The newest pip is the one that pops.
            float pop = (i == filled - 1) ? anim.qiPipPop * size * 0.35f : 0f;
            FocusBattleTheme.pip(canvas, area[0] + i * (size + gap) - pop / 2f, y - pop / 2f, size + pop,
                    i < filled, FocusBattleTheme.QI);
        }
        value(ui, canvas, rect, row, filled + "/" + slots, FocusBattleTheme.TEXT_LABEL, uiScale);
    }

    private static void paintAtb(MasonryUI ui, Canvas canvas, float[] rect, CombatantView monk,
                                 float uiScale, BattleHudAnimState anim) {
        int row = FocusBattleLayout.PARTY_ROW_ATB;
        caption(ui, canvas, rect, row, "ATB", uiScale);
        float[] bar = inset(FocusBattleLayout.partyBarRect(rect, row, uiScale), 10f * uiScale);
        float f = FocusBattleTheme.clamp01(monk.atb());
        int color = f >= 1f
                ? FocusBattleTheme.lerpColor(FocusBattleTheme.ATB, FocusBattleTheme.ATB_FULL, anim.atbFullFlash)
                : FocusBattleTheme.ATB;
        FocusBattleTheme.bar(canvas, bar[0], bar[1], bar[2], bar[3], f, color);
    }

    private static void paintFocus(MasonryUI ui, Canvas canvas, float[] rect, BattleView view,
                                   float uiScale, BattleHudAnimState anim) {
        int row = FocusBattleLayout.PARTY_ROW_FOCUS;
        caption(ui, canvas, rect, row, "FOCUS", uiScale);
        float[] bar = inset(FocusBattleLayout.partyBarRect(rect, row, uiScale), 14f * uiScale);
        float f = view.focusFraction();
        boolean ready = view.focusReady();
        FocusBattleTheme.bar(canvas, bar[0], bar[1], bar[2], bar[3], f, FocusBattleTheme.FOCUS);
        if (ready) {
            FocusBattleTheme.roundedStroke(canvas, bar[0] - 1f, bar[1] - 1f, bar[2] + 2f, bar[3] + 2f, 1f,
                    FocusBattleTheme.FOCUS, 1f);
            paintShimmer(canvas, bar, anim.focusShimmer);
        }
        value(ui, canvas, rect, row, Math.round(f * 100f) + "%",
                ready ? FocusBattleTheme.FOCUS : FocusBattleTheme.TEXT, uiScale);
    }

    /** Shimmer hook: a bright band at {@code phase} 0..1 across a full Focus bar; negative = none. */
    private static void paintShimmer(Canvas canvas, float[] bar, float phase) {
        if (phase < 0f) return;
        float bandW = bar[2] * 0.12f;
        float x = bar[0] + (bar[2] + bandW) * FocusBattleTheme.clamp01(phase) - bandW;
        float left = Math.max(bar[0] + 1f, x);
        float right = Math.min(bar[0] + bar[2] - 1f, x + bandW);
        FocusBattleTheme.fillRect(canvas, left, bar[1] + 1f, right - left, bar[3] - 2f,
                FocusBattleTheme.FOCUS_SHIMMER);
    }

    // ─────────────────────────────────────────────── Cells

    private static void caption(MasonryUI ui, Canvas canvas, float[] rect, int row, String text, float uiScale) {
        float[] cell = FocusBattleLayout.partyLabelRect(rect, row, uiScale);
        Font font = FocusBattleTheme.fitFont(ui, text, FocusBattleTheme.FS_LABEL, uiScale, cell[2] - 4f * uiScale);
        if (font == null) return;
        FocusBattleTheme.text(canvas, text, cell[0],
                FocusBattleTheme.baseline(cell[1] + cell[3] / 2f, font.getSize()), font, FocusBattleTheme.TEXT_LABEL);
    }

    private static void value(MasonryUI ui, Canvas canvas, float[] rect, int row, String text, int color,
                              float uiScale) {
        float[] cell = FocusBattleLayout.partyValueRect(rect, row, uiScale);
        Font font = FocusBattleTheme.fitFont(ui, text, FocusBattleTheme.FS_VALUE, uiScale, cell[2]);
        if (font == null) return;
        FocusBattleTheme.textRight(canvas, text, cell[0] + cell[2],
                FocusBattleTheme.baseline(cell[1] + cell[3] / 2f, font.getSize()), font, color);
    }

    private static float nameSize(float[] row, float uiScale) {
        return Math.min(FocusBattleTheme.FS_NAME, row[3] / Math.max(0.01f, uiScale) * 0.85f);
    }

    /** Vertically centres a bar of at most {@code maxHeight} inside its cell. */
    private static float[] inset(float[] cell, float maxHeight) {
        float h = Math.min(cell[3], maxHeight);
        return new float[]{cell[0], (float) Math.floor(cell[1] + (cell[3] - h) / 2f), cell[2], (float) Math.floor(h)};
    }
}
