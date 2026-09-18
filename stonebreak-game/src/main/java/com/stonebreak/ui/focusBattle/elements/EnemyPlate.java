package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * E5 — the enemy plate: name, status chips and the HP bar with exact numbers (the proof of concept
 * shows real HP). The ATB / telegraph bar inside it is {@link EnemyGaugeBar}, painted separately
 * into {@link FocusBattleLayout#enemyGaugeRect(float[], float)}.
 */
public final class EnemyPlate {

    private EnemyPlate() {}

    public static void paint(MasonryUI ui, Canvas canvas, float[] rect, CombatantView enemy, float uiScale) {
        paint(ui, canvas, rect, enemy, uiScale, BattleHudAnimState.NEUTRAL);
    }

    /**
     * {@code anim} drives the HP ghost trail and the red hit flash. The shake is applied by the
     * caller (offset the rect) so the plate and its gauge bar move together.
     */
    public static void paint(MasonryUI ui, Canvas canvas, float[] rect, CombatantView enemy, float uiScale,
                             BattleHudAnimState anim) {
        if (canvas == null || enemy == null) return;
        FocusBattleTheme.battleWindow(canvas, rect);

        float[] nameRow = FocusBattleLayout.enemyNameRect(rect, uiScale);
        Font nameFont = FocusBattleTheme.font(ui,
                Math.min(FocusBattleTheme.FS_NAME, nameRow[3] / Math.max(0.01f, uiScale) * 0.8f), uiScale);
        String name = enemy.displayName() == null ? "" : enemy.displayName();
        float minChipX = nameRow[0];
        if (nameFont != null) {
            FocusBattleTheme.text(canvas, name, nameRow[0],
                    FocusBattleTheme.baseline(nameRow[1] + nameRow[3] / 2f, nameFont.getSize()), nameFont,
                    FocusBattleTheme.TEXT);
            minChipX += MPainter.measureWidth(nameFont, name) + 10f * uiScale;
        }
        StatusChips.paintRightAligned(ui, canvas, nameRow, minChipX, StatusChips.of(enemy.statuses()), uiScale);

        float[] hp = FocusBattleLayout.enemyHpBarRect(rect, uiScale);
        float f = enemy.hpFraction();
        FocusBattleTheme.bar(canvas, hp[0], hp[1], hp[2], hp[3], f, FocusBattleTheme.hpColor(f),
                anim.archonGhostHpFraction, FocusBattleTheme.HP_GHOST);
        Font valueFont = FocusBattleTheme.font(ui,
                Math.min(FocusBattleTheme.FS_LABEL, hp[3] / Math.max(0.01f, uiScale) * 0.8f), uiScale);
        if (valueFont != null) {
            String numbers = Math.round(Math.max(0f, enemy.hp())) + "/" + Math.round(enemy.maxHp());
            FocusBattleTheme.textCentered(canvas, numbers, hp[0] + hp[2] / 2f,
                    FocusBattleTheme.baseline(hp[1] + hp[3] / 2f, valueFont.getSize()), valueFont,
                    FocusBattleTheme.TEXT);
        }

        if (anim.enemyHitFlash > 0f) {
            FocusBattleTheme.roundedFill(canvas, rect[0], rect[1], rect[2], rect[3], FocusBattleTheme.WINDOW_RADIUS,
                    FocusBattleTheme.fade(FocusBattleTheme.HIT_FLASH, anim.enemyHitFlash * 0.35f));
        }
    }
}
