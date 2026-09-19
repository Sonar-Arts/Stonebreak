package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.rendering.UI.masonryUI.MCastBar;
import com.stonebreak.rendering.UI.masonryUI.MChipRow;
import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.rendering.UI.masonryUI.MGauge;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattlePalette;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.List;

/**
 * E5 + E6: the enemy plate. A HUD frame holding the name and status chips, the HP gauge (exact
 * numbers: the proof of concept shows real HP) and the enemy's gauge: a plain ATB fill that becomes
 * a labelled {@link MCastBar} while an attack is telegraphed, with the parry window marked on the
 * same timeline ({@code 0..impactTime} seconds). The marker pulses while the monk is guarding.
 *
 * <p>The window owns one widget per moving part; the HP trail and hit flash are the gauge's own.
 */
public final class EnemyPlate {

    public static final String ATB_LABEL = "ATB";
    public static final String PARRY_LABEL = "PARRY";
    public static final String BLOCK_LABEL = "BLOCK";
    public static final String CANCELLED_LABEL = "CANCELLED";

    // Design metrics (multiplied by the HUD scale, compressed together on a short plate).
    private static final float PAD = 10f;
    private static final float NAME_H = 22f;
    private static final float HP_H = 14f;
    private static final float CAST_H = 38f;
    private static final float GAP = 2f;
    private static final float NAME_GAP = 10f;
    private static final float ATB_BAR_H = 10f;
    /** Height the plate's content needs at scale 1; a shorter plate shrinks its lines together. */
    private static final float DESIGN_H = 2f * PAD + NAME_H + GAP + HP_H + GAP + CAST_H;

    private final MGauge hp = new MGauge().vitalRamp().ghost(true)
            .valueAsFraction().valuePlacement(MGauge.ValuePlacement.OVER_CENTER);
    private final MCastBar cast = new MCastBar().windowColor(BattlePalette.REACT_WINDOW);
    private final MChipRow chips = new MChipRow().align(MChipRow.Align.RIGHT);
    private final StatusChips chipSource = new StatusChips();

    public MGauge hpGauge() { return hp; }
    public MCastBar castBar() { return cast; }
    public MChipRow chipRow() { return chips; }

    public void reset() {
        hp.reset();
        cast.reset();
    }

    public void update(float dt, BattleView view, List<BattleEvent> events) {
        sync(view);
        if (events != null) {
            for (BattleEvent event : events) {
                if (event instanceof BattleEvent.DamageDealt hit && hit.target() == CombatantId.ARCHON) {
                    PartyStatusWindow.flashOnHit(hp, hit);
                }
            }
        }
        hp.update(dt);
        cast.update(dt);
    }

    /** Binds the widgets to the view. Idempotent: the same view twice changes nothing. */
    private void sync(BattleView view) {
        CombatantView enemy = view == null ? null : view.archon();
        if (enemy == null) return;
        hp.value(enemy.hp(), enemy.maxHp());
        chips.chips(chipSource.chipsFor(enemy.statuses(), false));

        TelegraphView t = view.telegraph();
        if (t == null) {
            // Same label row as the cast bar, so the bar does not jump when a windup starts.
            cast.clearWindow().label(ATB_LABEL).labelColor(MStyle.TEXT_SECONDARY).windowLabel("")
                    .interrupted(false).pulse(false)
                    .fillColor(BattlePalette.ATB).barHeight(ATB_BAR_H).fraction(enemy.atb());
            return;
        }
        boolean guarding = view.monk() != null && view.monk().has(BattleStatus.GUARDING);
        cast.timeline(t.elapsed(), t.impactTime())
                .window(t.parryWindowStart(), t.parryWindowEnd())
                .windowLabel(t.cancelled() ? CANCELLED_LABEL : guarding ? PARRY_LABEL : BLOCK_LABEL)
                .label(t.action() == null ? "" : t.action().displayName())
                .labelColor(MColor.lerp(MStyle.TEXT_PRIMARY, BattlePalette.CAST_END, t.progress() * 0.6f))
                .fillRamp(BattlePalette.CAST_START, BattlePalette.CAST_END)
                .barHeight(0f)
                .interrupted(t.cancelled())
                .pulse(guarding && !t.cancelled());
    }

    // ─────────────────────────────────────────────── Geometry

    /** The plate's three stacked lines {@code {name, hp, cast}}, each {@code {x, y, w, h}}. */
    public static float[][] lines(float[] rect, float scale) {
        float k = DESIGN_H * scale <= 0f ? 1f : Math.min(1f, rect[3] / (DESIGN_H * scale));
        float x = rect[0] + PAD * scale, w = Math.max(0f, rect[2] - 2f * PAD * scale);
        float unit = scale * k;
        float nameY = rect[1] + PAD * unit;
        float hpY = nameY + (NAME_H + GAP) * unit;
        float castY = hpY + (HP_H + GAP) * unit;
        return new float[][]{{x, nameY, w, NAME_H * unit}, {x, hpY, w, HP_H * unit}, {x, castY, w, CAST_H * unit}};
    }

    // ─────────────────────────────────────────────── Render

    public void render(MasonryUI ui, float[] rect, BattleView view, float scale) {
        Canvas canvas = ui == null ? null : ui.canvas();
        if (canvas == null || rect == null || view == null || view.archon() == null || !(scale > 0f)) return;
        if (!(rect[2] > 0f) || !(rect[3] > 0f)) return;
        sync(view);
        MPainter.hudFrame(canvas, rect[0], rect[1], rect[2], rect[3], BattlePalette.accent(CombatantId.ARCHON), 1f);

        float[][] lines = lines(rect, scale);
        float[] nameRow = lines[0];
        CombatantView enemy = view.archon();
        String name = enemy.displayName() == null ? "" : enemy.displayName();
        Font font = ui.fonts().get(Math.min(MStyle.FONT_ITEM, nameRow[3] / scale * 0.8f), scale);
        float nameEnd = nameRow[0];
        if (font != null) {
            MPainter.drawText(canvas, name, nameRow[0],
                    MPainter.baselineFor(nameRow[1] + nameRow[3] / 2f, font.getSize()), font, MStyle.TEXT_PRIMARY,
                    MPainter.Align.LEFT);
            nameEnd += MPainter.measureWidth(font, name) + NAME_GAP * scale;
        }
        chips.scale(scale).bounds(nameRow[0], nameRow[1], nameRow[2], nameRow[3]).minX(nameEnd).render(ui);

        hp.scale(scale).bounds(lines[1][0], lines[1][1], lines[1][2], lines[1][3]).render(ui);
        cast.scale(scale).bounds(lines[2][0], lines[2][1], lines[2][2], lines[2][3]).render(ui);
    }
}
