package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.rendering.UI.masonryUI.MChipRow;
import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.rendering.UI.masonryUI.MGauge;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MPipRow;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattlePalette;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.List;

/**
 * E3: the monk's status window. A HUD frame holding five equal rows: name + status chips, HP, Qi,
 * ATB and Focus. Every moving part is a library widget this window owns one instance of, so the HP
 * trail, the hit flash, the Qi pop/spend, the full-ATB blink and the Focus shimmer are the widgets'
 * own animations; this class only feeds them the view and the frame's battle events.
 *
 * <p>The caller passes the rect the window is drawn in (already slid or shaken), so the same
 * instance renders identically wherever it is put.
 */
public final class PartyStatusWindow {

    public static final int ROWS = 5;
    public static final int ROW_NAME = 0, ROW_HP = 1, ROW_QI = 2, ROW_ATB = 3, ROW_FOCUS = 4;

    // Design metrics (multiplied by the HUD scale).
    private static final float PAD = 10f;
    private static final float ROW_GAP = 4f;
    private static final float CAPTION_W = 58f;
    private static final float VALUE_W = 94f;
    private static final float NAME_GAP = 10f;

    private final MGauge hp = new MGauge().caption("HP").vitalRamp().ghost(true).barHeight(14f)
            .valueAsFraction().valuePlacement(MGauge.ValuePlacement.BESIDE);
    private final MPipRow qi = new MPipRow().color(BattlePalette.QI).pipSize(18f).gap(4f).autoAnimate(true);
    private final MGauge atb = new MGauge().caption("ATB").fillColor(BattlePalette.ATB)
            .fullGlow(BattlePalette.ATB_READY).flash(BattlePalette.ATB_READY).flashSeconds(0.45f).barHeight(10f);
    private final MGauge focus = new MGauge().caption("FOCUS").fillColor(BattlePalette.FOCUS)
            .fullGlow(BattlePalette.FOCUS).flash(MStyle.TEXT_PRIMARY).flashSeconds(0.6f).barHeight(14f)
            .valueAsPercent().valuePlacement(MGauge.ValuePlacement.BESIDE);
    private final MChipRow chips = new MChipRow().align(MChipRow.Align.RIGHT);
    private final StatusChips chipSource = new StatusChips();

    public MGauge hpGauge() { return hp; }
    public MPipRow qiPips() { return qi; }
    public MGauge atbGauge() { return atb; }
    public MGauge focusGauge() { return focus; }
    public MChipRow chipRow() { return chips; }

    /** Forgets the last fight: the next values are taken as they are, with no trail, pop or flash. */
    public void reset() {
        hp.reset();
        atb.reset();
        focus.reset();
        qi.reset();
    }

    /** Feeds the view and this frame's events to the widgets, then advances their animations. */
    public void update(float dt, BattleView view, List<BattleEvent> events) {
        sync(view);
        if (events != null) {
            for (BattleEvent event : events) consume(event);
        }
        hp.update(dt);
        qi.update(dt);
        atb.update(dt);
        focus.update(dt);
    }

    private void consume(BattleEvent event) {
        switch (event) {
            case BattleEvent.DamageDealt hit when hit.target() == CombatantId.MONK -> flashOnHit(hp, hit);
            case BattleEvent.TurnReady ready when ready.who() == CombatantId.MONK -> atb.pulseFlash();
            case BattleEvent.FocusFull full -> focus.pulseFlash();
            default -> { }
        }
    }

    /** Red flash on a gauge, as strong as the blow: a parry (the player's win) does not flash at all. */
    static void flashOnHit(MGauge gauge, BattleEvent.DamageDealt hit) {
        BattleEvent.DamageFlavor flavor = hit.flavor() == null ? BattleEvent.DamageFlavor.NORMAL : hit.flavor();
        float strength = switch (flavor) {
            case CRITICAL -> 1f;
            case NORMAL -> 0.75f;
            case BLOCKED -> 0.3f;
            case PARRIED -> 0f;
        };
        if (strength > 0f) gauge.flash(MColor.fade(BattlePalette.HIT_FLASH, strength)).pulseFlash();
    }

    /** Binds the widgets to the view. Idempotent: the same view twice changes nothing. */
    private void sync(BattleView view) {
        CombatantView monk = view == null ? null : view.monk();
        if (monk == null) return;
        hp.value(monk.hp(), monk.maxHp())
                .valueColor(monk.hpFraction() <= 0.25f ? MStyle.VITAL_CRIT : MStyle.TEXT_PRIMARY);
        qi.count(view.maxQi()).filled(view.qi());
        atb.fraction(monk.atb());
        focus.fraction(view.focusFraction()).shimmer(view.focusReady())
                .valueColor(view.focusReady() ? BattlePalette.FOCUS : MStyle.TEXT_PRIMARY);
        chips.chips(chipSource.chipsFor(monk.statuses(), view.queuedSurgeHits() > 0));
    }

    // ─────────────────────────────────────────────── Geometry

    /** Row {@code index} of {@link #ROWS} inside {@code rect}: {@code {x, y, w, h}}. */
    public static float[] rowRect(float[] rect, int index, float scale) {
        float pad = PAD * scale, gap = ROW_GAP * scale;
        float rowH = Math.max(0f, (rect[3] - 2f * pad - (ROWS - 1) * gap) / ROWS);
        return new float[]{rect[0] + pad, rect[1] + pad + index * (rowH + gap), Math.max(0f, rect[2] - 2f * pad), rowH};
    }

    // ─────────────────────────────────────────────── Render

    public void render(MasonryUI ui, float[] rect, BattleView view, float scale) {
        Canvas canvas = ui == null ? null : ui.canvas();
        if (canvas == null || rect == null || view == null || view.monk() == null || !(scale > 0f)) return;
        if (!(rect[2] > 0f) || !(rect[3] > 0f)) return;
        sync(view);
        MPainter.hudFrame(canvas, rect[0], rect[1], rect[2], rect[3], BattlePalette.accent(CombatantId.MONK), 1f);

        float[] row = rowRect(rect, ROW_HP, scale);
        // Narrow windows give the columns up proportionally, so the bars never vanish.
        float captionW = Math.min(CAPTION_W, row[2] / scale * 0.2f);
        float valueW = Math.min(VALUE_W, row[2] / scale * 0.28f);
        Font meta = ui.fonts().get(Math.min(MStyle.FONT_META, row[3] / scale * 0.85f), scale);

        paintName(ui, canvas, rowRect(rect, ROW_NAME, scale), view.monk(), scale);
        place(hp, row, captionW, valueW, scale).render(ui);
        paintQi(ui, canvas, rowRect(rect, ROW_QI, scale), captionW, valueW, meta, scale);
        place(atb, rowRect(rect, ROW_ATB, scale), captionW, valueW, scale).render(ui);
        place(focus, rowRect(rect, ROW_FOCUS, scale), captionW, valueW, scale).render(ui);
    }

    private static MGauge place(MGauge gauge, float[] row, float captionW, float valueW, float scale) {
        // A gauge without a value still ends where the others' bars end, so the column stays straight.
        boolean valueless = gauge.resolvedValueText().isEmpty();
        float w = valueless ? row[2] - valueW * scale : row[2];
        return gauge.scale(scale).captionWidth(captionW).valueWidth(valueW).bounds(row[0], row[1], w, row[3]);
    }

    private void paintName(MasonryUI ui, Canvas canvas, float[] row, CombatantView monk, float scale) {
        Font font = ui.fonts().get(Math.min(MStyle.FONT_ITEM, row[3] / scale * 0.85f), scale);
        String name = monk.displayName() == null ? "" : monk.displayName();
        float nameEnd = row[0];
        if (font != null) {
            MPainter.drawText(canvas, name, row[0], MPainter.baselineFor(row[1] + row[3] / 2f, font.getSize()),
                    font, MStyle.TEXT_PRIMARY, MPainter.Align.LEFT);
            nameEnd += MPainter.measureWidth(font, name) + NAME_GAP * scale;
        }
        chips.scale(scale).bounds(row[0], row[1], row[2], row[3]).minX(nameEnd).render(ui);
    }

    /** The Qi row: the gauges' caption and value columns, with pips where they have a bar. */
    private void paintQi(MasonryUI ui, Canvas canvas, float[] row, float captionW, float valueW, Font meta,
                         float scale) {
        float left = row[0] + captionW * scale;
        qi.scale(scale).bounds(left, row[1], Math.max(0f, row[0] + row[2] - valueW * scale - left), row[3]).render(ui);
        if (meta == null) return;
        float baseline = MPainter.baselineFor(row[1] + row[3] / 2f, meta.getSize());
        MPainter.drawText(canvas, "Qi", row[0], baseline, meta, MStyle.TEXT_SECONDARY, MPainter.Align.LEFT);
        MPainter.drawText(canvas, qi.filled() + "/" + qi.count(), row[0] + row[2], baseline, meta,
                MStyle.TEXT_SECONDARY, MPainter.Align.RIGHT);
    }
}
